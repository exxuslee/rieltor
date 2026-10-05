(async function () {
    const target = document.querySelector('[data-live-property]') || document.querySelector('main');
    const id = new URLSearchParams(location.search).get('id') || location.pathname.split('/').pop().replace('.html', '');
    const e = Listings.escape;
    try {
        const item = await Listings.one(id);
        document.title = `${item.title} — Ірина Ліннік`;
        const canonical = document.querySelector('link[rel="canonical"]') || document.createElement('link');
        canonical.rel = 'canonical';
        canonical.href = `https://rieltor.dpdns.org/property.html?id=${encodeURIComponent(item.id)}`;
        document.head.append(canonical);
        document.querySelector('meta[name="description"]').content =
            `${item.title} — ${item.location}. ${Listings.price(item)}. Перегляд і консультація Ірини Ліннік.`;
        target.innerHTML = `<section class="property-detail"><div class="container">
            <p><a href="/catalog.html">← Каталог нерухомості</a></p>
            <div class="property-detail__grid"><div class="property-detail__image"><img src="${e(item.image)}" alt="${e(item.title)}" width="1080" height="720"></div>
            <article class="property-detail__info"><h1>${e(item.title)}</h1><p>${e(item.location)}</p>
            <p class="property-detail__price">${e(Listings.price(item))}</p>
            <p>${item.area ? `${e(item.area)} м²` : ''} ${item.rooms ? `· ${e(item.rooms)} кімн.` : ''} ${item.landAreaSotka ? `· ${e(item.landAreaSotka)} соток` : ''}</p>
            <p>${e(item.description)}</p><ul>${(item.primeParams.details || []).map(value => `<li>${e(value)}</li>`).join('')}</ul>
            <p>${item.governmentPrograms.map(code => e(Listings.programs[code])).join(' · ')}</p>
            <a class="btn" href="tel:+380663727102">Записатися на перегляд</a></article></div>
            <div class="listing-gallery">${item.photos.slice(1).map(url => `<img src="${e(url)}" alt="${e(item.title)} — фото об’єкта" loading="lazy" width="1080" height="720">`).join('')}</div>
            </div></section>`;
    } catch (error) {
        if (error.status === 404) document.querySelector('meta[name="robots"]').content = 'noindex,follow';
        target.innerHTML = `<section class="page-hero"><div class="container"><h1>Оголошення недоступне</h1><p>${e(error.message)}</p><a class="btn" href="/catalog.html">Перейти до каталогу</a></div></section>`;
    }
})();
