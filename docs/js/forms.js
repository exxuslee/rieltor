const LANDING_LEAD_API_URL = 'https://api.rieltor.dpdns.org/v1/landing/leads';

function landingLeadPayload(form) {
    const fields = {};
    new FormData(form).forEach((value, name) => {
        if (name !== 'website') fields[name] = String(value).trim();
    });
    return {
        formType: form.dataset.formType,
        fields,
        pageUrl: window.location.href,
        website: form.elements.website?.value || ''
    };
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
            const response = await fetch(LANDING_LEAD_API_URL, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(landingLeadPayload(form))
            });
            const result = await response.json();

            if (!response.ok || !result.ok) throw new Error(`API: ${response.status}`);

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
