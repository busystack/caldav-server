import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import {call} from './transport.mjs';
import {issueDavCredential} from './dav-credentials.mjs';
import {qualifySharingCapacity} from './groups.mjs';
import {qualifyOAuth} from './oauth.mjs';
import {accountLocation, organizationPrimary, primaryCannotMutate, signOut} from './oidc.mjs';

export async function qualifyRestoration({page, base, privateValues, administratorPage, username,
  collectionName, cdp, authenticatorId, waitFor}) {
  const exchange = process.env.BUSYSTACK_BROWSER_RECOVERY_FILE;
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  const federated = process.env.BUSYSTACK_BROWSER_OIDC === 'true';
  await page.goto(base + '/');
  const collection = await page.locator('li').filter({has: page.getByText(collectionName, {exact: true})}).locator('code').innerText();
  const readerUsername = 'restorereader';
  const writerUsername = 'restorewriter';
  for (const member of [readerUsername, writerUsername]) {
    await administratorPage.goto(base + '/admin');
    const provision = administratorPage.locator(`form[action="${prefix}/admin/accounts"]`);
    const initialPassword = randomBytes(32).toString('base64url');
    privateValues.push(initialPassword);
    await provision.locator('input[name=username]').fill(member);
    await provision.locator('input[name=displayName]').fill('Restore ' + member);
    await provision.locator('input[name=password]').fill(initialPassword);
    await Promise.all([administratorPage.waitForURL(base + '/admin'),
      provision.getByRole('button', {name: 'Create account', exact: true}).click()]);
    await administratorPage.getByText(member, {exact: true}).waitFor();
  }
  await page.goto(base + '/');
  for (const [member, permission] of [[readerUsername, 'READ'], [writerUsername, 'WRITE']]) {
    const share = page.locator(`form[action="${prefix}/shares"]`).filter({has: page.locator('select[name=collection]')});
    await share.locator('select[name=collection]').selectOption(collection);
    await share.locator('input[name=username]').fill(member);
    await share.locator('select[name=permission]').selectOption(permission);
    await Promise.all([page.waitForURL(base + '/'), share.getByRole('button', {name: 'Save access', exact: true}).click()]);
    const row = page.locator('tbody tr').filter({has: page.getByText(member, {exact: true})});
    await row.getByRole('button', {name: 'Remove individual access', exact: true}).waitFor();
  }
  const credential = await issueDavCredential(page, base, privateValues, 'Recovery snapshot device', username);
  const grant = await qualifyOAuth(page, base, privateValues, administratorPage,
    {clientId: 'browser-native-restore-qualification', clientName: 'Browser Native Restore', retainGrant: true});
  const oldCookie = (await page.context().cookies()).find(value => value.name === 'JSESSIONID')?.value;
  if (oldCookie) privateValues.push(oldCookie);
  assert.ok(oldCookie, 'A live authenticated snapshot session is required');
  const initial = {username, collection, readerUsername, writerUsername, credential: credential.credential, encoded: credential.encoded, grant, snapshotCookie: oldCookie};
  writeFileSync(exchange, JSON.stringify(initial), {mode: 0o600});
  if (process.env.BUSYSTACK_BROWSER_CAPACITY === 'true') {
    console.log('READY_CAPACITY_SHARING');
    await waitFor('CAPACITY_SHARING_SEEDED');
    const state = JSON.parse(readFileSync(exchange, 'utf8'));
    state.sharingCapacity = await qualifySharingCapacity(page, administratorPage, base, state.sharingCapacity);
    writeFileSync(exchange, JSON.stringify(state), {mode: 0o600});
    console.log('READY_CAPACITY_SHARING_CLEANUP');
    await waitFor('CAPACITY_SHARING_CLEANED');
  }
  console.log('READY_RESTORE_BACKUP');
  await waitFor('BACKED_UP');
  const backedUp = JSON.parse(readFileSync(exchange, 'utf8'));
  assert.equal(backedUp.snapshotCookie, oldCookie);
  assert.equal(backedUp.grant.clientId, grant.clientId);
  privateValues.push(backedUp.grant.accessToken, backedUp.grant.refreshToken);
  Object.assign(grant, backedUp.grant);
  // A long measured workload must not bypass the normal recent password/organization + passkey requirement.
  await signOut(page, base);
  if (federated) {
    await page.context().clearCookies({domain: /(^|\.)idp\.identity\.test$/});
    await organizationPrimary(page, base, privateValues, true);
  } else {
    const primaryPassword = readFileSync(process.env.BUSYSTACK_BROWSER_PASSWORD_FILE, 'utf8').trimEnd();
    privateValues.push(primaryPassword);
    await page.locator('input[name=username]').fill(username);
    await page.locator('input[name=password]').fill(primaryPassword);
    await Promise.all([page.waitForURL(url => accountLocation(url, base)),
      page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
  }
  await page.goto(base + '/login');
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey', exact: true}).click()]);
  assert.notEqual((await page.context().cookies()).find(value => value.name === 'JSESSIONID')?.value, oldCookie);
  console.log('HTTPS_RECOVERY_RECENT_PRIMARY_AND_WEBAUTHN_REAUTHENTICATION_PASSED');
  await page.goto(base + '/');
  const device = page.locator('li').filter({hasText: 'Recovery snapshot device'});
  await Promise.all([page.waitForURL(base + '/'), device.getByRole('button', {name: 'Revoke', exact: true}).click()]);
  await page.goto(base + '/oauth-grants');
  const authorization = page.locator('tbody tr').filter({hasText: grant.clientId});
  await Promise.all([page.waitForURL(base + '/oauth-grants'), authorization.getByRole('button', {name: 'Revoke access', exact: true}).click()]);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Basic ' + credential.encoded}})).status, 401);
  assert.equal((await call(base, '/dav/missing', {headers: {Authorization: 'Bearer ' + grant.accessToken}})).status, 401);
  console.log('READY_RESTORE_APPLY');
  await waitFor('RESTORED');
  // Replay the exact cookie proven live at backup, separately from the later reauthenticated browser.
  const replayedSession = await call(base, '/', {headers: {Cookie: 'JSESSIONID=' + oldCookie}});
  assert.ok([302, 303, 401, 403].includes(replayedSession.status), 'The snapshot session must not revive after restore');
  console.log('HTTPS_RECOVERY_SNAPSHOT_SESSION_COOKIE_REPLAY_REJECTED');
  // Restored session rows and factors cannot revive a browser which authenticated before backup.
  await page.goto(base + '/');
  await page.waitForURL(url => url.pathname === prefix + '/login');
  assert.notEqual((await page.context().cookies()).find(value => value.name === 'JSESSIONID')?.value, oldCookie);
  await page.getByRole('button', {name: 'Verify passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: '(401)'}).waitFor();
  await cdp.send('WebAuthn.clearCredentials', {authenticatorId});
  await page.goto(base + '/login');
  const oldPasswordFile = federated ? process.env.BUSYSTACK_UPSTREAM_PASSWORD_FILE : process.env.BUSYSTACK_BROWSER_PASSWORD_FILE;
  const oldPassword = readFileSync(oldPasswordFile, 'utf8').trimEnd();
  privateValues.push(oldPassword);
  await page.locator('input[name=username]').fill(username);
  await page.locator('input[name=password]').fill(oldPassword);
  await Promise.all([page.waitForURL(url => url.pathname === prefix + '/login' && url.searchParams.has('error')),
    page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
  if (federated) {
    await page.context().clearCookies({domain: /(^|\.)idp\.identity\.test$/});
    await organizationPrimary(page, base, privateValues, true);
  } else {
    const password = readFileSync(process.env.BUSYSTACK_BROWSER_RECOVERY_PASSWORD_FILE, 'utf8').trimEnd();
    privateValues.push(password);
    await page.locator('input[name=username]').fill(username);
    await page.locator('input[name=password]').fill(password);
    await Promise.all([page.waitForURL(url => accountLocation(url, base)),
      page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
    await page.getByRole('heading', {name: 'BusyStack account', exact: true}).waitFor();
  }
  await primaryCannotMutate(page, base);
  await page.goto(base + '/security/passkeys');
  await page.getByRole('button', {name: 'Register passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: 'Passkey registered.'}).waitFor();
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey to complete MFA', exact: true}).click()]);
  const fresh = await issueDavCredential(page, base, privateValues, 'Recovered device', username);
  await qualifyOAuth(page, base, privateValues, administratorPage,
    {clientId: grant.clientId, clientName: 'Browser Native Restore', existingClient: true});
  // Recover a second principal through its real browser factor enrollment, then enforce its restored READ grant.
  const readerContext = await page.context().browser().newContext();
  const readerPage = await readerContext.newPage();
  const readerCdp = await readerContext.newCDPSession(readerPage);
  await cdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled: false});
  await readerCdp.send('WebAuthn.enable');
  const readerAuthenticator = await readerCdp.send('WebAuthn.addVirtualAuthenticator', {options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
    isUserVerified: true, automaticPresenceSimulation: true}});
  let readerCredential;
  try {
    const readerPassword = readFileSync(process.env.BUSYSTACK_BROWSER_READER_PASSWORD_FILE, 'utf8').trimEnd();
    privateValues.push(readerPassword);
    await readerPage.goto(base + '/login');
    await readerPage.locator('input[name=username]').fill(readerUsername);
    await readerPage.locator('input[name=password]').fill(readerPassword);
    await Promise.all([readerPage.waitForURL(base + '/'), readerPage.getByRole('button', {name: 'Verify password', exact: true}).click()]);
    await primaryCannotMutate(readerPage, base);
    await readerPage.goto(base + '/security/passkeys');
    await readerPage.getByRole('button', {name: 'Register passkey', exact: true}).click();
    await readerPage.locator('#security-status').filter({hasText: 'Passkey registered.'}).waitFor();
    await Promise.all([readerPage.waitForURL(base + '/'), readerPage.getByRole('button', {name: 'Verify passkey to complete MFA', exact: true}).click()]);
    await readerPage.locator('li').getByText(collectionName, {exact: true}).waitFor();
    readerCredential = await issueDavCredential(readerPage, base, privateValues, 'Recovered shared read device', readerUsername);
  } finally {
    await readerCdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId: readerAuthenticator.authenticatorId, enabled: false});
    await readerContext.close();
    await cdp.send('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled: true});
  }
  writeFileSync(exchange, JSON.stringify({...initial, recoveredEncoded: fresh.encoded,
    readerEncoded: readerCredential.encoded}), {mode: 0o600});
  console.log('READY_RESTORE_VERIFY');
  await waitFor('RESTORE_VERIFIED');
  await page.goto(base + '/');
  const restoredShare = page.locator('tbody tr').filter({has: page.getByText(readerUsername, {exact: true})});
  await Promise.all([page.waitForURL(base + '/'), restoredShare.getByRole('button', {name: 'Remove individual access', exact: true}).click()]);
  assert.equal(await page.locator('tbody tr').filter({has: page.getByText(readerUsername, {exact: true})}).count(), 0);
  console.log('READY_RESTORE_REVOKED_READER_VERIFY');
  await waitFor('RESTORE_REVOKED_READER_VERIFIED');
  console.log('HTTPS_RESTORE_SHARED_RECIPIENT_FRESH_MFA_READ_WRITE_DENIAL_AND_REVOCATION_PASSED');
  console.log('HTTPS_RESTORE_OLD_ACCESS_REJECTED_FRESH_MFA_DAV_OAUTH_CONTENT_AND_SYNC_PASSED');
  await page.goto(base + '/');
}
