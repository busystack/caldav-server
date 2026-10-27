import assert from 'node:assert/strict';
import {call} from './transport.mjs';

export async function qualifyDavCredentials(page, base, privateValues, username = process.env.BUSYSTACK_BROWSER_USERNAME) {
  const label = 'Browser-qualified DAV device';
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
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + encoded}})).status, 404);
  await page.goto(base + '/');
  assert.ok(!(await page.content()).includes(credential), 'A device secret must not appear again in the management page');
  const entry = page.locator('li').filter({hasText: label});
  await Promise.all([page.waitForURL(base + '/'), entry.getByRole('button', {name: 'Revoke', exact: true}).click()]);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + encoded}})).status, 401);
  console.log('BROWSER_DAV_CREDENTIAL_ISSUANCE_DISPLAY_ONCE_AND_REVOCATION_PASSED');
}
