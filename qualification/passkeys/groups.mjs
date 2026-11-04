import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {openCollection,provisionAccount,prefixOf,grantDirect,revokeDirect,captureWorkflow,expectFieldError} from './portal-helpers.mjs';

export async function qualifyGroups(page,base,privateValues) {
  const prefix=prefixOf(base),member='browsergroupmember',password=randomBytes(32).toString('base64url');
  privateValues.push(password);
  await page.goto(base+'/admin/accounts/new');
  const accountForm=page.locator(`form[action="${prefix}/admin/accounts"]`);
  await accountForm.locator('[name=username]').fill('invalid username'); await accountForm.locator('[name=displayName]').fill('Retain account label'); await accountForm.locator('[name=password]').fill(password);
  await expectFieldError(page,accountForm,'username',400,'account-create');
  assert.equal(await accountForm.locator('[name=displayName]').inputValue(),'Retain account label');
  const account=await provisionAccount(page,base,member,'Browser Group Member',password);
  const display='Group member <img src=x onerror=alert(1)>';
  const profile=page.locator(`form[action="${prefix}/admin/accounts/${account}/edit"]`);
  await profile.locator('input[name=displayName]').fill('');
  await expectFieldError(page,profile,'displayName',400,'account-edit');
  await profile.locator('input[name=displayName]').fill(display);
  await Promise.all([page.waitForURL(base+'/admin/accounts/'+account),profile.getByRole('button',{name:'Save display name',exact:true}).click()]);
  assert.equal(await page.getByRole('heading',{name:display,exact:true}).count(),1);
  assert.equal(await page.locator('img[src=x]').count(),0);
  await captureWorkflow(page,'account-detail');
  await page.goto(base+'/admin/accounts?q='+encodeURIComponent(member)+'&status=enabled');
  assert.equal(await page.locator('tbody tr').count(),1);
  assert.equal(await page.getByRole('link',{name:member,exact:true}).count(),1);
  await page.goto(base+'/admin/groups');
  const create=page.locator(`form[method="post"][action="${prefix}/admin/groups"]`);
  await create.locator('input[name=name]').fill('');
  await expectFieldError(page,create,'name',400,'group-create');
  await create.locator('input[name=name]').fill('Browser sharing group');
  await Promise.all([page.waitForURL(/\/admin\/groups\/[a-f0-9-]{36}$/i),create.getByRole('button',{name:'Create group',exact:true}).click()]);
  const group=new URL(page.url()).pathname.split('/').at(-1);
  const add=page.locator(`form[action="${prefix}/admin/groups/${group}/members"]`);
  await add.locator('input[name=username]').fill('missing-account');await add.locator('input[name=confirmed]').check();
  await expectFieldError(page,add,'username',400,'member-add');
  await add.locator('input[name=username]').fill(member);await add.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(base+'/admin/groups/'+group),add.getByRole('button',{name:'Add member',exact:true}).click()]);
  const row=page.locator('tbody tr').filter({has:page.getByText(member,{exact:true})});
  await row.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(base+'/admin/groups/'+group),row.getByRole('button',{name:'Remove member',exact:true}).click()]);
  assert.equal(await row.count(),0);
  await add.locator('input[name=username]').fill(member);await add.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(base+'/admin/groups/'+group),add.getByRole('button',{name:'Add member',exact:true}).click()]);
  const rename=page.locator(`form[action="${prefix}/admin/groups/${group}"]`);
  await rename.locator('input[name=name]').fill('');
  await expectFieldError(page,rename,'name',400,'group-rename');
  await rename.locator('input[name=name]').fill('Browser group renamed');
  await Promise.all([page.waitForURL(base+'/admin/groups/'+group),rename.getByRole('button',{name:'Save group name',exact:true}).click()]);
  await page.getByRole('heading',{name:'Browser group renamed',exact:true}).waitFor();
  await captureWorkflow(page,'group-detail');
  const collection=await openCollection(page,base,'Browser-qualified tasks');
  await page.goto(base+'/collections/'+collection+'/sharing');
  const direct=page.locator(`form[action="${prefix}/shares"]`).filter({has:page.locator('input[name=username]:not([type=hidden])')});
  await direct.locator('..').locator('summary').click();
  await direct.locator('[name=username]').fill('missing-account');
  await direct.locator('[name=permission]').selectOption('WRITE');
  await expectFieldError(page,direct,'username',400,'direct-sharing');
  assert.equal(await direct.locator('[name=username]').inputValue(),'missing-account');
  assert.equal(await direct.locator('[name=permission]').inputValue(),'WRITE');
  const sharing=page.locator(`form[action="${prefix}/group-shares"]`).filter({has:page.locator('select[name=group]')});
  await sharing.locator('..').locator('summary').click();
  await sharing.locator('select[name=group]').selectOption(group);
  await sharing.locator('select[name=permission]').selectOption('READ');
  await Promise.all([page.waitForURL(base+'/collections/'+collection+'/sharing'),sharing.getByRole('button',{name:'Grant group access',exact:true}).click()]);
  const grant=page.locator('table').filter({has:page.locator('caption').filter({hasText:'Groups with grants for this collection'})}).locator('tbody tr').filter({hasText:'Browser group renamed'});
  for(const permission of ['WRITE','REVOKE']) {
    await grant.locator('select[name=permission]').selectOption(permission);
    await Promise.all([page.waitForURL(base+'/collections/'+collection+'/sharing'),grant.getByRole('button',{name:'Save group grant',exact:true}).click()]);
    if(permission==='WRITE') {
      await grantDirect(page,base,collection,member,'READ');
      const effective=page.locator('table').filter({has:page.locator('caption').filter({hasText:'Combined direct and group access'})}).locator('tbody tr').filter({has:page.getByText(member,{exact:true})});
      assert.ok((await effective.innerText()).includes('Read and write'));
      await captureWorkflow(page,'effective-direct-group-access');
      await revokeDirect(page,base,collection,member);
      assert.ok((await effective.innerText()).includes('Read and write'),'Removing direct access must preserve the effective group write grant');
    }
  }
  assert.equal(await grant.count(),0);
  await page.goto(base+'/admin/groups/'+group);
  const remove=page.locator(`form[action="${prefix}/admin/groups/${group}/delete"]`);
  await remove.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(base+'/admin/groups'),remove.getByRole('button',{name:'Delete group',exact:true}).click()]);
  assert.equal(await page.getByRole('link',{name:'Browser group renamed',exact:true}).count(),0);
  await page.goto(base+'/collections/'+collection+'/sharing');
  assert.equal(await page.locator(`option[value="${group}"]`).count(),0);
  await page.goto(base+'/admin/accounts/'+account);
  const disable=page.locator(`form[action="${prefix}/admin/accounts/${account}/disable"]`);
  await disable.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(base+'/admin/accounts/'+account),disable.getByRole('button',{name:'Disable account',exact:true}).click()]);
  assert.equal(await page.getByText('Disabled',{exact:true}).count(),1);
  await page.goto(base+'/admin/accounts?q='+encodeURIComponent(member)+'&status=disabled');
  assert.equal(await page.locator('tbody tr').count(),1);
  const disabledContext=await page.context().browser().newContext();
  try {
    const disabledPage=await disabledContext.newPage();
    await disabledPage.goto(base+'/login');
    await disabledPage.locator('input[name=username]').fill(member);
    await disabledPage.locator('input[name=password]').fill(password);
    await Promise.all([disabledPage.waitForURL(base+'/login?error'),disabledPage.getByRole('button',{name:'Verify password',exact:true}).click()]);
  } finally {await disabledContext.close();}
  console.log('BROWSER_ACCOUNT_PROFILE_SEARCH_DISABLE_AND_EFFECTIVE_DIRECT_GROUP_ACCESS_PASSED');
  console.log('BROWSER_GROUP_MEMBERSHIP_UPDATE_DELETE_AND_OWNER_GRANTS_PASSED');
}

export async function qualifySharingCapacity(page,administratorPage,base,fixture) {
  const prefix=prefixOf(base);
  assert.equal(fixture.collections.length,25);
  let firstPageRows=0,lastPageRows=0;
  for(const id of fixture.collections) {
    await page.goto(base+'/collections/'+id+'/sharing');
    const rows=page.locator('table').filter({has:page.locator('caption').filter({hasText:'Groups with grants for this collection'})}).locator('tbody tr');
    assert.equal(await rows.count(),50);firstPageRows+=50;
    await page.goto(base+'/collections/'+id+'/sharing?groupPage=9');
    assert.equal(await rows.count(),50);lastPageRows+=50;
    const nav=page.getByRole('navigation',{name:'Group grant pages'});
    assert.equal(await nav.getByRole('link',{name:'Next',exact:true}).count(),0);
  }
  const id=fixture.collections[0];
  await page.goto(base+'/collections/'+id+'/sharing');
  const rows=page.locator('table').filter({has:page.locator('caption').filter({hasText:'Groups with grants for this collection'})}).locator('tbody tr');
  const remove=rows.first().locator('form');
  const removedGroup=await remove.locator('input[name=group]').inputValue();
  await remove.locator('select[name=permission]').selectOption('REVOKE');
  await Promise.all([page.waitForURL(base+'/collections/'+id+'/sharing'),remove.getByRole('button',{name:'Save group grant',exact:true}).click()]);
  assert.equal(await rows.count(),50);
  await administratorPage.goto(base+'/admin/groups/'+fixture.firstGroup);
  const rename=administratorPage.locator(`form[action="${prefix}/admin/groups/${fixture.firstGroup}"]`);
  await rename.locator('input[name=name]').fill('Capacity group updated');
  await Promise.all([administratorPage.waitForURL(base+'/admin/groups/'+fixture.firstGroup),rename.getByRole('button',{name:'Save group name',exact:true}).click()]);
  const member=administratorPage.locator('tbody tr').filter({has:administratorPage.getByText('restorereader',{exact:true})});
  await member.locator('input[name=confirmed]').check();
  await Promise.all([administratorPage.waitForURL(base+'/admin/groups/'+fixture.firstGroup),member.getByRole('button',{name:'Remove member',exact:true}).click()]);
  const add=administratorPage.locator(`form[action="${prefix}/admin/groups/${fixture.firstGroup}/members"]`);
  await add.locator('input[name=username]').fill('restorewriter');await add.locator('input[name=confirmed]').check();
  await Promise.all([administratorPage.waitForURL(base+'/admin/groups/'+fixture.firstGroup),add.getByRole('button',{name:'Add member',exact:true}).click()]);
  const members=administratorPage.locator('tbody tr');
  assert.equal(await members.count(),1);assert.ok((await members.innerText()).includes('restorewriter'));
  const deletion=administratorPage.locator(`form[action="${prefix}/admin/groups/${fixture.firstGroup}/delete"]`);
  await deletion.locator('input[name=confirmed]').check();
  await Promise.all([administratorPage.waitForURL(base+'/admin/groups'),deletion.getByRole('button',{name:'Delete group',exact:true}).click()]);
  console.log('BROWSER_12500_GRANTS_PAGINATION_REVOCATION_MEMBERSHIP_AND_GROUP_DELETE_PASSED');
  return {...fixture,browserPassed:true,firstPageRows,lastPage:9,lastPageRows,removedGroup};
}
