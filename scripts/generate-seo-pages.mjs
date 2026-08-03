import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const domain = 'https://rieltor.dpdns.org';
const context = { window: {} };
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'js', 'data.js'), 'utf8'), context);
const properties = context.window.PROPERTIES;

const escapeHtml = value => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#039;');

const json = value => JSON.stringify(value).replaceAll('<', '\\u003c');
const areaText = item => item.category === 'land' ? `${item.area / 100} соток` : `${item.area} м²`;
const priceText = item => `${item.pricePrefix || ''}${item.price.toLocaleString('uk-UA')}${item.priceSuffix || ' $'}`;
const imagePath = item => item.image.replace(/\.png$/i, '.webp');
const cardImagePath = item => item.image.replace(/\.png$/i, '-768.webp');
const canonical = item => `${domain}/properties/${item.id}.html`;

const card = item => `<article class="property-card"><a href="/properties/${item.id}.html" aria-label="Переглянути: ${escapeHtml(item.title)}">
  <div class="property-card__image"><img src="/${cardImagePath(item)}" alt="${escapeHtml(item.title)}" loading="lazy" width="768" height="512"></div>
  <div class="property-card__body"><h3 class="property-card__title">${escapeHtml(item.title)}</h3><p class="property-card__location">${escapeHtml(item.location)}</p>
    <div class="property-card__meta"><strong class="property-card__price">${escapeHtml(priceText(item))}</strong>${item.rooms ? `<span class="property-card__spec">${item.rooms} кімн.</span>` : ''}<span class="property-card__spec">${escapeHtml(areaText(item))}</span><span aria-hidden="true">→</span></div>
  </div></a></article>`;

const propertyPage = item => {
  const pageDescription = `${item.title} — ${item.location}, площа ${areaText(item)}, ціна ${priceText(item)}. Перегляд і консультація рієлтора Ірини Ліннік.`;
  const schema = {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebPage',
        '@id': `${canonical(item)}#webpage`,
        url: canonical(item),
        name: `${item.title} — Ірина Ліннік`,
        description: pageDescription,
        inLanguage: 'uk',
        primaryImageOfPage: `${domain}/${imagePath(item)}`,
        breadcrumb: { '@id': `${canonical(item)}#breadcrumb` }
      },
      {
        '@type': 'BreadcrumbList',
        '@id': `${canonical(item)}#breadcrumb`,
        itemListElement: [
          { '@type': 'ListItem', position: 1, name: 'Головна', item: `${domain}/` },
          { '@type': 'ListItem', position: 2, name: 'Купівля', item: `${domain}/buy.html` },
          { '@type': 'ListItem', position: 3, name: item.title, item: canonical(item) }
        ]
      }
    ]
  };

  return `<!doctype html>
<html lang="uk">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>${escapeHtml(item.title)} — Ірина Ліннік</title>
  <meta name="description" content="${escapeHtml(pageDescription)}">
  <link rel="canonical" href="${canonical(item)}">
  <meta property="og:type" content="website">
  <meta property="og:locale" content="uk_UA">
  <meta property="og:title" content="${escapeHtml(item.title)} — Ірина Ліннік">
  <meta property="og:description" content="${escapeHtml(pageDescription)}">
  <meta property="og:url" content="${canonical(item)}">
  <meta property="og:image" content="${domain}/${imagePath(item)}">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="theme-color" content="#f5f1e8">
  <link rel="icon" href="/images/favicon.svg" type="image/svg+xml">
  <link rel="manifest" href="/site.webmanifest">
  <script type="application/ld+json">${json(schema)}</script>
  <script src="/js/theme.js"></script>
  <link rel="stylesheet" href="/css/styles.css">
</head>
<body data-page="buy">
  <site-header></site-header>
  <main>
    <section class="property-detail">
      <div class="container">
        <nav class="breadcrumbs" aria-label="Хлібні крихти"><a href="/index.html">Головна</a><span aria-hidden="true">/</span><a href="/buy.html">Купівля</a><span aria-hidden="true">/</span><span aria-current="page">${escapeHtml(item.title)}</span></nav>
        <div class="property-detail__grid">
          <div class="property-detail__image"><img src="/${imagePath(item)}" alt="${escapeHtml(item.title)}, ${escapeHtml(item.location)}" width="1536" height="1024" fetchpriority="high"></div>
          <article class="property-detail__info">
            <p class="eyebrow">${escapeHtml(context.window.CATEGORY_LABELS[item.category])}</p>
            <h1>${escapeHtml(item.title)}</h1>
            <p class="property-detail__location">${escapeHtml(item.location)}</p>
            <p class="property-detail__price">${escapeHtml(priceText(item))}</p>
            <dl class="spec-list">${item.rooms ? `<div><dt>Кімнати</dt><dd>${item.rooms}</dd></div>` : ''}<div><dt>Площа</dt><dd>${escapeHtml(areaText(item))}</dd></div><div><dt>Поверх</dt><dd>${escapeHtml(item.floor)}</dd></div>${item.year ? `<div><dt>Рік</dt><dd>${item.year}</dd></div>` : ''}</dl>
            <p>${escapeHtml(item.description)}</p>
            <p class="listing-note">Наявність, стан і фінальну вартість об’єкта потрібно підтвердити перед переглядом.</p>
            <a class="btn" href="/contacts.html?topic=viewing&property=${encodeURIComponent(item.id)}">Записатися на перегляд</a>
          </article>
        </div>
      </div>
    </section>
  </main>
  <site-footer></site-footer>
  <script src="/js/components.js"></script>
</body>
</html>
`;
};

const propertyDirectory = path.join(root, 'properties');
fs.mkdirSync(propertyDirectory, { recursive: true });
for (const item of properties) {
  fs.writeFileSync(path.join(propertyDirectory, `${item.id}.html`), propertyPage(item));
}

const buyPath = path.join(root, 'buy.html');
const buy = fs.readFileSync(buyPath, 'utf8');
const cards = properties.map(card).join('\n');
const updatedBuy = buy.replace(
  /<!-- GENERATED_PROPERTY_CARDS_START -->[\s\S]*?<!-- GENERATED_PROPERTY_CARDS_END -->/,
  `<!-- GENERATED_PROPERTY_CARDS_START -->\n${cards}\n<!-- GENERATED_PROPERTY_CARDS_END -->`
);
fs.writeFileSync(buyPath, updatedBuy);

const today = new Date().toISOString().slice(0, 10);
const staticUrls = ['/', '/buy.html', '/sell-your-apartment.html', '/contacts.html', '/about.html', '/privacy.html'];
const urls = [...staticUrls, ...properties.map(item => `/properties/${item.id}.html`)];
const sitemap = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls.map(url => `  <url><loc>${domain}${url}</loc><lastmod>${today}</lastmod></url>`).join('\n')}
</urlset>
`;
fs.writeFileSync(path.join(root, 'sitemap.xml'), sitemap);

console.log(`Generated ${properties.length} property pages and sitemap with ${urls.length} URLs.`);
