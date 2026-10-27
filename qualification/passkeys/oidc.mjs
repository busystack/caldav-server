import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

export async function organizationPrimary(page, base, privateValues, requirePasswordForm = false) {
  const password = readFileSync(process.env.BUSYSTACK_UPSTREAM_PASSWORD_FILE, 'utf8').trimEnd();
  privateValues.push(password);
  await page.goto(base + '/login');
  await page.getByRole('link', {name: "Sign in with your organization's identity provider", exact: true}).click();
  const dashboard = page.getByRole('heading', {name: 'BusyStack account', exact: true});
  const destination = await Promise.race([
    page.locator('#kc-login').waitFor().then(() => 'provider-password'),
    dashboard.waitFor().then(() => 'application')
  ]);
  if (requirePasswordForm) assert.equal(destination, 'provider-password', 'The first organization login must exercise real upstream password authentication');
  if (destination === 'provider-password') {
    assert.equal(new URL(page.url()).hostname, 'idp.identity.test');
    const username = page.locator('#username');
    if (await username.count()) {
      if (await username.isEditable()) await username.fill(process.env.BUSYSTACK_UPSTREAM_USERNAME);
      else assert.equal(await username.inputValue(), process.env.BUSYSTACK_UPSTREAM_USERNAME);
    }
    await page.locator('#password').fill(password);
    await Promise.all([page.waitForURL(url => url.origin === new URL(base).origin), page.locator('#kc-login').click()]);
  }
  await dashboard.waitFor();
  await page.getByText(process.env.BUSYSTACK_FEDERATED_USERNAME, {exact: true}).waitFor();
  const cookie = (await page.context().cookies()).find(value => value.name === 'JSESSIONID');
  assert.ok(cookie && cookie.secure && cookie.httpOnly && cookie.sameSite === 'Lax', 'Cross-site OIDC callback must retain a protected Lax session');
}

export async function signOut(page, base) {
  await page.goto(base + '/');
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  await Promise.all([page.waitForURL(url => url.pathname === prefix + '/login'),
    page.locator(`form[action="${prefix}/logout"]`).getByRole('button', {name: 'Sign out', exact: true}).click()]);
}

export async function primaryCannotMutate(page, base) {
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  const status = await page.evaluate(async target => {
    const csrf = document.querySelector('input[name=_csrf]').value;
    return (await fetch(target, {method: 'POST', redirect: 'manual', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: new URLSearchParams({_csrf: csrf, name: 'must fail', component: 'VTODO'})})).status;
  }, prefix + '/collections');
  assert.equal(status, 403, 'Organization login alone must not authorize a protected mutation');
}

export async function newFederatedUser(browser, base, privateValues) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', {options: {protocol: 'ctap2', transport: 'internal',
    hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true}});
  await organizationPrimary(page, base, privateValues, true);
  await primaryCannotMutate(page, base);
  await page.goto(base + '/security/passkeys');
  await page.getByRole('button', {name: 'Register passkey', exact: true}).click();
  await page.locator('#security-status').filter({hasText: 'Passkey registered.'}).waitFor();
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey to complete MFA', exact: true}).click()]);
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  const form = page.locator(`form[action="${prefix}/collections"]`);
  await form.locator('input[name=name]').fill('Federated browser-qualified tasks');
  await form.locator('select[name=component]').selectOption('VTODO');
  await Promise.all([page.waitForURL(base + '/'), form.getByRole('button', {name: 'Create', exact: true}).click()]);
  const collection = page.locator('li').filter({has: page.getByText('Federated browser-qualified tasks', {exact: true})});
  assert.ok((await collection.innerText()).includes('VTODO'));
  assert.equal(await page.getByText('Browser-qualified tasks', {exact: true}).count(), 0, 'An organization user must not discover the local administrator collection');
  const forbidden = await page.goto(base + '/admin');
  assert.equal(forbidden.status(), 403, 'Upstream realm roles must not grant application administration');
  await page.goto(base + '/');
  await signOut(page, base);
  await page.locator('input[name=username]').fill(process.env.BUSYSTACK_FEDERATED_USERNAME);
  const upstreamPassword = readFileSync(process.env.BUSYSTACK_UPSTREAM_PASSWORD_FILE, 'utf8').trimEnd();
  await page.locator('input[name=password]').fill(upstreamPassword);
  await Promise.all([page.waitForURL(url => url.pathname === prefix + '/login' && url.searchParams.has('error')),
    page.getByRole('button', {name: 'Verify password', exact: true}).click()]);
  await organizationPrimary(page, base, privateValues);
  await primaryCannotMutate(page, base);
  await page.goto(base + '/login');
  await Promise.all([page.waitForURL(base + '/'), page.getByRole('button', {name: 'Verify passkey', exact: true}).click()]);
  console.log('FEDERATED_LOCAL_PASSWORD_FALLBACK_DENIED');
  console.log('CROSS_SITE_ORGANIZATION_PLUS_PASSKEY_AND_NONADMIN_PROVISIONING_PASSED');
  return {context, page};
}
