'use strict';
document.querySelectorAll('[data-copy]').forEach(button => {
  button.addEventListener('click', async () => {
    const target = document.getElementById(button.dataset.copy);
    let status = button.parentElement.querySelector('[data-copy-feedback]');
    if (!status) {
      status = document.createElement('span');
      status.dataset.copyFeedback = '';
      status.setAttribute('role', 'status');
      status.setAttribute('aria-live', 'polite');
      button.insertAdjacentElement('afterend', status);
    }
    try {
      await navigator.clipboard.writeText(target.value ?? target.textContent);
      status.textContent = button.dataset.copyMessage || (button.dataset.copy.includes('username') ? 'Username copied.' : button.dataset.copy === 'issued-secret' ? 'Password copied.' : 'Server address copied.');
    } catch {
      status.textContent = 'Copy failed. Select the text and use your browser’s Copy command.';
    }
  });
});

const errorSummary = document.querySelector('[data-error-summary]');
if (errorSummary) {
  errorSummary.focus();
  errorSummary.querySelectorAll('a[href^="#"]').forEach(link => link.addEventListener('click', event => {
    const field = document.getElementById(link.hash.slice(1));
    if (field) { event.preventDefault(); field.closest('details')?.setAttribute('open', ''); field.focus(); }
  }));
}

window.addEventListener('pagehide', () => {
  const secret = document.getElementById('issued-secret');
  if (secret) secret.textContent = 'This password is no longer displayed. Create a replacement if needed.';
});

document.querySelectorAll('form[data-single-submit]').forEach(form => {
  let submitted = false;
  form.addEventListener('submit', event => {
    if (submitted) { event.preventDefault(); return; }
    submitted = true;
    const button = form.querySelector('button[type="submit"], button:not([type])');
    if (button) { button.disabled = true; button.textContent = 'Creating application password…'; }
    form.setAttribute('aria-busy', 'true');
  });
});
window.addEventListener('pageshow', event => {
  if (!event.persisted) return;
  const destination = new URL(window.location.href);
  const stylesheet = document.querySelector('link[href$="/assets/account.css"]');
  const prefix = stylesheet ? new URL(stylesheet.href).pathname.slice(0, -'/assets/account.css'.length) : '';
  if (destination.pathname === prefix + '/credentials') {
    destination.pathname = prefix + '/security/credentials'; destination.search = '';
  } else if (destination.pathname.startsWith(prefix + '/security/recovery/')) {
    destination.pathname = prefix + '/security/recovery'; destination.search = '';
  }
  // Navigate with GET; reloading a historical POST could reissue a one-time secret.
  window.location.replace(destination.href);
});
