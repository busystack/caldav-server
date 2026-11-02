import assert from 'node:assert/strict';
import {readFileSync, mkdirSync} from 'node:fs';
import {createCollection,openCollection,provisionAccount,grantDirect,revokeDirect,prefixOf} from './portal-helpers.mjs';
import {issueDavCredential} from './dav-credentials.mjs';
import {call} from './transport.mjs';

const series='BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack portal qualification//EN\r\nBEGIN:VTIMEZONE\r\nTZID:Fixture/Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:portal-series\r\nDTSTAMP:20261101T000000Z\r\nDTSTART;TZID=Fixture/Fixed:20261102T090000\r\nDURATION:P1D\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:Family time\r\nX-PRIVATE:preserve\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:portal-series\r\nDTSTAMP:20261101T000000Z\r\nRECURRENCE-ID;TZID=Fixture/Fixed:20261103T090000\r\nDTSTART;TZID=Fixture/Fixed:20261103T110000\r\nSUMMARY:Moved occurrence\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:portal-second\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261105T090000Z\r\nSUMMARY:Second event\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n';
async function importFile(page,base,id,content) {
  await page.goto(base+'/portability?destination='+id);
  const form=page.locator('form').filter({has:page.locator(`input[name=destination][value="${id}"]`)});
  await form.locator('input[type=file]').setInputFiles({name:'../../untrusted.ics',mimeType:'text/calendar',buffer:Buffer.from(content)});
  await Promise.all([page.waitForURL(base+'/portability/preview'),form.getByRole('button',{name:'Validate and preview import',exact:true}).click()]);
  await page.getByRole('heading',{name:'Review your import',exact:true}).waitFor();
}
async function confirmImport(page,base) {
  const form=page.locator('form').filter({has:page.getByRole('button',{name:'Confirm import',exact:true})});
  await form.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForURL(/\/portability\/import\/[a-f0-9-]{36}$/i),form.getByRole('button',{name:'Confirm import',exact:true}).click()]);
  await page.getByRole('heading',{name:'Import results',exact:true}).waitFor();
}
async function resourceUrls(page,base,address,encoded) {
  const response=await call(base,new URL(address).pathname.slice(prefixOf(base).length),{method:'PROPFIND',headers:{Authorization:'Basic '+encoded,Depth:'1'},body:'<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>'});
  assert.equal(response.status,207);
  return page.evaluate(xml=>[...new DOMParser().parseFromString(xml,'application/xml').getElementsByTagNameNS('DAV:','response')].map(r=>r.getElementsByTagNameNS('DAV:','href')[0].textContent).filter(h=>!h.endsWith('/')),response.body);
}
export async function qualifyPersonalPortal(page,base,privateValues,username,suffix='local') {
  const name='Portal events '+suffix;
  const id=await createCollection(page,base,name,'VEVENT');
  const originalAddress=await page.locator('#collection-address').inputValue();
  const edit=page.locator('form').filter({has:page.getByRole('button',{name:'Save details',exact:true})});
  await edit.locator('input[name=name]').fill(name+' renamed');
  await edit.locator('textarea[name=description]').fill('<img src=x onerror=alert(1)> family description');
  await edit.locator('input[name=color]').fill('#126A5EFF');
  await Promise.all([page.waitForURL(base+'/collections/'+id),edit.getByRole('button',{name:'Save details',exact:true}).click()]);
  assert.equal(await page.locator('#collection-address').inputValue(),originalAddress);
  assert.equal(await page.locator('img[src=x]').count(),0);
  await importFile(page,base,id,series);
  assert.equal(await page.getByRole('cell',{name:'Ready',exact:true}).count(),2);
  await confirmImport(page,base);
  assert.equal(await page.getByRole('cell',{name:'Imported',exact:true}).count(),2);
  const device=await issueDavCredential(page,base,privateValues,'Portal round trip '+suffix,username);
  const urls=await resourceUrls(page,base,originalAddress,device.encoded);assert.equal(urls.length,2);
  const originalBodies=[];
  for(const url of urls) {
    const response=await call(base,new URL(url,base).pathname.slice(prefixOf(base).length),{headers:{Authorization:'Basic '+device.encoded}});
    assert.equal(response.status,200);originalBodies.push(response.body);
  }
  const recurring=originalBodies.find(s=>s.includes('UID:portal-series'));
  assert.ok(recurring.includes('RECURRENCE-ID;TZID=Fixture/Fixed:20261103T090000'));
  assert.ok(recurring.includes('DURATION:P1D'));assert.ok(recurring.includes('X-PRIVATE:preserve'));
  await page.goto(base+'/collections/'+id);
  const downloadPromise=page.waitForEvent('download');
  await page.getByRole('link',{name:'Export iCalendar',exact:true}).click();
  const download=await downloadPromise;
  const exported=readFileSync(await download.path());
  assert.equal(exported.toString(),originalBodies.join(''));
  const destination=await createCollection(page,base,'Portal migrated '+suffix,'VEVENT');
  const destinationAddress=await page.locator('#collection-address').inputValue();
  await importFile(page,base,destination,exported);await confirmImport(page,base);
  const newUrls=await resourceUrls(page,base,destinationAddress,device.encoded);assert.equal(newUrls.length,2);
  const migrated=[];
  for(const url of newUrls) migrated.push((await call(base,new URL(url,base).pathname.slice(prefixOf(base).length),{headers:{Authorization:'Basic '+device.encoded}})).body);
  assert.deepEqual(migrated.sort(),originalBodies.sort());
  await importFile(page,base,destination,exported);
  assert.equal(await page.getByRole('cell',{name:'Skipped',exact:true}).count(),2);
  await page.getByRole('button',{name:'Discard preview',exact:true}).click();
  await importFile(page,base,destination,Buffer.from(exported.toString().replace('SUMMARY:Family time','SUMMARY:Conflicting edit')));
  assert.equal(await page.getByRole('cell',{name:'Conflicting',exact:true}).count(),1);
  await page.getByRole('button',{name:'Discard preview',exact:true}).click();
  const deniedUpload=await page.evaluate(async target=>{
    const response=await fetch(target,{method:'POST',body:new FormData()});return response.status;
  },prefixOf(base)+'/portability/preview');
  assert.equal(deniedUpload,403);
  await page.goto(base+'/collections/'+destination+'/delete');
  const withoutConfirmation=await page.evaluate(async ()=>{
    const form=document.querySelector('main form');const data=new URLSearchParams(new FormData(form));data.delete('confirmed');
    return (await fetch(form.action,{method:'POST',body:data})).status;
  });
  assert.equal(withoutConfirmation,400);
  await page.getByRole('checkbox').check();
  await Promise.all([page.waitForURL(base+'/collections'),page.getByRole('button',{name:'Delete collection permanently',exact:true}).click()]);
  assert.ok([403,404].includes((await call(base,new URL(destinationAddress).pathname.slice(prefixOf(base).length),{method:'PROPFIND',headers:{Authorization:'Basic '+device.encoded,Depth:'0'},body:'<D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'})).status));
  console.log('PORTAL_COLLECTION_METADATA_IMPORT_EXPORT_ROUNDTRIP_CONFLICTS_AND_CONFIRMED_DELETE_PASSED '+suffix);
  return {id,address:originalAddress,device,name:name+' renamed'};
}

export async function qualifyPortalPresentation(page,base,collection,administrator=true,suffix='local') {
  const output=process.env.BUSYSTACK_PORTAL_EVIDENCE;
  if(output) mkdirSync(output,{recursive:true});
  const pages=['/','/collections','/collections/new','/collections/'+collection.id,'/collections/'+collection.id+'/sharing','/connections','/security','/security/credentials','/security/passkeys','/security/sessions','/security/recovery','/security/password','/oauth-grants','/portability','/login'];
  if(administrator) pages.push('/admin','/admin/accounts','/admin/accounts/new','/admin/groups','/admin/audit','/admin/diagnostics','/admin/oauth-clients');
  const unexpected=[];
  const observe=request=>{if(!request.url().startsWith(base+'/') && !request.url().startsWith('data:')) unexpected.push(new URL(request.url()).origin);};
  page.on('request',observe);
  try {
    for(const viewport of [{width:1280,height:900},{width:390,height:844},{width:320,height:844}]) {
      await page.setViewportSize(viewport);
      for(const path of pages) {
        const response=await page.goto(base+path);assert.equal(response.status(),200,path);
        assert.equal(await page.locator('main h1').count(),1,path+' must have one primary heading');
        assert.equal(await page.locator('link[rel=stylesheet]').count(),1);
        assert.ok(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor!=='rgba(0, 0, 0, 0)'),path+' must be styled');
        if(output) await page.screenshot({path:output+'/'+suffix+'-'+viewport.width+'-'+path.replaceAll('/','_').replaceAll('?','_')+'.png',fullPage:true});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),path+' must fit the viewport');
        const unlabeled=await page.locator('input:not([type=hidden]),select,textarea').evaluateAll(elements=>elements.filter(e=>!e.labels?.length && !e.getAttribute('aria-label') && !e.getAttribute('aria-labelledby')).map(e=>e.name||e.id));
        assert.deepEqual(unlabeled,[],path+' control labels');
        await page.keyboard.press('Tab');
        assert.ok(await page.evaluate(()=>document.activeElement.tagName==='A'),'The skip link must be the first keyboard stop');
        await page.keyboard.press('Enter');
        assert.equal(new URL(page.url()).hash,'#main');
        assert.equal(await page.evaluate(()=>document.activeElement.id),'main','Skip navigation must move keyboard focus to the content');

      }
    }
    await page.setViewportSize({width:1280,height:900});
    await page.goto(base+'/connections');
    await page.getByRole('button',{name:'Copy server',exact:true}).focus();
    assert.ok(await page.evaluate(()=>getComputedStyle(document.activeElement).outlineStyle!=='none'));
    await page.evaluate(()=>document.documentElement.style.fontSize='200%');
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Two hundred percent text resizing must reflow');
    await page.emulateMedia({colorScheme:'dark',reducedMotion:'reduce'});
    await page.goto(base+'/');
    if(output) await page.screenshot({path:output+'/'+suffix+'-dark-overview.png',fullPage:true});
    await page.emulateMedia({colorScheme:'light',reducedMotion:'reduce'});
    assert.deepEqual(unexpected,[],'Frontend assets must remain local');
  } finally {page.off('request',observe);}
  console.log('PORTAL_DESKTOP_MOBILE_KEYBOARD_LABEL_FOCUS_AND_LOCAL_ASSETS_PASSED '+suffix);
}
