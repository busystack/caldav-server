import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {createCollection, provisionAccount, grantDirect} from './portal-helpers.mjs';
const base=process.env.BUSYSTACK_BROWSER_URL, prefix=new URL(base).pathname.replace(/\/$/,'');
const password=readFileSync('/fixture/password','utf8').trimEnd();
const code=readFileSync('/fixture/initialization-code','utf8').trim();
const privateValues=[password,code];
const output='/evidence'; mkdirSync(output,{recursive:true});
const browser=await chromium.launch({headless:true});
let assertions=0;
const check=(condition,message)=>{assert.ok(condition,message); assertions++;};
async function virtual(context,page) {
  const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
  return cdp;
}
async function shot(page,name,mask=[]) {
  await page.screenshot({path:output+'/'+name+'.png',fullPage:true,mask,maskColor:'#172b38'});
  console.log('P0_SCREEN_CAPTURED '+name);
  for (const width of [320, 390]) {
    await page.setViewportSize({width,height:900});
    check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),name+' fits '+width+'px');
  }
  await page.setViewportSize({width:1280,height:900});
}
async function signIn(page,username) {
  await page.goto(base+'/login'); await page.locator('[name=username]').fill(username);
  await page.locator('[name=password]').fill(password);
  await Promise.all([page.waitForURL(url => url.pathname === prefix + '/auth/continue'),page.getByRole('button',{name:'Sign in',exact:true}).click()]);
}
async function enroll(page) {
  await page.getByRole('button',{name:'Create passkey',exact:true}).click();
  await page.getByRole('heading',{name:'Passkey saved',exact:true}).waitFor();
}
async function verify(page) {
  const cookie=(await page.context().cookies()).find(c=>c.name==='JSESSIONID').value;
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/auth/continue') && !url.pathname.endsWith('/login')),
    page.getByRole('button',{name:'Verify and continue',exact:true}).click()
  ]);
  check(cookie!==(await page.context().cookies()).find(c=>c.name==='JSESSIONID').value,'Session rotates after passkey assertion');
}
async function mutate(page,path,values) {
  return page.evaluate(async ({path,values})=>{
    const token=document.querySelector('meta[name=csrf-token]')?.content||document.querySelector('[name=_csrf]').value;
    const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','X-CSRF-TOKEN':token},body:new URLSearchParams(values)});
    return response.status;
  },{path:prefix+path,values});
}
try {
  const context=await browser.newContext({viewport:{width:1280,height:900},colorScheme:'light'});
  await context.grantPermissions(['clipboard-read','clipboard-write']);
  const page=await context.newPage(); const cdp=await virtual(context,page);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  const remoteAssets=[];page.on('request',request=>{if(new URL(request.url()).origin!==new URL(base).origin)remoteAssets.push(request.resourceType());});
  await page.goto(base+'/'); await page.getByRole('heading',{name:'Set up BusyStack',exact:true}).waitFor();
  await shot(page,'01-protected-setup');
  check((await page.request.get(base+'/setup/administrator',{maxRedirects:0})).status()===302,'Legacy setup leads to the canonical page');
  check((await page.request.get(base+'/help/setup')).ok(),'Local setup help is served');
  check(await mutate(page,'/setup/administrator',{code:'wrong',username:'rejected',password,confirmation:password})===400,'Invalid operator code rejected');
  await page.locator('[name=code]').fill(code);await page.locator('[name=username]').fill('demo-admin');
  await page.locator('[name=password]').fill(password);await page.locator('[name=confirmation]').fill(password+'!');
  await page.getByRole('button',{name:'Create administrator account',exact:true}).click();
  check(page.url()===base+'/setup','Password mismatch is caught before submission');
  check(await page.locator('[name=password]').inputValue()===password,'Mismatch retains the password in the current DOM');
  await page.locator('[name=confirmation]').fill(password);
  await Promise.all([page.waitForURL(base+'/login'),page.getByRole('button',{name:'Create administrator account',exact:true}).click()]);
  await page.getByText('Administrator account created. Sign in to continue.').waitFor();
  await shot(page,'02-sign-in');
  await signIn(page,'demo-admin');await shot(page,'03-create-passkey');
  check(await page.locator('.section-nav').count()===0,'Primary-only layout has no portal navigation');
  check((await page.request.get(base+'/admin/accounts',{maxRedirects:0})).status()===302,'Primary-only direct admin request is routed to verification');
  check(await mutate(page,'/credentials',{label:'Denied password only',days:'1'})===403,'No passkeys: issuance denied');
  await page.evaluate(()=>{window.savedCreate=navigator.credentials.create.bind(navigator.credentials);navigator.credentials.create=async()=>{throw new DOMException('Interaction did not complete','NotAllowedError');};});
  await page.getByRole('button',{name:'Create passkey',exact:true}).click();
  await page.locator('#security-status').filter({hasText:'may have been cancelled'}).waitFor();
  await page.evaluate(()=>{navigator.credentials.create=window.savedCreate;});
  let lostRegistrationResponse=false;
  await cdp.send('Fetch.enable',{patterns:[{urlPattern:'*webauthn/register',requestStage:'Response'}]});
  cdp.on('Fetch.requestPaused',async event=>{
    if(event.responseStatusCode===200){lostRegistrationResponse=true;await cdp.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'Failed'});}
    else await cdp.send('Fetch.continueResponse',{requestId:event.requestId});
  });
  await enroll(page);await cdp.send('Fetch.disable');
  check(lostRegistrationResponse,'A real committed registration with a lost response is reconciled');
  await shot(page,'04-passkey-saved');
  check(!(await page.locator('main').innerText()).includes('No passkeys are enrolled.'),'Registration clears stale empty state');
  check(await mutate(page,'/credentials',{label:'Denied registration only',days:'1'})===403,'Registration alone: issuance denied');
  await verify(page);await page.getByRole('heading',{name:'Overview',exact:true}).waitFor();
  await shot(page,'05-overview');
  await page.setViewportSize({width:320,height:900});
  const menu = page.getByText('Menu',{exact:true}).and(page.locator('summary'));
  await menu.focus();
  await page.keyboard.press('Enter');
  check(await page.locator('.section-disclosure').evaluate(element=>element.open),'Keyboard opens mobile navigation');
  await page.keyboard.press('Escape');
  check(await menu.evaluate(element=>document.activeElement===element && !element.parentElement.open),'Escape closes navigation and returns focus');
  await page.emulateMedia({colorScheme:'dark',reducedMotion:'reduce',forcedColors:'active'});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Dark forced-color view fits width with reduced motion');
  await page.emulateMedia({colorScheme:'light',reducedMotion:'no-preference',forcedColors:'none'});
  await page.setViewportSize({width:1280,height:900});
  await page.getByRole('link',{name:'Open server administration',exact:true}).click();
  await shot(page,'06-server-administration');
  await page.goto(base+'/admin/accounts');await shot(page,'07-accounts');
  await page.goto(base+'/admin/accounts/new');await shot(page,'08-create-account');
  await provisionAccount(page,base,'demo-user','Demo User',password);
  await shot(page,'09-account-handoff');
  await page.goto(base+'/collections/new?component=VJOURNAL');await shot(page,'10-resource-type');
  const calendar=await createCollection(page,base,'Demo calendar','VEVENT');
  await createCollection(page,base,'Demo tasks','VTODO');await shot(page,'11-empty-task-list');
  const journal=await createCollection(page,base,'Demo journal','VJOURNAL');
  await page.goto(base+'/collections');await shot(page,'12-resources');
  await grantDirect(page,base,calendar,'demo-user','READ');await shot(page,'13-sharing');
  const grant = page.locator('form').filter({has:page.locator('input[name=username]:not([type=hidden])')});
  await grant.locator('..').locator('summary').click();
  await grant.locator('input[name=username]').fill('unknown-demo-user');
  const shareError = await Promise.all([page.waitForNavigation(),grant.getByRole('button',{name:'Grant account access',exact:true}).click()]);
  check(shareError[0].status()===400,'Unknown recipient returns the editable form');
  check(await page.locator('.section-nav [aria-current=page]').textContent()==='Calendars, tasks & journals','Collection navigation stays current after validation');
  check(await page.locator('#direct-add-username').inputValue()==='unknown-demo-user','Recipient input survives validation');
  await shot(page,'27-sharing-error');
  await page.goto(base+'/security');await shot(page,'14-security');
  await page.goto(base+'/security/recovery/codes/new');
  await page.getByRole('button',{name:'Generate ten codes',exact:true}).click();
  await page.getByRole('heading',{name:'Save your recovery codes',exact:true}).waitFor();
  await shot(page,'15-recovery-codes',[page.locator('[data-one-time-secret] code')]);
  check(await page.locator('main ol code').count()===10,'Protected recovery workflow issues ten one-time codes');
  await page.goto(base+'/connections');await shot(page,'16-connect-an-app');
  await page.getByRole('link',{name:'GNOME Calendar',exact:true}).click();await shot(page,'17-client-configuration');
  check(await page.locator('#server-address').inputValue()===base+'/dav/','Configured root/subpath server address is correct');
  check(await page.locator('#gnome-address').inputValue()===base.replace('://','://demo-admin@')+'/dav/','GNOME address retains username and prefix');
  await page.getByRole('button',{name:'Copy username',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Username copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())==='demo-admin','Username copied accurately');
  await page.getByRole('button',{name:'Copy address',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Server address copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())===base+'/dav/','Server address copied accurately');
  await page.getByRole('link',{name:'Create application password',exact:true}).click();await shot(page,'18-create-application-password');
  await page.locator('[name=label]').fill('GNOME on demo laptop');
  const intent=await page.locator('[name=intent]').inputValue();
  await page.getByRole('button',{name:'Create application password',exact:true}).click();
  await page.getByRole('heading',{name:'Application password created',exact:true}).waitFor();
  const secret=await page.locator('#issued-secret').innerText();privateValues.push(secret);
  writeFileSync('/fixture/issued-password',secret,{mode:0o600});
  await shot(page,'19-password-created',[page.locator('#issued-secret')]);
  await page.getByRole('button',{name:'Copy password',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Password copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())===secret,'Clipboard contains issued password');
  await page.evaluate(()=>{window.savedClipboard=navigator.clipboard.writeText.bind(navigator.clipboard);navigator.clipboard.writeText=async()=>{throw new DOMException('Denied','NotAllowedError');};});
  await page.getByRole('button',{name:'Copy password',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Copy failed.'}).waitFor();
  check(await page.getByRole('button',{name:'Copy password',exact:true}).evaluate(button=>document.activeElement===button),'Clipboard failure keeps keyboard focus at the action');
  await page.evaluate(()=>{navigator.clipboard.writeText=window.savedClipboard;});
  const receipt = await page.evaluate(async ({path,intent})=>{
    const token=document.querySelector('meta[name=csrf-token]').content;
    const response=await fetch(path,{method:'POST',headers:{'X-CSRF-TOKEN':token},body:new URLSearchParams({label:'GNOME on demo laptop',days:90,client:'gnome',from:'connect',intent})});
    return await response.text();
  },{path:prefix+'/credentials',intent});
  check(receipt.includes('already created an application password') && !receipt.includes(secret),'Same intent produces a safe receipt');
  await page.getByRole('link',{name:'Done',exact:true}).click();
  check(page.url().includes('client=gnome'),'Selected client preserved through issuance');
  check(!(await page.content()).includes(secret),'Secret absent from subsequent response');
  await page.goto(base+'/portability?destination='+journal);await shot(page,'20-import-destination');
  await page.locator('input[type=file]').setInputFiles({name:'synthetic-journal.ics',mimeType:'text/calendar',buffer:Buffer.from('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack//Acceptance//EN\r\nBEGIN:VJOURNAL\r\nUID:ux-journal-fixture\r\nDTSTAMP:20261126T180000Z\r\nDTSTART;VALUE=DATE:20261126\r\nSUMMARY:Demo journal entry\r\nEND:VJOURNAL\r\nEND:VCALENDAR\r\n')});
  await Promise.all([page.waitForURL(/\/portability\/import\/[0-9a-f-]+/),page.getByRole('button',{name:'Validate and preview import'}).click()]);
  await shot(page,'21-import-preview');
  await page.locator('input[name=confirmed]').check();
  await Promise.all([page.waitForNavigation(),page.getByRole('button',{name:'Confirm import'}).click()]);
  await page.getByRole('heading',{name:'Import results',exact:true}).waitFor();
  await shot(page,'22-import-result');
  await page.goto(base+'/admin/audit');await shot(page,'23-audit');
  await page.goto(base+'/admin/diagnostics');await shot(page,'24-diagnostics');
  const userContext=await browser.newContext({viewport:{width:1280,height:900},colorScheme:'light'});
  const user=await userContext.newPage();await virtual(userContext,user);
  await user.goto(base+'/connections?client=thunderbird');
  await signIn(user,'demo-user');await enroll(user);await verify(user);
  const continued=new URL(user.url());
  check(continued.origin===new URL(base).origin && continued.pathname===prefix+'/connections' && continued.searchParams.get('client')==='thunderbird', 'Primary/session rotation preserves the intended application workflow; observed path='+continued.pathname+' query keys='+[...continued.searchParams.keys()].join(','));
  await user.goto(base+'/');await shot(user,'25-shared-overview');
  check(await user.getByRole('link',{name:'Server administration',exact:true}).count()===0,'No administrator UI for ordinary user');
  check(/shared/i.test(await user.locator('main').innerText()),'Shared collections offered in onboarding');
  check(!(await user.locator('main').innerText()).includes('Create a calendar or task list'),'Shared recipient not forced to create collection');
  const clientStatus=await user.evaluate(async path=>(await fetch(path)).status,prefix+'/admin/accounts');check(clientStatus===403,'Ordinary account denied administration');
  await user.getByRole('button',{name:'Sign out',exact:true}).click();
  await signIn(user,'demo-user');await user.getByRole('heading',{name:'Verify your passkey',exact:true}).waitFor();await shot(user,'26-returning-verification');
  check(await user.getByRole('button',{name:'Create passkey',exact:true}).count()===0,'Returning user verifies existing passkey');await verify(user);
  await user.goto(base+'/security/passkeys');await user.getByRole('link',{name:/Remove passkey/}).click();
  await user.getByRole('button',{name:'Remove passkey',exact:true}).click();
  await user.getByRole('heading',{name:'Create a passkey',exact:true}).waitFor();
  const after=await user.evaluate(async path=>(await fetch(path)).json(),prefix+'/auth/state');
  check(!after.enrolled && !after.verified,'Last removal invalidates the completed browser proof');
  check(await mutate(user,'/credentials',{label:'Removed factor',days:'1'})===403,'Removed key does not authorize issuance');
  check(errors.length===0,'No page script errors');
  check(remoteAssets.length===0,'No externally loaded frontend resources');
  writeFileSync(output+'/browser-result.json',JSON.stringify({status:'passed',assertions,playwright:'1.61.1',chromium:browser.version(),base,manualFirefoxBitwarden:'pending',screenshots:27},null,2));
  console.log('P0_BROWSER_PASSED assertions='+assertions+' screenshots=27');
} catch(error) {
  let diagnostic=String(error.stack||error);
  for(const value of privateValues)diagnostic=diagnostic.replaceAll(value,'[redacted]');
  diagnostic=diagnostic.replace(/JSESSIONID=[^;\s'"]+/g,'JSESSIONID=[redacted]');
  console.error('P0_BROWSER_FAILED '+diagnostic);process.exitCode=1;
} finally {await browser.close();}
