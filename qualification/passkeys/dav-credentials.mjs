import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {call} from './transport.mjs';

export async function issueDavCredential(page, base, privateValues, label, username = process.env.BUSYSTACK_BROWSER_USERNAME) {
  await page.goto(base + '/');
  const form = page.locator('form').filter({has: page.locator('input[name=label]')});
  await form.locator('input[name=label]').fill(label);
  await form.locator('input[name=days]').fill('1');
  const [response] = await Promise.all([page.waitForResponse(value => value.request().method() === 'POST' && value.url() === base + '/credentials'),
    form.getByRole('button', {name: 'Issue credential', exact: true}).click()]);
  assert.ok((response.headers()['cache-control'] || '').includes('no-store'));
  await page.getByRole('heading', {name: 'New DAV credential', exact: true}).waitFor();
  const credential = await page.locator('code').innerText();
  assert.equal(credential.length, 80);
  const encoded = Buffer.from(username + ':' + credential).toString('base64');
  privateValues.push(credential, encoded);
  await page.goto(base + '/');
  assert.ok(!(await page.content()).includes(credential), 'A device secret must not appear again in management');
  return {credential, encoded};
}

export async function qualifyDavCredentials(page, base, privateValues, username = process.env.BUSYSTACK_BROWSER_USERNAME) {
  const label = 'Browser-qualified DAV device';
  const {credential, encoded} = await issueDavCredential(page, base, privateValues, label, username);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + encoded}})).status, 404);
  await verifySyncDepth(page, base, encoded);
  await page.goto(base + '/');
  assert.ok(!(await page.content()).includes(credential), 'A device secret must not appear again in the management page');
  const entry = page.locator('li').filter({hasText: label});
  await Promise.all([page.waitForURL(base + '/'), entry.getByRole('button', {name: 'Revoke', exact: true}).click()]);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + encoded}})).status, 401);
  console.log('BROWSER_DAV_CREDENTIAL_ISSUANCE_DISPLAY_ONCE_AND_REVOCATION_PASSED');
}


async function verifySyncDepth(page, base, encoded) {
  const headers = {Authorization: 'Basic ' + encoded};
  const discovery = await call(base, '/dav/', {method: 'PROPFIND', headers: {...headers, Depth: '0'},
    body: '<D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/></D:prop></D:propfind>'});
  assert.equal(discovery.status, 207);
  const principal = await page.evaluate(xml => {
    const doc = new DOMParser().parseFromString(xml, 'application/xml');
    const property = doc.getElementsByTagNameNS('DAV:', 'current-user-principal')[0];
    return property?.getElementsByTagNameNS('DAV:', 'href')[0]?.textContent;
  }, discovery.body);
  assert.ok(principal);
  const id = new URL(principal, base).pathname.split('/').filter(Boolean).at(-1);
  assert.match(id, /^[0-9a-f-]{36}$/);
  const path = '/dav/calendars/' + id + '/depth-probe-' + randomUUID() + '/';
  assert.equal((await call(base, path, {method: 'MKCALENDAR', headers})).status, 201);
  try {
    const body = '<D:sync-collection xmlns:D="DAV:"><D:sync-token/><D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>';
    const observed = {};
    for (const depth of [undefined, '0', '1']) {
      const response = await call(base, path, {method: 'REPORT', headers: {...headers, ...(depth === undefined ? {} : {Depth: depth})}, body});
      observed[depth === undefined ? 'missing' : depth] = response.status;
    }
    assert.equal(observed.missing, 207);
    assert.equal(observed['0'], 207);
    const expected = process.env.BUSYSTACK_SYNC_DEPTH_EXPECTED;
    assert.ok(expected === 'true' || expected === 'false', 'Calling harness must record the intended configuration');
    assert.equal(observed['1'], expected === 'true' ? 207 : 400);
    const other = await call(base, path, {method: 'REPORT', headers: {...headers, Depth: '1'}, body: body.replace('<D:sync-level>1</D:sync-level>', '<D:sync-level>2</D:sync-level>')});
    assert.ok([400, 403].includes(other.status));
    console.log('DAV_SYNC_CONFIGURATION=' + JSON.stringify({configurationInput: process.env.BUSYSTACK_SYNC_DEPTH_INPUT,
      observedDepthStatus: observed, depthOneWithOtherSyncLevelStatus: other.status, allowSyncDepthOne: observed['1'] === 207}));
  } finally {
    assert.equal((await call(base, path, {method: 'DELETE', headers})).status, 204);
  }
}
