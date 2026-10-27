import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';

export async function qualifyGroups(page, base, privateValues) {
  const prefix = new URL(base).pathname.replace(/\/$/, '');
  const member = 'browsergroupmember';
  const password = randomBytes(32).toString('base64url');
  privateValues.push(password);
  await page.goto(base + '/admin');
  const provision = page.locator(`form[action="${prefix}/admin/accounts"]`);
  await provision.locator('input[name=username]').fill(member);
  await provision.locator('input[name=displayName]').fill('Browser Group Member');
  await provision.locator('input[name=password]').fill(password);
  await Promise.all([page.waitForURL(base + '/admin'), provision.getByRole('button', {name: 'Create account', exact: true}).click()]);
  await page.getByText(member, {exact: true}).waitFor();
  await page.getByRole('link', {name: 'Sharing groups', exact: true}).click();
  await page.getByRole('heading', {name: 'Sharing groups', exact: true}).waitFor();
  const create = page.locator(`form[action="${prefix}/admin/groups"]`);
  await create.locator('input[name=name]').fill('Browser sharing group');
  await create.locator('textarea[name=usernames]').fill(member);
  await Promise.all([page.waitForURL(base + '/admin/groups'), create.getByRole('button', {name: 'Create group', exact: true}).click()]);
  let card = page.locator('article').filter({has: page.getByRole('heading', {name: 'Browser sharing group', exact: true})});
  assert.equal(await card.locator('textarea[name=usernames]').inputValue(), member);
  const group = await card.locator('code').innerText();
  assert.match(group, /^[a-f0-9-]{36}$/i);
  const edit = card.locator(`form[action="${prefix}/admin/groups/${group}"]`);
  await edit.locator('input[name=name]').fill('Browser group renamed');
  await edit.locator('textarea[name=usernames]').fill('');
  await Promise.all([page.waitForURL(base + '/admin/groups'), edit.getByRole('button', {name: 'Save group', exact: true}).click()]);
  card = page.locator('article').filter({has: page.getByRole('heading', {name: 'Browser group renamed', exact: true})});
  assert.equal(await card.locator('textarea[name=usernames]').inputValue(), '');
  await card.locator('textarea[name=usernames]').fill(member);
  await Promise.all([page.waitForURL(base + '/admin/groups'), card.getByRole('button', {name: 'Save group', exact: true}).click()]);
  await page.goto(base + '/');
  const sharing = page.locator(`form[action="${prefix}/group-shares"]`).filter({has: page.locator('select[name=group]')});
  await sharing.locator('select[name=collection]').selectOption({label: 'Browser-qualified tasks'});
  await sharing.locator('select[name=group]').selectOption(group);
  for (const permission of ['READ', 'WRITE']) {
    await sharing.locator('select[name=permission]').selectOption(permission);
    await Promise.all([page.waitForURL(base + '/'), sharing.getByRole('button', {name: 'Save group access', exact: true}).click()]);
    const grant = page.locator('tbody tr').filter({hasText: 'Browser group renamed'});
    assert.ok((await grant.innerText()).includes(permission));
  }
  let grant = page.locator('tbody tr').filter({hasText: 'Browser group renamed'});
  await Promise.all([page.waitForURL(base + '/'), grant.getByRole('button', {name: 'Remove group access', exact: true}).click()]);
  assert.equal(await page.locator('tbody tr').filter({hasText: 'Browser group renamed'}).count(), 0);
  await page.goto(base + '/admin/groups');
  card = page.locator('article').filter({has: page.getByRole('heading', {name: 'Browser group renamed', exact: true})});
  await Promise.all([page.waitForURL(base + '/admin/groups'), card.getByRole('button', {name: 'Delete group', exact: true}).click()]);
  assert.equal(await page.getByRole('heading', {name: 'Browser group renamed', exact: true}).count(), 0);
  await page.goto(base + '/');
  assert.equal(await page.locator(`option[value="${group}"]`).count(), 0);
  console.log('BROWSER_GROUP_MEMBERSHIP_UPDATE_DELETE_AND_OWNER_GRANTS_PASSED');
}
