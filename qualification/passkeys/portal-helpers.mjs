import assert from 'node:assert/strict';
import {mkdirSync} from 'node:fs';
export async function captureWorkflow(page,label) {
  const output=process.env.BUSYSTACK_PORTAL_EVIDENCE;
  if(!output || process.env.BUSYSTACK_GNOME_ONLY==='true') return;
  mkdirSync(output,{recursive:true});
  const initial=page.viewportSize();
  try {
    for(const width of [1280,390]) {
      await page.setViewportSize({width,height:900});
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),label+' must fit the viewport');
      for(const region of await page.locator('.table-scroll').all()) {
        if(await region.evaluate(element=>element.scrollWidth>element.clientWidth+1)) {
          await region.focus();
          await page.keyboard.press('ArrowRight');
          const handle=await region.elementHandle();
          try {await page.waitForFunction(element=>element.scrollLeft>0,handle);}
          finally {await handle.dispose();}
          await page.keyboard.press('Tab');
          assert.ok(await region.evaluate(element=>document.activeElement!==element),'Scrollable tables must not trap keyboard focus');
          await region.evaluate(element=>{element.scrollLeft=0;});
        }
      }
      await page.screenshot({path:output+'/workflow-'+label+'-'+width+'.png',fullPage:true});
    }
  } finally {
    await page.setViewportSize(initial);
  }
}

export function prefixOf(base) {return new URL(base).pathname.replace(/\/$/, '');}
export async function openCollection(page, base, name) {
  await page.goto(base + '/collections?scope=all');
  const link=page.getByRole('link', {name,exact:true});
  const href=await link.getAttribute('href');
  assert.match(href,/\/collections\/[a-f0-9-]{36}$/i);
  await link.click();
  return href.split('/').at(-1);
}
export async function createCollection(page,base,name,component) {
  await page.goto(base+'/collections/new');
  const form=page.locator(`form[action="${prefixOf(base)}/collections"]`);
  await form.locator('input[name=name]').fill(name);
  await form.locator('select[name=component]').selectOption(component);
  await Promise.all([page.waitForURL(/\/collections\/[a-f0-9-]{36}$/i),form.getByRole('button',{name:'Create collection',exact:true}).click()]);
  assert.equal(await page.getByRole('heading',{name,exact:true}).count(),1);
  assert.ok((await page.locator('main').innerText()).includes(component==='VTODO'?'Task list':'Calendar'));
  return new URL(page.url()).pathname.split('/').at(-1);
}
export async function provisionAccount(page,base,username,displayName,password) {
  await page.goto(base+'/admin/accounts/new');
  const form=page.locator(`form[action="${prefixOf(base)}/admin/accounts"]`);
  await form.locator('input[name=username]').fill(username);
  await form.locator('input[name=displayName]').fill(displayName);
  await form.locator('input[name=password]').fill(password);
  await Promise.all([page.waitForURL(/\/admin\/accounts\/[a-f0-9-]{36}$/i),form.getByRole('button',{name:'Create account',exact:true}).click()]);
  await page.getByText(username,{exact:true}).and(page.locator('dd')).waitFor();
  return new URL(page.url()).pathname.split('/').at(-1);
}
export async function grantDirect(page,base,collection,username,permission) {
  await page.goto(base+'/collections/'+collection+'/sharing');
  const form=page.locator(`form[action="${prefixOf(base)}/shares"]`).filter({has:page.locator('input[name=username]:not([type=hidden])')});
  await form.locator('..').locator('summary').click();
  await form.locator('input[name=username]').fill(username);
  await form.locator('select[name=permission]').selectOption(permission);
  await Promise.all([page.waitForURL(base+'/collections/'+collection+'/sharing'),form.getByRole('button',{name:'Grant account access',exact:true}).click()]);
  return page.locator('table').filter({has:page.locator('caption').filter({hasText:'Accounts with direct grants'})}).locator('tbody tr').filter({has:page.getByText(username,{exact:true})});
}
export async function revokeDirect(page,base,collection,username) {
  await page.goto(base+'/collections/'+collection+'/sharing');
  const row=page.locator('table').filter({has:page.locator('caption').filter({hasText:'Accounts with direct grants'})}).locator('tbody tr').filter({has:page.getByText(username,{exact:true})});
  await row.locator('select[name=permission]').selectOption('REVOKE');
  await Promise.all([page.waitForURL(base+'/collections/'+collection+'/sharing'),row.getByRole('button',{name:'Save direct grant',exact:true}).click()]);
}
