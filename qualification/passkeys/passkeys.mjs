import {qualifyImportProcessInterruption} from './portability-crash.mjs';
import {qualifyPersonalPortal,qualifyPortalPresentation} from './portal.mjs';
import {createCollection, openCollection, captureWorkflow} from './portal-helpers.mjs';
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { createInterface } from 'node:readline';
import { readFileSync, writeFileSync } from 'node:fs';
import { qualifyOAuth } from './oauth.mjs';
import { qualifyDavCredentials } from './dav-credentials.mjs';
import {qualifyAccountRecovery} from './account-recovery.mjs';
import {qualifyGroups} from './groups.mjs';
import {qualifyRestoration} from './recovery.mjs';
import { newFederatedUser, organizationPrimary, primaryCannotMutate, signOut } from './oidc.mjs';

const base = process.env.BUSYSTACK_BROWSER_URL;
assert.ok(base && new URL(base).protocol === 'https:' && ['localhost', 'app.busystack.test'].includes(new URL(base).hostname), 'Browser fixture must use isolated test-site HTTPS');
const password = readFileSync(process.env.BUSYSTACK_BROWSER_PASSWORD_FILE, 'utf8').trimEnd();
const privateValues = [password];
const prefix = new URL(base).pathname.replace(/\/$/, '');
const input = createInterface({input: process.stdin});
const lines = input[Symbol.asyncIterator]();
async function waitFor(expected) {
  const result = await lines.next();
  assert.ok(!result.done && result.value === expected, `Expected harness signal ${expected}`);
}
const browser = await chromium.launch({headless: true});
console.log('PLAYWRIGHT_VERSION=1.61.1');
console.log('CHROMIUM_VERSION=' + browser.version());
try {
  let context = await browser.newContext();
  let page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const localAuthenticator = await cdp.send('WebAuthn.addVirtualAuthenticator', {options: {protocol: 'ctap2', transport: 'internal',
    hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true}});
  await page.goto(base + '/login');
  await captureWorkflow(page,'sign-in');
  assert.equal(await page.locator('meta[name=csrf-token]').count(), 1);
  await page.locator('input[name=username]').fill(process.env.BUSYSTACK_BROWSER_USERNAME);
  await page.locator('input[name=password]').fill(password);
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
  const passwordSession = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID')?.value;
  assert.ok(passwordSession);
  privateValues.push(passwordSession);
  const passwordOnly = await page.evaluate(async path => {
    const csrf = document.querySelector('input[name=_csrf]').value;
    const response = await fetch(path, {method: 'POST', redirect: 'manual',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: new URLSearchParams({_csrf: csrf, name: 'must fail', component: 'VTODO'})});
    return response.status;
  }, prefix + '/collections');
  assert.equal(passwordOnly, 403, 'Password alone must not authorize account mutations');
  await page.goto(base + '/security/passkeys');
  await page.getByRole('button', {name: 'Register passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: 'Passkey registered.'}).waitFor();
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey to complete MFA', exact: true}).click()]);
  const mfaSession = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID')?.value;
  if (mfaSession) privateValues.push(mfaSession);
  assert.ok(mfaSession && mfaSession !== passwordSession, 'Passkey authentication must rotate session ID');
  await createCollection(page,base,'Browser-qualified tasks','VTODO');
  console.log('PASSWORD_PLUS_WEBAUTHN_AND_MUTATION_PASSED');
  const localPortal=await qualifyPersonalPortal(page,base,privateValues,process.env.BUSYSTACK_BROWSER_USERNAME);
  if (process.env.BUSYSTACK_GNOME_ONLY !== 'true') await qualifyPortalPresentation(page,base,localPortal,true);
  if (process.env.BUSYSTACK_GNOME_EXCHANGE) {
    writeFileSync(process.env.BUSYSTACK_GNOME_EXCHANGE, JSON.stringify({address:localPortal.address,connectionAddress:localPortal.gnomeAddress,
      name:localPortal.name, username:process.env.BUSYSTACK_BROWSER_USERNAME, credential:localPortal.device.credential}));
    console.log('READY_GNOME');
    await waitFor('GNOME_VERIFIED');
  }
  if (process.env.BUSYSTACK_GNOME_ONLY === 'true') {
    console.log('NATIVE_CONNECTION_SETUP_ONLY_PASSED');
  } else {
  if (process.env.BUSYSTACK_PORTABILITY_CRASH === 'true') await qualifyImportProcessInterruption(page,base,waitFor);
  if (process.env.BUSYSTACK_BROWSER_GROUPS === 'true') await qualifyGroups(page, base, privateValues);
  const administratorPage = page;
  let activeCdp = cdp;
  let activeAuthenticatorId = localAuthenticator.authenticatorId;
  let currentUsername = process.env.BUSYSTACK_BROWSER_USERNAME;
  let collectionName = 'Browser-qualified tasks';
  if (process.env.BUSYSTACK_BROWSER_OIDC === 'true') {
    await cdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId: localAuthenticator.authenticatorId, enabled: false});
    const federated = await newFederatedUser(browser, base, privateValues);
    context = federated.context;
    page = federated.page;
    activeCdp = federated.cdp;
    activeAuthenticatorId = federated.authenticatorId;
    currentUsername = process.env.BUSYSTACK_FEDERATED_USERNAME;
    collectionName = 'Federated browser-qualified tasks';
    const externalPortal=await qualifyPersonalPortal(page,base,privateValues,currentUsername,'external');
    await qualifyPortalPresentation(page,base,externalPortal,false,'external');
  }
  await qualifyAccountRecovery({page, base, privateValues, administratorPage, username: currentUsername,
    cdp: activeCdp, authenticatorId: activeAuthenticatorId});
  await qualifyDavCredentials(page, base, privateValues, currentUsername);
  await qualifyOAuth(page, base, privateValues, administratorPage);
  if (process.env.BUSYSTACK_BROWSER_RESTORE === 'true') {
    await qualifyRestoration({page, base, privateValues, administratorPage, username: currentUsername,
      collectionName, cdp: activeCdp, authenticatorId: activeAuthenticatorId, waitFor});
  }
  const persistentCookie = (await context.cookies()).find(value => value.name === 'JSESSIONID')?.value;
  if (persistentCookie) privateValues.push(persistentCookie);
  console.log('READY_RESTART');
  await waitFor('RESTARTED');
  await page.goto(base + '/');
  await page.getByRole('heading', {name: /^Welcome,/}).waitFor();
  assert.equal((await context.cookies()).find(value => value.name === 'JSESSIONID')?.value, persistentCookie,
    'A persisted browser session must survive the application restart');
  console.log('PERSISTED_BROWSER_SESSION_AFTER_RESTART_PASSED');
  if (process.env.BUSYSTACK_BROWSER_OIDC === 'true') {
    await signOut(page, base);
    await organizationPrimary(page, base, privateValues);
    await primaryCannotMutate(page, base);
  } else {
  await page.goto(base + '/login');
  await page.locator('input[name=username]').fill(process.env.BUSYSTACK_BROWSER_USERNAME);
  const currentPassword = process.env.BUSYSTACK_BROWSER_RESTORE === 'true' ? readFileSync(process.env.BUSYSTACK_BROWSER_RECOVERY_PASSWORD_FILE, 'utf8').trimEnd() : password;
  privateValues.push(currentPassword);
  await page.locator('input[name=password]').fill(currentPassword);
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
  }
  await page.goto(base + '/login');
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey', exact: true}).click()]);
  await openCollection(page,base,collectionName);
  console.log('PERSISTED_PASSKEY_AFTER_RESTART_PASSED');
  console.log('READY_DISABLE');
  await waitFor('DISABLED');
  const disabled = await page.goto(base + '/');
  assert.equal(disabled.status(), 403);
  // Existing sessions and independent passkey authentication must both reject disabled users.
  await page.goto(base + '/login');
  await page.getByRole('button', {name: 'Verify passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: '(401)'}).waitFor();
  console.log('DISABLED_SESSION_AND_PASSKEY_PASSED');
  }
} catch (error) {
  let diagnostic = String(error.stack || error);
  for (const value of privateValues) diagnostic = diagnostic.replaceAll(value, '[redacted]');
  diagnostic = diagnostic.replace(/([?&](?:code|state|code_challenge)=)[^&\s)"]+/g, '$1[redacted]');
  console.error(diagnostic);
  process.exitCode = 1;
} finally {
  input.close();
  await browser.close();
}
