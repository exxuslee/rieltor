import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const defaultRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const defaultApi = 'https://api.rieltor.dpdns.org/api/listings';
const domain = 'https://rieltor.dpdns.org';
const escapeXml = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

/** Read every public catalog page; a failed page must never produce a partial sitemap. */
export async function catalogIds(api = defaultApi) {
    const ids = new Set();
    const cursors = new Set();
    let cursor = null;
    do {
        const url = new URL(api);
        url.search = '';
        url.searchParams.set('limit', '100');
        if (cursor) url.searchParams.set('cursor', cursor);
        const response = await fetch(url, {signal: AbortSignal.timeout(30_000)});
        if (!response.ok) throw new Error(`Catalog request failed: HTTP ${response.status}`);
        const page = await response.json();
        if (!Array.isArray(page.items) || !(page.nextCursor === null ||
            (typeof page.nextCursor === 'string' && page.nextCursor.length))) {
            throw new Error('Invalid catalog page');
        }
        for (const item of page.items) {
            const id = String(item?.id ?? '');
            if (!/^[1-9]\d*$/.test(id)) throw new Error(`Invalid catalog ID: ${id}`);
            ids.add(id);
        }
        cursor = page.nextCursor;
        if (cursor && cursors.has(cursor)) throw new Error('Catalog returned a repeated cursor');
        if (cursor) cursors.add(cursor);
    } while (cursor);
    return [...ids].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0);
}

export async function updateSitemap({root = defaultRoot, api = process.env.CATALOG_API_URL || defaultApi} = {}) {
    const target = path.join(root, 'sitemap.xml');
    const previous = await fs.readFile(target, 'utf8');
    // Preserve ordinary pages and their real lastmod values; replace only catalog entries.
    const staticEntries = [...previous.matchAll(/<url>[\s\S]*?<\/url>/g)]
        .map(match => match[0]).filter(entry => {
            const loc = entry.match(/<loc>([^<]+)<\/loc>/)?.[1];
            if (!loc || !loc.startsWith(`${domain}/`)) throw new Error('Invalid sitemap URL');
            const url = new URL(loc.replaceAll('&amp;', '&'));
            return url.pathname !== '/property.html' && !url.pathname.startsWith('/properties/');
        });
    if (!staticEntries.length) throw new Error('Sitemap contains no ordinary pages');
    const ids = await catalogIds(api);
    const entries = [...staticEntries, ...ids.map(id =>
        `<url><loc>${escapeXml(`${domain}/property.html?id=${id}`)}</loc></url>`)];
    if (entries.length > 50_000) throw new Error('Sitemap exceeds 50,000 URLs; split it before publishing');
    const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${entries.map(entry => `  ${entry}`).join('\n')}\n</urlset>\n`;
    const temporary = `${target}.tmp`;
    await fs.writeFile(temporary, xml, 'utf8');
    await fs.rename(temporary, target);
    return {properties: ids.length, urls: entries.length};
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    const result = await updateSitemap();
    console.log(`Sitemap updated: ${result.properties} catalog objects, ${result.urls} URLs.`);
}
