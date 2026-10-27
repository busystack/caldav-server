import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {randomBytes, createHash} from 'node:crypto';
import {call, json} from './transport.mjs';

export async function qualifyOAuth(page, base, privateValues, administratorPage = page) {
  const clientId = 'browser-native-qualification';
  const clientName = 'Browser Native Calendar';
  const state = randomBytes(32).toString('base64url');
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  privateValues.push(state, verifier);
  let resolveCode;
  const codeReceived = new Promise(resolve => { resolveCode = resolve; });
  const callback = createServer((incoming, outgoing) => {
    const url = new URL(incoming.url, 'http://127.0.0.1');
    const code = url.searchParams.get('code');
    if (incoming.method !== 'GET' || url.pathname !== '/callback' || url.searchParams.get('state') !== state || !code) {
      outgoing.writeHead(400, {'Content-Type': 'text/plain'});
      outgoing.end('Invalid authorization callback.');
      return;
    }
    privateValues.push(code);
    outgoing.writeHead(200, {'Content-Type': 'text/plain', 'Cache-Control': 'no-store'});
    outgoing.end('Authorization completed. Return to your account.');
    resolveCode(code);
  });
  await new Promise((resolve, reject) => { callback.once('error', reject); callback.listen(0, '127.0.0.1', resolve); });
  let deadline;
  try {
    const redirect = `http://127.0.0.1:${callback.address().port}/callback`;
    await administratorPage.goto(base + '/admin/oauth-clients');
    await administratorPage.getByLabel('Client ID', {exact: true}).fill(clientId);
    await administratorPage.getByLabel('Name', {exact: true}).fill(clientName);
    await administratorPage.getByLabel('Redirect URIs, one per line', {exact: true}).fill(redirect);
    await Promise.all([administratorPage.waitForURL(base + '/admin/oauth-clients'), administratorPage.getByRole('button', {name: 'Register application', exact: true}).click()]);
    await administratorPage.getByRole('cell', {name: clientId, exact: true}).waitFor();
    const parameters = new URLSearchParams({response_type: 'code', client_id: clientId, redirect_uri: redirect,
      scope: 'dav', state, code_challenge: challenge, code_challenge_method: 'S256'});
    const authorizationPage = await page.goto(base + '/oauth2/authorize?' + parameters);
    try {
      await page.getByRole('heading', {name: 'Authorize application', exact: true}).waitFor();
    } catch {
      const headings = await page.locator('h1').allInnerTexts();
      throw new Error(`Consent page unavailable: status=${authorizationPage.status()}, path=${new URL(page.url()).pathname}, headings=${JSON.stringify(headings)}`);
    }
    await page.getByRole('checkbox', {name: 'Calendar and task access', exact: true}).check();
    const timeout = new Promise((_, reject) => { deadline = setTimeout(() => reject(new Error('Authorization callback timed out')), 30000); });
    await page.getByRole('button', {name: 'Authorize application', exact: true}).click();
    const code = await Promise.race([codeReceived, timeout]);
    clearTimeout(deadline);
    const exchange = await call(base, '/oauth2/token', {method: 'POST', form: {
      grant_type: 'authorization_code', client_id: clientId, redirect_uri: redirect, code, code_verifier: verifier
    }});
    assert.equal(exchange.status, 200, 'Public S256 authorization-code exchange must succeed without a client secret');
    const initial = json(exchange);
    for (const key of ['access_token', 'refresh_token']) {
      assert.ok(typeof initial[key] === 'string' && initial[key].length > 40, 'Expected an opaque client credential');
      privateValues.push(initial[key]);
    }
    assert.equal(initial.token_type.toLowerCase(), 'bearer');
    if (initial.scope !== undefined) assert.ok(initial.scope.split(' ').includes('dav'));
    assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Bearer ' + initial.access_token}})).status, 404,
      'A valid DAV bearer must reach the application rather than an authentication challenge');
    const refreshed = await call(base, '/oauth2/token', {method: 'POST', form: {
      grant_type: 'refresh_token', client_id: clientId, refresh_token: initial.refresh_token
    }});
    assert.equal(refreshed.status, 200, 'Public native refresh must succeed');
    const current = json(refreshed);
    for (const key of ['access_token', 'refresh_token']) {
      assert.ok(typeof current[key] === 'string' && current[key].length > 40, 'Expected a rotated opaque credential');
      privateValues.push(current[key]);
      assert.notEqual(current[key], initial[key]);
    }
    assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Bearer ' + current.access_token}})).status, 404);
    await page.goto(base + '/oauth-grants');
    const grant = page.locator('tbody tr').filter({hasText: clientId});
    await Promise.all([page.waitForURL(base + '/oauth-grants'), grant.getByRole('button', {name: 'Revoke access', exact: true}).click()]);
    assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Bearer ' + current.access_token}})).status, 401);
    const revokedRefresh = await call(base, '/oauth2/token', {method: 'POST', form: {
      grant_type: 'refresh_token', client_id: clientId, refresh_token: current.refresh_token
    }});
    assert.equal(revokedRefresh.status, 400);
    assert.equal(json(revokedRefresh).error, 'invalid_grant');
    await page.goto(base + '/');
    console.log('BROWSER_S256_CONSENT_TOKEN_REFRESH_AND_GRANT_REVOCATION_PASSED');
  } finally {
    clearTimeout(deadline);
    callback.closeAllConnections();
    await new Promise(resolve => callback.close(resolve));
  }
}
