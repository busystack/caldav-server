import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
export async function captureWorkflow(page,label,mask=[]) {
  const output=process.env.BUSYSTACK_PORTAL_EVIDENCE;
  if(!output || process.env.BUSYSTACK_GNOME_ONLY==='true') return;
  mkdirSync(output,{recursive:true});
  const initial=page.viewportSize();
  try {
    for(const width of [1280,390]) {
      await page.setViewportSize({width,height:900});
      const fits=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1);
      if(!fits) {
        await page.screenshot({path:output+'/workflow-'+label+'-'+width+'-overflow.png',fullPage:true,mask});
        writeFileSync(output+'/workflow-'+label+'-'+width+'-overflow.json',JSON.stringify(await page.evaluate(()=>({
          viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,
          elements:[...document.querySelectorAll('body *')].filter(element=>element.getBoundingClientRect().right>innerWidth+1 && (!element.closest('.table-scroll') || getComputedStyle(element).position==='absolute'))
            .slice(0,40).map(element=>({tag:element.tagName,classes:[...element.classList],
              left:element.getBoundingClientRect().left,right:element.getBoundingClientRect().right,
              width:element.getBoundingClientRect().width,minWidth:getComputedStyle(element).minWidth,
              position:getComputedStyle(element).position,containingBlock:element.offsetParent?.tagName}))
        })),null,2));
      }
      assert.ok(fits,label+' must fit the viewport');
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
      await page.screenshot({path:output+'/workflow-'+label+'-'+width+'.png',fullPage:true,mask});
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

export async function expectFieldError(page,form,field,status,label) {
  await form.evaluate(element=>{element.noValidate=true;});
  const [response]=await Promise.all([page.waitForNavigation(),form.locator('button').first().click()]);
  assert.equal(response.status(),status,label);
  const summary=page.locator('[data-error-summary]');
  await summary.waitFor();
  await page.waitForFunction(()=>document.activeElement===document.querySelector('[data-error-summary]'));
  const control=page.locator('[aria-invalid="true"]').filter({hasNot:page.locator('[type=hidden]')});
  const target=page.locator(`[name="${field}"][aria-invalid="true"]`);
  assert.equal(await target.count(),1,label+' has one invalid editable field');
  const id=await target.getAttribute('id'),description=await target.getAttribute('aria-describedby');
  assert.ok(id && description);
  assert.equal(await target.evaluate(element=>element.labels.length>0),true);
  assert.ok((await page.locator('[id="'+description+'"]').innerText()).trim());
  await summary.locator(`a[href="#${id}"]`).click();
  assert.equal(await target.evaluate(element=>document.activeElement===element),true,'Error link moves keyboard focus to its field');
  for(const password of await page.locator('input[type=password]').all()) assert.equal(await password.inputValue(),'','Passwords must never be redisplayed');
  assert.ok(await control.count()>0);
  await captureWorkflow(page,'form-error-'+label);
}
