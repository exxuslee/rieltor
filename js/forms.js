document.querySelectorAll('[data-local-form]').forEach(form => {
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const success = form.querySelector('.form-success');
    if (success) success.classList.add('is-visible');
    form.reset();
  });
});
