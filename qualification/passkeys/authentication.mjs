import assert from 'node:assert/strict';
export async function verifyPasskey(page,base) {
  await page.goto(base+'/auth/continue');
  await Promise.all([page.waitForURL(base+'/auth/continue?verified'),page.getByRole('button',{name:'Verify passkey',exact:true}).click()]);
  await page.getByRole('heading',{name:'Authentication successful',exact:true}).waitFor();
  await page.getByRole('link',{name:'Continue',exact:true}).click();
}
// A normal signed anonymous browser assertion still exercises revoked/disabled
// cryptography even though primary login no longer offers an unordered passkey button.
export async function assertRejectedPasskey(page,base) {
  await page.goto(base+'/login');
  const status=await page.evaluate(async prefix=>{
    const token=document.querySelector('meta[name=csrf-token]').content;
    const call=(path,body)=>fetch(prefix+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':token},body:body===undefined?undefined:JSON.stringify(body)});
    const optionsResponse=await call('/webauthn/authenticate/options');
    if(!optionsResponse.ok)return optionsResponse.status;
    const options=await optionsResponse.json();
    const decode=x=>Uint8Array.from(atob(x.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
    const encode=x=>btoa(String.fromCharCode(...new Uint8Array(x))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
    options.challenge=decode(options.challenge);for(const c of options.allowCredentials||[])c.id=decode(c.id);
    const credential=await navigator.credentials.get({publicKey:options}),r=credential.response;
    return (await call('/login/webauthn',{id:credential.id,rawId:encode(credential.rawId),type:credential.type,response:{authenticatorData:encode(r.authenticatorData),clientDataJSON:encode(r.clientDataJSON),signature:encode(r.signature),userHandle:r.userHandle?encode(r.userHandle):null},clientExtensionResults:credential.getClientExtensionResults(),authenticatorAttachment:credential.authenticatorAttachment})).status;
  },new URL(base).pathname);
  assert.equal(status,401,'Revoked or disabled passkey authentication must fail');
}
