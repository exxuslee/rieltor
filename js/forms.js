document.querySelectorAll('[data-local-form]').forEach(form => {
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const success = form.querySelector('.form-success');
    if (success) success.classList.add('is-visible');
    form.reset();
  });
});

const contactTopic = document.querySelector('#contact-topic');
const requestedTopic = new URLSearchParams(window.location.search).get('topic');
const topicLabels = {
  sell: 'Продати нерухомість',
  viewing: 'Записатися на перегляд'
};
if (contactTopic && topicLabels[requestedTopic]) contactTopic.value = topicLabels[requestedTopic];
