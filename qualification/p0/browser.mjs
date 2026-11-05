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
  await page.setViewportSize({width:390,height:900});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),name+' fits mobile width');
  await page.setViewportSize({width:1280,height:900});
}
async function signIn(page,username) {
  await page.goto(base+'/login'); await page.locator('[name=username]').fill(username);
  await page.locator('[name=password]').fill(password);
  await Promise.all([page.waitForURL(base+'/auth/continue'),page.getByRole('button',{name:'Sign in',exact:true}).click()]);
}
async function enroll(page) {
  await page.getByRole('button',{name:'Create passkey',exact:true}).click();
  await page.getByRole('heading',{name:'Passkey created',exact:true}).waitFor();
}
async function verify(page) {
  const cookie=(await page.context().cookies()).find(c=>c.name==='JSESSIONID').value;
  await page.goto(base+'/auth/continue');
  await Promise.all([page.waitForURL(base+'/auth/continue?verified'),page.getByRole('button',{name:'Verify passkey',exact:true}).click()]);
  await page.getByRole('heading',{name:'Authentication successful',exact:true}).waitFor();
  check(cookie!==(await page.context().cookies()).find(c=>c.name==='JSESSIONID').value,'Session rotates after passkey assertion');
  await page.getByRole('link',{name:'Continue',exact:true}).click();
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
  await shot(page,'01-initial-setup');
  await page.getByRole('link',{name:'Create administrator account',exact:true}).click();
  await shot(page,'02-create-administrator');
  check(await mutate(page,'/setup/administrator',{code:'wrong',username:'rejected',password,confirmation:password})===400,'Missing operator code rejected');
  await page.locator('[name=code]').fill(code);await page.locator('[name=username]').fill('demo-admin');
  await page.locator('[name=password]').fill(password);await page.locator('[name=confirmation]').fill(password);
  await page.getByRole('button',{name:'Create administrator account',exact:true}).click();
  await page.getByRole('heading',{name:'Administrator account created',exact:true}).waitFor();
  await shot(page,'03-setup-complete');
  await page.getByRole('link',{name:'Sign in',exact:true}).click(); await shot(page,'04-sign-in');
  await signIn(page,'demo-admin');await shot(page,'05-create-passkey');
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
  await shot(page,'06-passkey-created');
  check(!(await page.locator('main').innerText()).includes('No passkeys are enrolled.'),'Registration clears stale empty state');
  check(await mutate(page,'/credentials',{label:'Denied registration only',days:'1'})===403,'Registration alone: issuance denied');
  await page.getByRole('link',{name:'Verify your passkey',exact:true}).click();await shot(page,'07-verify-passkey');
  await verify(page);await page.getByRole('heading',{name:'Welcome to BusyStack',exact:true}).waitFor();
  await shot(page,'08-administrator-welcome');
  await page.getByRole('button',{name:'Manage users',exact:true}).click();await shot(page,'09-manage-users');
  await page.goto(base+'/admin/accounts/new');await shot(page,'10-create-user');
  await provisionAccount(page,base,'demo-user','Demo User',password);
  const calendar=await createCollection(page,base,'Demo calendar','VEVENT');
  await createCollection(page,base,'Demo tasks','VTODO');
  await page.goto(base+'/collections');await shot(page,'12-calendars');
  await grantDirect(page,base,calendar,'demo-user','READ');await shot(page,'19-sharing');
  await page.goto(base+'/security');await shot(page,'17-security');
  await page.goto(base+'/security/recovery');
  await page.getByRole('button',{name:'Generate ten codes',exact:true}).click();
  await page.getByRole('heading',{name:'Save your recovery codes',exact:true}).waitFor();
  await shot(page,'18-recovery',[page.locator('main ol')]);
  check(await page.locator('main ol code').count()===10,'Protected recovery workflow issues ten one-time codes');
  await page.goto(base+'/connections');await shot(page,'13-connect-application');
  await page.getByRole('link',{name:'GNOME Calendar',exact:true}).click();await shot(page,'16-client-configuration');
  check(await page.locator('#server-address').inputValue()===base+'/dav/','Configured root/subpath server address is correct');
  await page.getByRole('button',{name:'Copy username',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Username copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())==='demo-admin','Username copied accurately');
  await page.getByRole('button',{name:'Copy server',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Server address copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())===base+'/dav/','Server address copied accurately');
  await page.getByRole('link',{name:'Create application password',exact:true}).click();await shot(page,'14-create-application-password');
  await page.locator('[name=label]').fill('GNOME on demo laptop');
  await page.getByRole('button',{name:'Create application password',exact:true}).click();
  await page.getByRole('heading',{name:'Application password created',exact:true}).waitFor();
  const secret=await page.locator('#issued-secret').innerText();privateValues.push(secret);
  writeFileSync('/fixture/issued-password',secret,{mode:0o600});
  await shot(page,'15-password-created',[page.locator('main dd').filter({has:page.locator('#issued-secret')})]);
  await page.getByRole('button',{name:'Copy password',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Password copied.'}).waitFor();
  check(await page.evaluate(()=>navigator.clipboard.readText())===secret,'Clipboard contains issued password');
  await page.evaluate(()=>{window.savedClipboard=navigator.clipboard.writeText.bind(navigator.clipboard);navigator.clipboard.writeText=async()=>{throw new DOMException('Denied','NotAllowedError');};});
  await page.getByRole('button',{name:'Copy password',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Copy failed.'}).waitFor();
  check(await page.getByRole('button',{name:'Copy password',exact:true}).evaluate(button=>document.activeElement===button),'Clipboard failure keeps keyboard focus at the action');
  await page.evaluate(()=>{navigator.clipboard.writeText=window.savedClipboard;});
  check(await mutate(page,'/credentials',{label:'MFA verified',days:'1'})===200,'Completed MFA: issuance succeeds');
  await page.getByRole('link',{name:'Continue to application setup',exact:true}).click();
  check(page.url().includes('client=gnome'),'Selected client preserved through issuance');
  check(!(await page.content()).includes(secret),'Secret absent from subsequent response');
  await page.goto(base+'/portability');await shot(page,'20-import-export');
  const userContext=await browser.newContext({viewport:{width:1280,height:900},colorScheme:'light'});
  const user=await userContext.newPage();await virtual(userContext,user);
  await user.goto(base+'/connections?client=thunderbird');
  await signIn(user,'demo-user');await enroll(user);await verify(user);
  const continued=new URL(user.url());
  check(continued.origin===new URL(base).origin && continued.pathname===prefix+'/connections' && continued.searchParams.get('client')==='thunderbird', 'Primary/session rotation preserves the intended application workflow; observed path='+continued.pathname+' query keys='+[...continued.searchParams.keys()].join(','));
  await user.goto(base+'/');await shot(user,'11-user-onboarding');
  check(await user.getByRole('link',{name:'Server administration',exact:true}).count()===0,'No administrator UI for ordinary user');
  check((await user.locator('main').innerText()).includes('shared'),'Shared collections offered in onboarding');
  check(!(await user.locator('main').innerText()).includes('Create a calendar or task list'),'Shared recipient not forced to create collection');
  const clientStatus=await user.evaluate(async path=>(await fetch(path)).status,prefix+'/admin/accounts');check(clientStatus===403,'Ordinary account denied administration');
  await user.getByRole('button',{name:'Sign out',exact:true}).click();
  await signIn(user,'demo-user');await user.getByRole('heading',{name:'Verify your passkey',exact:true}).waitFor();
  check(await user.getByRole('button',{name:'Create passkey',exact:true}).count()===0,'Returning user verifies existing passkey');await verify(user);
  await user.goto(base+'/security/passkeys');await user.getByRole('button',{name:/Remove passkey/}).click();
  await user.waitForURL(/\/auth\/continue/);
  const after=await user.evaluate(async path=>(await fetch(path)).json(),prefix+'/auth/state');
  check(!after.enrolled && after.verified,'Last removal preserves existing current factor according to established policy');
  check(await mutate(user,'/credentials',{label:'Existing recent factor',days:'1'})===200,'Prior completed MFA remains current after removal');
  await user.getByRole('button',{name:'Sign out',exact:true}).click();await signIn(user,'demo-user');
  check(await mutate(user,'/credentials',{label:'Password only again',days:'1'})===403,'New password-only session denied after last removal');
  check(errors.length===0,'No page script errors');
  check(remoteAssets.length===0,'No externally loaded frontend resources');
  writeFileSync(output+'/browser-result.json',JSON.stringify({status:'passed',assertions,playwright:'1.61.1',chromium:browser.version(),base,manualFirefoxBitwarden:'pending',screenshots:20},null,2));
  console.log('P0_BROWSER_PASSED assertions='+assertions+' screenshots=20');
} catch(error) {
  let diagnostic=String(error.stack||error);
  for(const value of privateValues)diagnostic=diagnostic.replaceAll(value,'[redacted]');
  diagnostic=diagnostic.replace(/JSESSIONID=[^;\s'"]+/g,'JSESSIONID=[redacted]');
  console.error('P0_BROWSER_FAILED '+diagnostic);process.exitCode=1;
} finally {await browser.close();}
