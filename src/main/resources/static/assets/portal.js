'use strict';
document.querySelectorAll('[data-copy]').forEach(button => {
  button.addEventListener('click', async () => {
    const target = document.getElementById(button.dataset.copy);
    const status = document.getElementById('copy-status');
    try {
      await navigator.clipboard.writeText(target.value ?? target.textContent);
      status.textContent = 'Copied. Paste it into your application settings.';
    } catch {
      if (target.select) { target.focus(); target.select(); }
      status.textContent = 'Select the field and use your browser’s Copy command.';
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
