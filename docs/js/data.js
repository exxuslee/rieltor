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
            const values = value => Array.isArray(value) ? value : String(value).split(',').filter(Boolean);
            const types = values(propertyType), conditions = values(condition), roomCounts = values(rooms);
            if (!types.length && !conditions.length && !roomCounts.length) return '';
            let codes = types.length ? types.flatMap(type => typeGroups[type] || []) : Object.values(typeGroups).flat();
            if (!types.length && roomCounts.length) codes = [...typeGroups.APARTMENT];
            if (!types.length && conditions.length) codes = codes.filter(type => type !== 'LAND');
            codes = codes.filter(type => {
                // Room counts apply to apartments; condition does not apply to land.
                if (type.startsWith('APARTMENT') && roomCounts.length && !roomCounts.some(room => type.startsWith(`APARTMENT ${room}`))) return false;
                if (type === 'LAND' || !conditions.length) return true;
                return conditions.some(condition => condition === 'RENOVATED' ? type.endsWith('+')
                    : condition === 'UNFINISHED' ? unfinishedTypes.has(type)
                        : condition === 'OLD_STOCK' && type.endsWith('-'));
            });
            return codes.join(',');
        },
        telegramDate: item => {
            const timestamp = Number(item.sourceCreatedAt);
            if (!Number.isFinite(timestamp) || timestamp <= 0) return null;
            const date = new Date(timestamp);
            if (!Number.isFinite(date.getTime())) return null;
            return {
                iso: date.toISOString(), text: new Intl.DateTimeFormat('uk-UA', {
                    timeZone: 'Europe/Kyiv', day: '2-digit', month: '2-digit', year: 'numeric',
                    hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
                }).format(date)
            };
        },
        programs: {EOSELIA: 'єОселя', VOUCHER: 'Ваучер', CERTIFICATE: 'Сертифікат', POSTANOVA: 'Постанова'},
        price: item => `${Number(item.price).toLocaleString('uk-UA', {maximumFractionDigits: 2})} $`,
        pricePerSquareMeter: item => {
            const price = Number(item.price);
            const area = Number(item.area);
            if (!Number.isFinite(price) || !Number.isFinite(area) || price <= 0 || area <= 0) return '';
            return `${Math.round(price / area).toLocaleString('uk-UA')} $/м²`;
        },
        escape: value => String(value ?? '').replace(/[&<>"']/g, char => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        }[char]))
    };
})();
