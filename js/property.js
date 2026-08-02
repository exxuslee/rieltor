(function () {
  const root = document.querySelector('[data-property-detail]');
  if (!root) return;
  const id = new URLSearchParams(location.search).get('id');
  const item = window.PROPERTIES.find(property => property.id === id) || window.PROPERTIES[0];
  document.title = `${item.title} — Ірина Ліннік`;
  const area = item.category === 'land' ? `${item.area / 100} соток` : `${item.area} м²`;
  const price = `${item.pricePrefix || ''}${item.price.toLocaleString('uk-UA')}${item.priceSuffix || ' $'}`;
  root.innerHTML = `<a class="property-back" href="javascript:history.back()">← Повернутися до каталогу</a>
    <div class="property-detail__grid"><div class="property-detail__image"><img src="${item.image}" alt="${item.title}"></div>
      <div class="property-detail__info"><h1>${item.title}</h1><p class="property-detail__location">${item.location}</p><p class="property-detail__price">${price}</p>
        <dl class="spec-list">${item.rooms ? `<div><dt>Кімнати</dt><dd>${item.rooms}</dd></div>` : ''}<div><dt>Площа</dt><dd>${area}</dd></div><div><dt>Поверх</dt><dd>${item.floor}</dd></div>${item.year ? `<div><dt>Рік</dt><dd>${item.year}</dd></div>` : ''}</dl>
        <p>${item.description}</p><a class="btn" href="contacts.html">Записатися на перегляд</a></div></div>`;
})();
