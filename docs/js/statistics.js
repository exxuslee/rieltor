(() => {
    const base = document.querySelector('meta[name="catalog-api"]')?.content ||
        (['localhost', '127.0.0.1'].includes(location.hostname) ? 'http://localhost:8080' : 'https://api.rieltor.dpdns.org');
    const $ = id => document.getElementById(id);
    const number = value => new Intl.NumberFormat('uk-UA').format(value);
    const fields = ['received', 'processed', 'accepted', 'active', 'tiktok', 'threads'];
    let offset = 0, controller;
    function cell(row, value) { const td = document.createElement('td'); td.textContent = value ?? '—'; row.append(td); return td; }
    function empty(body, columns, text) { const row = document.createElement('tr'); cell(row, text).colSpan = columns; body.replaceChildren(row); }
    async function load() {
        controller?.abort();
        const request = new AbortController(); controller = request;
        const period = $('period').value;
        $('refresh').disabled = true; $('previous').disabled = true; $('next').disabled = true;
        $('stats-status').dataset.error = 'false'; $('stats-status').textContent = 'Оновлюємо дані…';
        empty($('channels'), 7, 'Завантаження…'); empty($('publications'), 6, 'Завантаження…'); $('totals').replaceChildren(); $('page-info').textContent = '';
        const timeout = setTimeout(() => request.abort(), 15000);
        try {
            const response = await fetch(`${base}/health?period=${period}&offset=${offset}`, {signal: request.signal, cache: 'no-store', headers: {Accept: 'application/json'}});
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            if (controller !== request) return;
            ['day', 'month', 'total'].forEach(key => { $(`visitors-${key}`).textContent = number(data.visitors[key]); });
            $('channels').replaceChildren();
            const totals = Object.fromEntries(fields.map(key => [key, 0]));
            data.channels.forEach(channel => {
                const row = document.createElement('tr'), name = cell(row, channel.name), id = document.createElement('small');
                id.textContent = channel.chatId; name.append(id);
                fields.forEach(key => { const value = key === 'active' ? channel.active : channel[period][key]; cell(row, number(value)); totals[key] += value; });
                $('channels').append(row);
            });
            if (!data.channels.length) empty($('channels'), 7, 'Канали ще не додані.');
            const total = document.createElement('tr'); cell(total, 'Усього'); fields.forEach(key => cell(total, number(totals[key]))); $('totals').append(total);
            $('period-caption').textContent = `Статистика повідомлень · ${$('period').selectedOptions[0].textContent}`;
            $('publications').replaceChildren();
            data.publications.forEach(publication => {
                const row = document.createElement('tr');
                const platform = document.createElement('span'); platform.className = 'stats-platform'; platform.textContent = publication.platform === 'TIKTOK' ? 'TikTok' : 'Threads'; cell(row, '').append(platform);
                cell(row, data.channels.find(channel => channel.chatId === publication.chatId)?.name || publication.chatId);
                cell(row, publication.listingId); cell(row, publication.adId); cell(row, publication.messageId);
                cell(row, publication.publishedAt == null ? 'Час невідомий' : new Date(publication.publishedAt).toLocaleString('uk-UA', {timeZone: data.timezone}));
                $('publications').append(row);
            });
            if (!data.publications.length) empty($('publications'), 6, 'За цей період підтверджених репостів немає.');
            $('previous').disabled = offset === 0; $('next').disabled = !data.hasMorePublications;
            $('page-info').textContent = data.publications.length ? `${offset + 1}–${offset + data.publications.length}` : '0 репостів';
            $('historical-note').textContent = data.historicalNote;
            $('stats-status').textContent = `Оновлено ${new Date(data.generatedAt).toLocaleString('uk-UA', {timeZone: data.timezone})} · Київ`;
        } catch (error) {
            if (controller !== request) return;
            $('stats-status').dataset.error = 'true'; $('stats-status').textContent = 'Не вдалося отримати статистику. Перевірте з’єднання та натисніть «Оновити дані».';
            empty($('channels'), 7, 'Дані недоступні'); empty($('publications'), 6, 'Дані недоступні');
            ['day', 'month', 'total'].forEach(key => { $(`visitors-${key}`).textContent = '—'; });
        } finally { clearTimeout(timeout); if (controller === request) $('refresh').disabled = false; }
    }
    $('refresh').addEventListener('click', load);
    $('period').addEventListener('change', () => { offset = 0; load(); });
    $('previous').addEventListener('click', () => { offset = Math.max(0, offset - 50); load(); });
    $('next').addEventListener('click', () => { offset += 50; load(); });
    load();
})();
