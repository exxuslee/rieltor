/* Єдиний клієнт каталогу. Дані оголошень зберігаються лише в backend. */
(function () {
    const configured = document.querySelector('meta[name="catalog-api"]')?.content;
    const base = configured || (['localhost', '127.0.0.1'].includes(location.hostname)
        ? 'http://localhost:8080' : 'https://api.rieltor.dpdns.org');
    async function request(path, signal) {
        const response = await fetch(`${base}${path}`, {signal, headers: {Accept: 'application/json'}});
        if (!response.ok) throw new Error(response.status === 404 ? 'Об’єкт більше не доступний.' : 'Не вдалося завантажити оголошення. Спробуйте ще раз.');
        return response.json();
    }
    window.Listings = {
        list: (params = {}, signal) => request(`/api/listings?${new URLSearchParams(params)}`, signal),
        one: (id, signal) => request(`/api/listings/${encodeURIComponent(id)}`, signal),
        types: {apartments: 'APARTMENT', 'new-buildings': 'NEW_BUILD', houses: 'HOUSE', land: 'LAND', commercial: 'COMMERCIAL'},
        programs: {EOSELIA: 'єОселя', VOUCHER: 'Ваучер', CERTIFICATE: 'Сертифікат', POSTANOVA: 'Постанова'},
        price: item => `${Number(item.price).toLocaleString('uk-UA', {maximumFractionDigits: 2})} ${item.currency || ''}${{MONTH: '/міс.', PER_M2: '/м²', PER_SOTKA: '/сотка'}[item.pricePeriod] || ''}`,
        escape: value => String(value ?? '').replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]))
    };
})();
