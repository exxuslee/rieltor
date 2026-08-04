const TELEGRAM_TOKEN = '7018688132:AAEttjnGgvF9yfSqwT4ag40IE1Nzp00gbrI';
const ADMIN_CHAT_ID = '530667295';
const TELEGRAM_API_URL = `https://api.telegram.org/bot${TELEGRAM_TOKEN}/sendMessage`;

const formTitles = {
  selection: 'Новий запит: підбір нерухомості',
  valuation: 'Нова заявка: оцінка нерухомості',
  question: 'Нове питання із сайту'
};

function formatTelegramMessage(form) {
  const lines = [`🔔 ${formTitles[form.dataset.formType] || 'Нова заявка із сайту'}`];

  new FormData(form).forEach((rawValue, fieldName) => {
    const control = form.elements[fieldName];
    const label = control?.id ? form.querySelector(`label[for="${control.id}"]`)?.textContent.trim() : fieldName;
    const value = control instanceof HTMLSelectElement
      ? control.selectedOptions[0]?.textContent.trim()
      : String(rawValue).trim();

    if (value) lines.push(`${label || fieldName}: ${value}`);
  });

  lines.push(`Сторінка: ${window.location.href}`);
  lines.push(`Час: ${new Date().toLocaleString('uk-UA', { timeZone: 'Europe/Kyiv' })}`);
  return lines.join('\n');
}

document.querySelectorAll('[data-telegram-form]').forEach(form => {
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!form.reportValidity()) return;

    const submitButton = form.querySelector('[type="submit"]');
    const success = form.querySelector('.form-success');
    const error = form.querySelector('.form-error');
    const originalButtonText = submitButton?.textContent || '';

    success?.classList.remove('is-visible');
    error?.classList.remove('is-visible');
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.setAttribute('aria-busy', 'true');
      submitButton.textContent = 'Надсилаємо…';
    }

    try {
      const response = await fetch(TELEGRAM_API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: new URLSearchParams({
          chat_id: ADMIN_CHAT_ID,
          text: formatTelegramMessage(form)
        })
      });
      const result = await response.json();

      if (!response.ok || !result.ok) throw new Error(result.description || `Telegram API: ${response.status}`);

      form.reset();
      success?.classList.add('is-visible');
      success?.focus();
    } catch (requestError) {
      console.error('Не вдалося надіслати форму в Telegram:', requestError);
      error?.classList.add('is-visible');
      error?.focus();
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.removeAttribute('aria-busy');
        submitButton.textContent = originalButtonText;
      }
    }
  });
});

const contactTopic = document.querySelector('#contact-topic');
const requestedTopic = new URLSearchParams(window.location.search).get('topic');
const topicLabels = {
  sell: 'Продати нерухомість',
  viewing: 'Записатися на перегляд'
};
if (contactTopic && topicLabels[requestedTopic]) contactTopic.value = topicLabels[requestedTopic];
