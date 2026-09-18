(function () {
    const grid = document.querySelector('[data-property-grid]');
    if (!grid) return;
    const form = document.querySelector('[data-catalog-filters]');
    const status = document.querySelector('[data-result-status]');
    const more = document.querySelector('[data-load-more]');
    const retry = document.querySelector('[data-retry]');
    let controller, cursor = null, total = 0;
    const selectedValues = name => Array.from(form.querySelectorAll(`input[name="${name}"]:checked`), input => input.value);
    const updateRelevantFilters = () => {
        const types = selectedValues('propertyType');
        const supports = type => !types.length || types.includes(type);
        for (const input of form.querySelectorAll('[name="rooms"], [name="condition"]')) {
            input.disabled = input.name === 'rooms' ? !supports('APARTMENT')
                : input.value === 'OLD_STOCK' ? !supports('HOUSE')
                    : types.length > 0 && types.every(type => type === 'LAND');
            if (input.disabled) input.checked = false;
        }
        for (const details of form.querySelectorAll('.filter-multi')) {
            const summary = details.querySelector('summary span');
            const labels = Array.from(details.querySelectorAll('input:checked'), input => input.parentElement.textContent.trim());
            summary.textContent = labels.join(', ') || summary.dataset.empty;
        }
    };
    const restore = () => {
        const params = new URLSearchParams(location.search);
        for (const field of form.elements) {
            if (!field.name) continue;
            if (field.type === 'checkbox') field.checked = params.getAll(field.name).flatMap(v => v.split(',')).includes(field.value);
            else field.value = params.get(field.name) || field.dataset.default || '';
        }
        updateRelevantFilters();
    };

    function filters() {
        const result = {};
        for (const [key, value] of new FormData(form)) {
            if (value) result[key] = result[key] ? `${result[key]},${value}` : value;
        }
        return result;
    }

    function apiFilters(selected) {
        const {propertyType, condition, rooms, ...result} = selected;
        const typeOfRealty = Listings.typeCodes({propertyType, condition, rooms});
        if (typeOfRealty) result.typeOfRealty = typeOfRealty;
        return result;
    }

    async function render(append = false) {
        controller?.abort();
        controller = new AbortController();
        const selected = filters();
        const params = apiFilters(selected);
        if (!append) {
            cursor = null;
            total = 0;
            grid.replaceChildren();
        }
        if (Number(selected.priceMin || 0) > Number(selected.priceMax || Infinity)) {
            status.textContent = 'Мінімальна ціна має бути не більшою за максимальну.';
            more.hidden = true;
            return;
        }
        status.textContent = 'Завантажуємо оголошення…';
        grid.setAttribute('aria-busy', 'true');
        more.disabled = true;
        retry.hidden = true;
        try {
            const page = await Listings.list({...params, limit: 24, ...(cursor ? {cursor} : {})}, controller.signal);
            grid.insertAdjacentHTML('beforeend', page.items.map(window.propertyCard).join(''));
            total += page.items.length;
            cursor = page.nextCursor;
            status.textContent = total ? `Показано оголошень: ${total}` : 'За заданими параметрами об’єктів не знайдено. Спробуйте змінити фільтри.';
            more.hidden = !cursor;
        } catch (error) {
            if (error.name === 'AbortError') return;
            status.textContent = error.message;
            retry.hidden = false;
            more.hidden = true;
        } finally {
            grid.setAttribute('aria-busy', 'false');
            more.disabled = false;
        }
    }

    form.addEventListener('submit', event => {
        event.preventDefault();
        history.pushState(null, '', `?${new URLSearchParams(filters())}`);
        render();
    });
    form.addEventListener('change', updateRelevantFilters);
    document.addEventListener('pointerdown', event => {
        form.querySelectorAll('.filter-multi[open]').forEach(details => {
            if (!details.contains(event.target)) details.open = false;
        });
    });
    form.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        const details = event.target.closest('.filter-multi');
        if (details) {
            details.open = false;
            details.querySelector('summary').focus();
        }
    });
    form.addEventListener('reset', () => {
        history.pushState(null, '', location.pathname);
        setTimeout(() => {
            restore();
            render();
        });
    });
    more.addEventListener('click', () => render(true));
    retry.addEventListener('click', () => render());
    window.addEventListener('popstate', () => {
        restore();
        render();
    });
    restore();
    render();
})();
