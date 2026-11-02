'use strict';
(() => {
  const status = document.getElementById('security-status');
  const csrfToken = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  function decode(value) {
    const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
    return Uint8Array.from(raw, character => character.charCodeAt(0));
  }
  function encode(value) {
    return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  async function call(base, path, method, body) {
    const response = await fetch(base + path, {method, credentials: 'same-origin',
      headers: {[csrfHeader]: csrfToken, 'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)});
    if (!response.ok) throw new Error('Request failed (' + response.status + '). Verify your password and existing passkey, then try again.');
    return response.status === 204 ? null : response.json();
  }
  function run(button, action) {
    button.addEventListener('click', async () => {
      button.disabled = true;
      status.textContent = 'Waiting for your authenticator…';
      try { await action(button.dataset.context); }
      catch (error) { status.textContent = error.message || 'Authentication failed.'; }
      finally { button.disabled = false; }
    });
  }
  const register = document.getElementById('register-passkey');
  if (register) run(register, async base => {
    const options = await call(base, 'webauthn/register/options', 'POST');
    options.challenge = decode(options.challenge); options.user.id = decode(options.user.id);
    for (const credential of options.excludeCredentials || []) credential.id = decode(credential.id);
    const credential = await navigator.credentials.create({publicKey: options});
    const data = {id: credential.id, rawId: encode(credential.rawId), type: credential.type,
      response: {attestationObject: encode(credential.response.attestationObject),
        clientDataJSON: encode(credential.response.clientDataJSON), transports: credential.response.getTransports()},
      clientExtensionResults: credential.getClientExtensionResults(), authenticatorAttachment: credential.authenticatorAttachment};
    await call(base, 'webauthn/register', 'POST', {publicKey: {credential: data,
      label: document.getElementById('passkey-label').value.trim() || 'Passkey'}});
    status.textContent = 'Passkey registered. Verify it below to complete MFA.';
  });
  const authenticate = document.getElementById('authenticate-passkey');
  if (authenticate) run(authenticate, async base => {
    const options = await call(base, 'webauthn/authenticate/options', 'POST');
    options.challenge = decode(options.challenge);
    for (const credential of options.allowCredentials || []) credential.id = decode(credential.id);
    const credential = await navigator.credentials.get({publicKey: options});
    const response = credential.response;
    const result = await call(base, 'login/webauthn', 'POST', {id: credential.id, rawId: encode(credential.rawId), type: credential.type,
      response: {authenticatorData: encode(response.authenticatorData), clientDataJSON: encode(response.clientDataJSON),
        signature: encode(response.signature), userHandle: response.userHandle ? encode(response.userHandle) : null},
      clientExtensionResults: credential.getClientExtensionResults(), authenticatorAttachment: credential.authenticatorAttachment});
    const destination = new URL(result.redirectUrl || base, window.location.href);
    const application = new URL(base, window.location.href);
    if (destination.origin !== application.origin || !destination.pathname.startsWith(application.pathname)) {
      throw new Error('The authentication redirect is outside this application. Return to account management.');
    }
    window.location.assign(destination.href);
  });
  for (const button of document.querySelectorAll('.remove-passkey')) run(button, async base => {
    await call(base, 'webauthn/register/' + encodeURIComponent(button.dataset.id), 'DELETE');
    window.location.reload();
  });
})();
