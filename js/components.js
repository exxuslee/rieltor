const icon = (name) => {
  const paths = {
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42"/>',
    moon: '<path d="M21 12.8A8.5 8.5 0 1 1 11.2 3 6.5 6.5 0 0 0 21 12.8Z"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>'
  };
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name]}</svg>`;
};

class SiteHeader extends HTMLElement {
  connectedCallback() {
    const page = document.body.dataset.page || '';
    const links = [
      ['home', 'index.html', 'ГОЛОВНА'], ['apartments', 'apartments.html', 'КВАРТИРИ'],
      ['new-buildings', 'new-buildings.html', 'НОВОБУДОВИ'], ['houses', 'houses.html', 'БУДИНКИ'],
      ['land', 'land.html', 'ЗЕМЛЯ'], ['commercial', 'commercial.html', 'КОМЕРЦІЯ'],
      ['sell', 'sell-your-apartment.html', 'ПРОДАМ ВАШУ КВАРТИРУ'], ['contacts', 'contacts.html', 'КОНТАКТИ']
    ];
    this.innerHTML = `<header class="site-header"><div class="container header-inner">
      <a class="brand" href="index.html" aria-label="Ірина Ліннік — головна"><span class="brand-mark">IL</span><span class="brand-name">Ірина Ліннік</span></a>
      <nav class="nav" id="main-nav" aria-label="Головна навігація">${links.map(([key, href, label]) => `<a href="${href}" ${page === key ? 'aria-current="page"' : ''}>${label}</a>`).join('')}</nav>
      <div class="header-actions"><a class="phone" href="tel:+380670000000">+38 067 000 00 00</a>
        <button class="theme-toggle" type="button" aria-label="Змінити тему"><span class="sun">${icon('sun')}</span><span class="moon">${icon('moon')}</span></button>
        <button class="menu-toggle" type="button" aria-controls="main-nav" aria-expanded="false">${icon('menu')}<span>Меню</span></button>
      </div></div></header>`;
    const theme = this.querySelector('.theme-toggle');
    const menu = this.querySelector('.menu-toggle');
    const nav = this.querySelector('.nav');
    theme.addEventListener('click', () => window.toggleTheme());
    menu.addEventListener('click', () => {
      const open = nav.classList.toggle('is-open');
      menu.setAttribute('aria-expanded', String(open));
      document.body.classList.toggle('menu-open', open);
    });
  }
}

class SiteFooter extends HTMLElement {
  connectedCallback() {
    this.innerHTML = `<footer class="site-footer"><div class="container">
      <div class="footer-grid"><div class="footer-brand"><a class="brand" href="index.html"><span class="brand-mark">IL</span><span class="brand-name">Ірина Ліннік</span></a><p>Персональний супровід у купівлі та продажу нерухомості в Ірпені, Бучі та передмісті Києва.</p></div>
      <div><h2 class="footer-title">Нерухомість</h2><div class="footer-links"><a href="apartments.html">Квартири</a><a href="new-buildings.html">Новобудови</a><a href="houses.html">Будинки</a><a href="land.html">Земля</a><a href="commercial.html">Комерція</a></div></div>
      <div><h2 class="footer-title">Зв’язок</h2><div class="footer-links"><a href="tel:+380670000000">+38 067 000 00 00</a><a href="mailto:hello@linnik-realty.ua">hello@linnik-realty.ua</a><a href="contacts.html">Контакти та месенджери</a></div></div></div>
      <div class="footer-bottom"><span>© ${new Date().getFullYear()} Ірина Ліннік</span><span>Сайт персонального рієлтора</span></div>
    </div></footer>`;
  }
}

customElements.define('site-header', SiteHeader);
customElements.define('site-footer', SiteFooter);

window.propertyCard = function (property) {
  const rooms = property.rooms ? `<span class="property-card__spec">${property.rooms} кімн.</span>` : '';
  const area = property.category === 'land' ? `${property.area / 100} соток` : `${property.area} м²`;
  const price = `${property.pricePrefix || ''}${property.price.toLocaleString('uk-UA')}${property.priceSuffix || ' $'}`;
  return `<article class="property-card"><a href="property.html?id=${property.id}" aria-label="Переглянути: ${property.title}">
    <div class="property-card__image"><img src="${property.image}" alt="${property.title}" loading="lazy"></div>
    <div class="property-card__body"><h3 class="property-card__title">${property.title}</h3><p class="property-card__location">${property.location}</p>
      <div class="property-card__meta"><strong class="property-card__price">${price}</strong>${rooms}<span class="property-card__spec">${area}</span>${icon('arrow')}</div>
    </div></a></article>`;
};
