(function () {
  const grid = document.querySelector('[data-property-grid]');
  if (!grid) return;
  const category = document.body.dataset.category || 'all';
  const form = document.querySelector('[data-catalog-filters]');
  const count = document.querySelector('[data-result-count]');
  const params = new URLSearchParams(location.search);

  if (form) {
    ['query', 'minPrice', 'maxPrice', 'rooms'].forEach(name => {
      if (params.has(name) && form.elements[name]) form.elements[name].value = params.get(name);
    });
  }

  function render() {
    const values = form ? Object.fromEntries(new FormData(form)) : {};
    const query = (values.query || '').trim().toLowerCase();
    const min = Number(values.minPrice) || 0;
    const max = Number(values.maxPrice) || Infinity;
    const rooms = Number(values.rooms) || 0;
    const items = window.PROPERTIES.filter(item => {
      const categoryMatch = category === 'all' || item.category === category;
      const textMatch = !query || `${item.title} ${item.location}`.toLowerCase().includes(query);
      return categoryMatch && textMatch && item.price >= min && item.price <= max && (!rooms || item.rooms === rooms);
    });
    grid.innerHTML = items.length ? items.map(window.propertyCard).join('') : '<div class="empty-state">За заданими параметрами об’єктів не знайдено. Спробуйте змінити фільтри.</div>';
    if (count) count.textContent = items.length;
  }

  if (form) {
    form.addEventListener('submit', event => { event.preventDefault(); render(); });
    form.addEventListener('reset', () => setTimeout(render));
  }
  render();
})();
