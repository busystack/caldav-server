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
