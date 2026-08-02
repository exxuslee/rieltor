(function () {
  const grid = document.querySelector('[data-featured-grid]');
  if (grid) grid.innerHTML = window.PROPERTIES.slice(0, 3).map(window.propertyCard).join('');

  document.querySelectorAll('[data-category-tab]').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('[data-category-tab]').forEach(tab => tab.classList.remove('is-active'));
      button.classList.add('is-active');
      const value = button.dataset.categoryTab;
      const items = value === 'all' ? window.PROPERTIES.slice(0, 3) : window.PROPERTIES.filter(item => item.category === value).slice(0, 3);
      grid.innerHTML = items.map(window.propertyCard).join('');
    });
  });
})();
