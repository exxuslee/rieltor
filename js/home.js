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

  const search = document.querySelector('[data-hero-search]');
  if (search) search.addEventListener('submit', event => {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(search));
    const routes = { apartments: 'apartments.html', 'new-buildings': 'new-buildings.html', houses: 'houses.html', land: 'land.html', commercial: 'commercial.html' };
    const target = routes[values.category] || 'apartments.html';
    const params = new URLSearchParams();
    if (values.minPrice) params.set('minPrice', values.minPrice);
    if (values.maxPrice) params.set('maxPrice', values.maxPrice);
    location.href = `${target}?${params}`;
  });
})();
