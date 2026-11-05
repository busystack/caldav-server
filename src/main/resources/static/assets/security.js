'use strict';
(() => {
  const status = document.getElementById('security-status');
  if (!status) return;
  const csrfToken = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  let busy = false;
  function decode(value) {
    return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  }
  function encode(value) {
    return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  async function call(base, path, method, body) {
    let response;
    try {
      response = await fetch(base + path, {method, credentials: 'same-origin', cache: 'no-store',
        headers: {[csrfHeader]: csrfToken, 'Content-Type': 'application/json'},
        body: body === undefined ? undefined : JSON.stringify(body)});
    } catch { throw new Error('The network response is uncertain. Check your connection and account status before retrying.'); }
    const state = response.headers.get('X-BusyStack-Auth-State');
    const messages = {
      'challenge-expired': 'This challenge expired or was already used. Try again to request a new prompt.',
      'primary-required': 'Primary sign-in expired. Sign in again, then return to this step.',
      'verification-required': 'Verify your existing passkey before managing passkeys.',
      'session-ended': 'Your session was invalidated. Sign in again to check current account access.',
      'registration-rejected': 'The server rejected this registration. Check your account status, then try again.'
    };
    if (state) throw new Error(messages[state] || 'Check your account status before continuing.');
    if (response.redirected || response.headers.get('Content-Type')?.includes('text/html') && response.ok)
      throw new Error('Your session changed or expired. Sign in again to continue.');
    if (!response.ok) {
      if (response.status === 401) throw new Error('Passkey verification was rejected. Use a passkey for this account and request a new prompt.');
      if (response.status === 403) throw new Error('This operation is no longer authorized. Check account status or sign in again.');
      if (response.status === 400) throw new Error('The server rejected this ceremony. Request a new prompt and use a passkey for this account.');
      throw new Error('The server could not complete this operation. Check account status before retrying.');
    }
    return response.status === 204 ? null : response.json();
  }
  function explain(error) {
    if (error.name === 'NotAllowedError') return 'The passkey interaction did not complete. It may have been cancelled, timed out, or no available passkey was selected. Try again when ready.';
    if (error.name === 'InvalidStateError') return 'This authenticator may already have a passkey for this account. Check account status before creating another.';
    if (error.name === 'NotSupportedError') return 'This browser or authenticator cannot complete the requested passkey operation. Try a supported browser or passkey provider.';
    if (error.name === 'SecurityError') return 'Passkeys require the configured secure BusyStack address. Check the HTTPS hostname with your operator.';
    if (error.name === 'AbortError') return 'The passkey interaction was interrupted. Try again when ready.';
    return error.message || 'The passkey interaction could not complete. Check account status and try again.';
  }
  function run(button, action) {
    button.addEventListener('click', async () => {
      if (busy) return;
      busy = true;
      const buttons = [...document.querySelectorAll('#register-passkey, #authenticate-passkey, .remove-passkey')];
      buttons.forEach(b => { b.disabled = true; });
      status.textContent = 'Waiting for your browser or authenticator…';
      try {
        if (!window.isSecureContext || !window.PublicKeyCredential || !navigator.credentials)
          throw new Error('Passkeys are unavailable in this browser or address. Use a supported browser at the configured HTTPS URL.');
        await action(button.dataset.context);
      } catch (error) { status.textContent = explain(error); }
      finally { busy = false; buttons.forEach(b => { b.disabled = false; }); }
    });
  }
  function registered() {
    document.getElementById('enrollment-stage').hidden = true;
    document.getElementById('registration-success').hidden = false;
    status.textContent = 'Passkey created. Verify your passkey to continue.';
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
    const first = !!document.getElementById('enrollment-stage');
    try {
      await call(base, 'webauthn/register', 'POST', {publicKey: {credential: data,
        label: document.getElementById('passkey-label')?.value.trim() || 'Passkey'}});
    } catch (error) {
      // A committed first registration with a lost response must not encourage another one.
      if (first) {
        try { if ((await call(base, 'auth/state', 'GET')).enrolled) { registered(); return; } }
        catch { /* Preserve the original uncertain response; never claim success. */ }
      }
      throw error;
    }
    if (first) registered();
    else { status.textContent = 'Passkey created. Updating registered passkeys…'; window.location.reload(); }
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
    if (destination.origin !== application.origin || !destination.pathname.startsWith(application.pathname))
      throw new Error('The authentication destination is unavailable. Return to account management.');
    status.textContent = 'Authentication successful. Continuing…';
    window.location.assign(destination.href);
  });
  for (const button of document.querySelectorAll('.remove-passkey')) run(button, async base => {
    await call(base, 'webauthn/register/' + encodeURIComponent(button.dataset.id), 'DELETE');
    status.textContent = 'Passkey removed. Checking current account state…';
    window.location.assign(base + 'auth/continue?next=' + encodeURIComponent(new URL(base, location.href).pathname + 'security/passkeys'));
  });
  window.addEventListener('pageshow', event => { if (event.persisted) window.location.reload(); });
})();
