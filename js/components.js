const icon = (name) => {
  const paths = {
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42"/>',
    moon: '<path d="M21 12.8A8.5 8.5 0 1 1 11.2 3 6.5 6.5 0 0 0 21 12.8Z"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>'
  };
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name]}</svg>`;
};

const siteUrl = path => `/${String(path).replace(/^\/+/, '')}`;

class SiteHeader extends HTMLElement {
  connectedCallback() {
    const page = document.body.dataset.page || '';
    const links = [
      ['home', siteUrl('index.html'), 'ГОЛОВНА'], ['buy', siteUrl('buy.html'), 'КУПІВЛЯ'],
      ['sell', siteUrl('sell-your-apartment.html'), 'ПРОДАЖ'], ['contacts', siteUrl('contacts.html'), 'КОНТАКТИ']
    ];
    this.innerHTML = `<header class="site-header"><div class="container header-inner">
      <a class="brand" href="${siteUrl('index.html')}" aria-label="Ірина Ліннік — головна"><span class="brand-mark">IL</span><span class="brand-name">Ірина Ліннік</span></a>
      <nav class="nav" id="main-nav" aria-label="Головна навігація">${links.map(([key, href, label]) => `<a href="${href}" ${page === key ? 'aria-current="page"' : ''}>${label}</a>`).join('')}</nav>
      <div class="header-actions"><a class="phone" href="tel:+380663727102">+380 (66) 372 71 02</a>
        <button class="theme-toggle" type="button" aria-label="Змінити тему"><span class="sun">${icon('sun')}</span><span class="moon">${icon('moon')}</span></button>
        <button class="menu-toggle" type="button" aria-controls="main-nav" aria-expanded="false">${icon('menu')}<span>Меню</span></button>
      </div></div></header>`;
    const theme = this.querySelector('.theme-toggle');
    const menu = this.querySelector('.menu-toggle');
    const nav = this.querySelector('.nav');
    const header = this.querySelector('.site-header');
    const updateHeaderSize = () => {
      if (window.scrollY > 100) header.classList.add('is-compact');
      if (window.scrollY < 10) header.classList.remove('is-compact');
    };
    const onScroll = () => {
      updateHeaderSize();
    };
    updateHeaderSize();
    window.addEventListener('scroll', onScroll, { passive: true });
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
      <div class="footer-grid"><div class="footer-brand"><a class="brand" href="${siteUrl('index.html')}"><span class="brand-mark">IL</span><span class="brand-name">Ірина Ліннік</span></a><p>Персональний супровід у купівлі та продажу нерухомості в Ірпені, Бучі та Гостомелі.</p><div class="footer-links"><a href="${siteUrl('about.html')}">Про Ірину</a><a href="${siteUrl('privacy.html')}">Конфіденційність</a></div></div>
      <div><h2 class="footer-title">Послуги</h2><div class="footer-links"><a href="${siteUrl('buy.html')}">Купівля нерухомості</a><a href="${siteUrl('sell-your-apartment.html')}">Продаж нерухомості</a><a href="${siteUrl('contacts.html')}">Консультація</a></div></div>
      <div><h2 class="footer-title">Зв’язок</h2><div class="footer-links"><a href="tel:+380663727102">+380 (66) 372 71 02</a><a href="mailto:irinalinnik.lee@gmail.com">irinalinnik.lee@gmail.com</a><a href="https://t.me/irynalinnik_rieltor" target="_blank" rel="noopener noreferrer">Telegram: @irynalinnik_rieltor</a><a href="https://www.tiktok.com/@irina_rieltor_novator" target="_blank" rel="noopener noreferrer">TikTok: @irina_rieltor_novator</a></div></div></div>
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
  return `<article class="property-card"><a href="${siteUrl(`properties/${property.id}.html`)}" aria-label="Переглянути: ${property.title}">
    <div class="property-card__image"><img src="${siteUrl(property.image.replace(/\.png$/i, '-768.webp'))}" alt="${property.title}" loading="lazy" width="768" height="512"></div>
    <div class="property-card__body"><h3 class="property-card__title">${property.title}</h3><p class="property-card__location">${property.location}</p>
      <div class="property-card__meta"><strong class="property-card__price">${price}</strong>${rooms}<span class="property-card__spec">${area}</span>${icon('arrow')}</div>
    </div></a></article>`;
};
