/* Єдиний клієнт каталогу. Дані оголошень зберігаються лише в backend. */
(function () {
    const configured = document.querySelector('meta[name="catalog-api"]')?.content;
    const base = configured || (['localhost', '127.0.0.1'].includes(location.hostname)
        ? 'http://localhost:8080' : 'https://api.rieltor.dpdns.org');
    const typeGroups = {
        APARTMENT: ['APARTMENT', 'APARTMENT 1', 'APARTMENT 1+', 'APARTMENT 2', 'APARTMENT 2+', 'APARTMENT 3', 'APARTMENT 3+'],
        HOUSE: ['HOUSE', 'HOUSE+', 'HOUSE-'],
        DUPLEX: ['DUPLEX', 'DUPLEX+'],
        LAND: ['LAND']
    };
    const detailedTypes = Object.values(typeGroups).flat().filter(type => type !== 'APARTMENT');
    const unfinishedTypes = new Set(['APARTMENT 1', 'APARTMENT 2', 'APARTMENT 3', 'HOUSE', 'DUPLEX']);
    async function request(path, signal) {
        const response = await fetch(`${base}${path}`, {signal, headers: {Accept: 'application/json'}});
        if (!response.ok) throw new Error(response.status === 404 ? 'Об’єкт більше не доступний.' : 'Не вдалося завантажити оголошення. Спробуйте ще раз.');
        return response.json();
    }
    window.Listings = {
        list: (params = {}, signal) => request(`/api/listings?${new URLSearchParams(params)}`, signal),
        one: (id, signal) => request(`/api/listings/${encodeURIComponent(id)}`, signal),
        types: {
            apartments: typeGroups.APARTMENT.join(','),
            houses: typeGroups.HOUSE.join(','),
            duplexes: typeGroups.DUPLEX.join(','),
            land: typeGroups.LAND.join(',')
        },
        typeCodes: ({propertyType = '', condition = '', rooms = ''} = {}) => {
            if (!propertyType && !condition && !rooms) return '';
            let codes = propertyType ? [...(typeGroups[propertyType] || [])] : [...detailedTypes];
            if (condition === 'RENOVATED') codes = codes.filter(type => type.endsWith('+'));
            if (condition === 'UNFINISHED') codes = codes.filter(type => unfinishedTypes.has(type));
            if (condition === 'OLD_STOCK') codes = codes.filter(type => type.endsWith('-'));
            if (rooms) codes = codes.filter(type => type.startsWith(`APARTMENT ${rooms}`));
            return codes.join(',');
        },
        programs: {EOSELIA: 'єОселя', VOUCHER: 'Ваучер', CERTIFICATE: 'Сертифікат', POSTANOVA: 'Постанова'},
        price: item => `${Number(item.price).toLocaleString('uk-UA', {maximumFractionDigits: 2})} $`,
        pricePerSquareMeter: item => {
            const price = Number(item.price);
            const area = Number(item.area);
            if (!Number.isFinite(price) || !Number.isFinite(area) || price <= 0 || area <= 0) return '';
            return `${Math.round(price / area).toLocaleString('uk-UA')} $/м²`;
        },
        escape: value => String(value ?? '').replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]))
    };
})();
