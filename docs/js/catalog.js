(function () {
    const grid = document.querySelector('[data-property-grid]');
    if (!grid) return;
    const form = document.querySelector('[data-catalog-filters]');
    const status = document.querySelector('[data-result-status]');
    const more = document.querySelector('[data-load-more]');
    const retry = document.querySelector('[data-retry]');
    let controller, cursor = null, total = 0;
    const restore = () => {
        const params = new URLSearchParams(location.search);
        for (const field of form.elements) {
            if (!field.name) continue;
            if (field.type === 'checkbox') field.checked = params.getAll(field.name).flatMap(v => v.split(',')).includes(field.value);
            else field.value = params.get(field.name) || field.dataset.default || '';
        }
    };
    function filters() {
        const result = {};
        for (const [key, value] of new FormData(form)) {
            if (value) result[key] = result[key] ? `${result[key]},${value}` : value;
        }
        return result;
    }
    async function render(append = false) {
        controller?.abort(); controller = new AbortController();
        const params = filters();
        if (!append) { cursor = null; total = 0; grid.replaceChildren(); }
        if (Number(params.priceMin || 0) > Number(params.priceMax || Infinity)) {
            status.textContent = 'Мінімальна ціна має бути не більшою за максимальну.'; more.hidden = true; return;
        }
        if ((params.priceMin || params.priceMax || (params.sort && params.sort !== 'newest')) &&
            (!params.currency || !params.transactionType || !params.pricePeriod)) {
            status.textContent = 'Для порівняння цін оберіть валюту, тип угоди та ціну за об’єкт, місяць або одиницю площі.';
            more.hidden = true; return;
        }
        status.textContent = 'Завантажуємо оголошення…';
        grid.setAttribute('aria-busy', 'true'); more.disabled = true; retry.hidden = true;
        try {
            const page = await Listings.list({...params, limit: 24, ...(cursor ? {cursor} : {})}, controller.signal);
            grid.insertAdjacentHTML('beforeend', page.items.map(window.propertyCard).join(''));
            total += page.items.length; cursor = page.nextCursor;
            status.textContent = total ? `Показано оголошень: ${total}` : 'За заданими параметрами об’єктів не знайдено. Спробуйте змінити фільтри.';
            more.hidden = !cursor;
        } catch (error) {
            if (error.name === 'AbortError') return;
            status.textContent = error.message; retry.hidden = false; more.hidden = true;
        } finally { grid.setAttribute('aria-busy', 'false'); more.disabled = false; }
    }
    form.addEventListener('submit', event => {
        event.preventDefault(); history.pushState(null, '', `?${new URLSearchParams(filters())}`); render();
    });
    form.addEventListener('reset', () => { history.pushState(null, '', location.pathname); setTimeout(() => { restore(); render(); }); });
    more.addEventListener('click', () => render(true));
    retry.addEventListener('click', () => render());
    window.addEventListener('popstate', () => { restore(); render(); });
    restore(); render();
})();
