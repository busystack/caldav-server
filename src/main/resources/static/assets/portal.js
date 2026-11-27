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

const oneTimeReturn = document.querySelector('[data-one-time-return]');
if (oneTimeReturn) {
  window.addEventListener('pagehide', () => {
    document.querySelectorAll('[data-one-time-secret]').forEach(node => {
      node.replaceChildren(document.createTextNode('The secret is no longer displayed.'));
    });
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) window.location.replace(oneTimeReturn.href);
  });
}

document.querySelectorAll('form[data-single-submit]').forEach(form => {
  let submitted = false;
  const button = form.querySelector('button[type="submit"], button:not([type])');
  const original = button?.textContent;
  form.addEventListener('submit', event => {
    if (event.defaultPrevented) return;
    if (submitted) { event.preventDefault(); return; }
    submitted = true;
    if (button) { button.disabled = true; button.textContent = form.dataset.singleSubmit; }
    form.setAttribute('aria-busy', 'true');
  });
  window.addEventListener('pageshow', event => {
    if (!event.persisted) return;
    submitted = false;
    if (button) { button.disabled = false; button.textContent = original; }
    form.removeAttribute('aria-busy');
  });
});

const menu = document.querySelector('.section-disclosure');
if (menu) {
  const desktop = window.matchMedia('(min-width: 58rem)');
  const updateMenu = () => { menu.open = desktop.matches; };
  desktop.addEventListener('change', updateMenu);
  updateMenu();
  menu.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.open && !desktop.matches) {
      menu.open = false;
      menu.querySelector('summary').focus();
    }
  });
}

document.querySelectorAll('[data-password-policy]').forEach(form => {
  const password = form.elements.password;
  const confirmation = form.elements.confirmation;
  const show = form.querySelector('[data-show-password]');
  if (show) {
    show.hidden = false;
    show.addEventListener('click', () => {
      const visible = password.type === 'password';
      password.type = visible ? 'text' : 'password';
      if (confirmation) confirmation.type = password.type;
      show.textContent = visible ? 'Hide passwords' : 'Show passwords';
    });
  }

  const fields = [password, confirmation].filter(Boolean);
  fields.forEach(field => field.addEventListener('input', () => {
    fields.forEach(input => input.setCustomValidity(''));
  }));
  form.addEventListener('submit', event => {
    let invalid;
    if ([...password.value].length < 14 || new TextEncoder().encode(password.value).length > 72
        || /[\uD800-\uDFFF]/u.test(password.value)) {
      invalid = password;
      invalid.setCustomValidity('Use at least 14 Unicode characters and at most 72 UTF-8 bytes.');
    } else if (confirmation && confirmation.value !== password.value) {
      invalid = confirmation;
      invalid.setCustomValidity('Enter the same password in both fields.');
    }
    if (invalid) {
      event.preventDefault();
      invalid.reportValidity();
    }
  });
});

document.querySelectorAll('[data-collection-type]').forEach(form => {
  const heading = document.getElementById('creation-title');
  const submit = form.querySelector('button[type=submit]');
  const titles = {VEVENT: 'Create calendar', VTODO: 'Create task list', VJOURNAL: 'Create journal'};
  const update = () => {
    const title = titles[form.elements.component.value] || 'Create a resource';
    heading.textContent = title;
    submit.textContent = title;
  };
  form.addEventListener('change', update);
  update();
  const button = form.querySelector('[data-color-picker]');
  const picker = form.querySelector('input[type=color]');
  button.hidden = false;
  button.addEventListener('click', () => {
    picker.hidden = false;
    const color = form.elements.color.value;
    if (/^#[\da-f]{6}([\da-f]{2})?$/i.test(color)) picker.value = color.slice(0, 7);
    picker.focus();
  });
  picker.addEventListener('change', () => {
    const alpha = /^#[\da-f]{8}$/i.test(form.elements.color.value) ? form.elements.color.value.slice(7) : '';
    form.elements.color.value = picker.value + alpha;
  });
});

const sessionWarning = document.querySelector('[data-session-warning]');
if (sessionWarning) {
  const stay = sessionWarning.querySelector('[data-session-stay]');
  const message = sessionWarning.querySelector('[data-session-message]');
  let idle = Number(sessionWarning.dataset.idle);
  let overall = Number(sessionWarning.dataset.overall);
  let offset = Number(sessionWarning.dataset.server) - Date.now();
  let timer;
  const update = () => {
    clearTimeout(timer);
    const remaining = Math.min(idle, overall) - (Date.now() + offset);
    sessionWarning.hidden = remaining > 120000;
    if (remaining > 120000) {
      timer = setTimeout(update, remaining - 120000);
      return;
    }
    stay.hidden = overall <= idle || remaining <= 0;
    message.textContent = remaining <= 0 ? 'This browser session may have ended. Sign in to continue.'
      : overall <= idle ? 'Your eight-hour sign-in limit is approaching. Sign in again to continue your work.'
        : 'Your browser session may end soon due to inactivity.';
    if (remaining > 0) timer = setTimeout(update, remaining);
  };
  stay.addEventListener('click', async () => {
    stay.disabled = true;
    try {
      const response = await fetch(sessionWarning.dataset.stay, {
        method: 'POST', cache: 'no-store', credentials: 'same-origin',
        headers: {'X-CSRF-TOKEN': sessionWarning.dataset.csrf, 'Accept': 'application/json'}
      });
      if (!response.ok || response.redirected) throw new Error();
      const deadlines = await response.json();
      idle = deadlines.idleDeadline;
      overall = deadlines.overallDeadline;
      offset = deadlines.serverTime - Date.now();
      update();
    } catch {
      message.textContent = 'BusyStack could not extend this session. Sign in again to continue.';
      stay.hidden = true;
    } finally {
      stay.disabled = false;
      if (sessionWarning.hidden) document.getElementById('main')?.focus();
    }
  });
  update();
}
