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
