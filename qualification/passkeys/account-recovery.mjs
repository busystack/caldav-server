import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {call, json} from './transport.mjs';
import {issueDavCredential} from './dav-credentials.mjs';
import {qualifyOAuth} from './oauth.mjs';
import {organizationPrimary, primaryCannotMutate, signOut} from './oidc.mjs';

export async function qualifyAccountRecovery({page, base, privateValues, administratorPage,
  username, cdp, authenticatorId}) {
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  const federated = process.env.BUSYSTACK_BROWSER_OIDC === 'true';
  async function primary() {
    if (federated) await organizationPrimary(page, base, privateValues);
    else {
      await page.goto(base + '/login');
      const password = readFileSync(process.env.BUSYSTACK_BROWSER_PASSWORD_FILE, 'utf8').trimEnd();
      await page.locator('input[name=username]').fill(username);
      await page.locator('input[name=password]').fill(password);
      await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
    }
  }
  const device = await issueDavCredential(page, base, privateValues, 'Recovery-code reset device', username);
  const grant = await qualifyOAuth(page, base, privateValues, administratorPage,
    {clientId: 'browser-code-recovery-qualification', clientName: 'Browser Recovery Reset', retainGrant: true});
  await page.goto(base + '/security/recovery');
  const [issued] = await Promise.all([page.waitForResponse(response => response.request().method() === 'POST'
    && response.url() === base + '/security/recovery/codes'), page.getByRole('button', {name: 'Generate ten codes', exact: true}).click()]);
  assert.ok((issued.headers()['cache-control'] || '').includes('no-store'));
  await page.getByRole('heading', {name: 'Save your recovery codes', exact: true}).waitFor();
  const codes = await page.locator('ol code').allInnerTexts();
  privateValues.push(...codes);
  assert.equal(codes.length, 10);
  assert.equal(new Set(codes).size, 10);
  assert.ok(codes.every(value => value.length === 80));
  await page.goto(base + '/security/recovery');
  for (const code of codes) assert.ok(!(await page.content()).includes(code), 'Recovery codes must not reappear in settings');
  await signOut(page, base);
  const anonymousContext = await page.context().browser().newContext();
  try {
    const anonymous = await anonymousContext.newPage();
    await anonymous.goto(base + '/login');
    const status = await anonymous.evaluate(async ({target, code}) => {
      const csrf = document.querySelector('meta[name=csrf-token]').content;
      const response = await fetch(target, {method: 'POST', redirect: 'manual',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: new URLSearchParams({_csrf: csrf, code})});
      return response.status;
    }, {target: prefix + '/security/recovery/use', code: codes[0]});
    assert.ok([0, 401, 403].includes(status), 'A recovery code alone must not authenticate an anonymous browser');
  } finally { await anonymousContext.close(); }
  await primary();
  await primaryCannotMutate(page, base);
  await cdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled: false});
  await page.goto(base + '/security/recovery');
  assert.equal(await page.locator('main strong').innerText(), '10', 'Anonymous recovery must not consume a valid code');
  await page.getByLabel('Recovery code', {exact: true}).fill(codes[0]);
  await Promise.all([page.waitForURL(url => url.pathname === prefix + '/login' && url.searchParams.has('recovered')),
    page.getByRole('button', {name: 'Use code and reset account access', exact: true}).click()]);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + device.encoded}})).status, 401);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Bearer ' + grant.accessToken}})).status, 401);
  const refresh = await call(base, '/oauth2/token', {method: 'POST', form: {
    grant_type: 'refresh_token', client_id: grant.clientId, refresh_token: grant.refreshToken
  }});
  assert.equal(refresh.status, 400);
  assert.equal(json(refresh).error, 'invalid_grant');
  await cdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled: true});
  await page.getByRole('button', {name: 'Verify passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: '(401)'}).waitFor();
  await cdp.send('WebAuthn.clearCredentials', {authenticatorId});
  await primary();
  await page.goto(base + '/security/recovery');
  assert.equal(await page.locator('main strong').innerText(), '9');
  await page.getByLabel('Recovery code', {exact: true}).fill(codes[0]);
  const [replay] = await Promise.all([page.waitForResponse(response => response.request().method() === 'POST'
    && response.url() === base + '/security/recovery/use'), page.getByRole('button', {name: 'Use code and reset account access', exact: true}).click()]);
  assert.equal(replay.status(), 403, 'A consumed recovery code must not be reusable');
  await page.goto(base + '/security/passkeys');
  await page.getByRole('button', {name: 'Register passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: 'Passkey registered.'}).waitFor();
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey to complete MFA', exact: true}).click()]);
  console.log('BROWSER_SINGLE_USE_PRIMARY_PLUS_RECOVERY_CODE_RESET_AND_FRESH_MFA_PASSED');
}
