import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {updateSitemap} from './generate-sitemap.mjs';

const original = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://rieltor.dpdns.org/</loc><lastmod>2026-09-18</lastmod></url>
  <url><loc>https://rieltor.dpdns.org/property.html?id=999</loc></url>
</urlset>`;

async function fixture(t, handler) {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'rieltor-sitemap-'));
    await fs.writeFile(path.join(root, 'sitemap.xml'), original);
    const server = http.createServer((request, response) => {
        response.setHeader('Content-Type', 'application/json');
        handler(new URL(request.url, 'http://localhost'), response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(async () => {
        server.closeAllConnections();
        await new Promise(resolve => server.close(resolve));
        await fs.rm(root, {recursive: true, force: true});
    });
    return {root, api: `http://127.0.0.1:${server.address().port}/api/listings`};
}

test('includes every page, deduplicates IDs, removes stale objects and preserves static lastmod', async t => {
    const cursors = [];
    const options = await fixture(t, (url, response) => {
        cursors.push(url.searchParams.get('cursor'));
        assert.equal(url.searchParams.get('limit'), '100');
        response.end(JSON.stringify(url.searchParams.has('cursor')
            ? {items: [{id: '100'}, {id: '101'}], nextCursor: null}
            : {items: Array.from({length: 100}, (_, i) => ({id: String(i + 1)})), nextCursor: 'next-page'}));
    });
    assert.deepEqual(await updateSitemap(options), {properties: 101, urls: 102});
    assert.deepEqual(cursors, [null, 'next-page']);
    const xml = await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8');
    const ids = [...xml.matchAll(/property\.html\?id=(\d+)/g)].map(match => match[1]);
    assert.deepEqual(ids, Array.from({length: 101}, (_, i) => String(i + 1)));
    assert.ok(xml.includes('<lastmod>2026-09-18</lastmod>'));
    assert.ok(!xml.includes('{id}'));
});

test('keeps the old sitemap if a later API page fails', async t => {
    const options = await fixture(t, (url, response) => {
        if (url.searchParams.has('cursor')) {
            response.statusCode = 503;
            response.end('{}');
        } else response.end(JSON.stringify({items: [{id: '1'}], nextCursor: 'next-page'}));
    });
    await assert.rejects(updateSitemap(options), /HTTP 503/);
    assert.equal(await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8'), original);
});

test('rejects a cursor loop instead of hanging or writing an incomplete file', async t => {
    const options = await fixture(t, (url, response) => {
        response.end(JSON.stringify({items: [{id: '1'}], nextCursor: 'same-page'}));
    });
    await assert.rejects(updateSitemap(options), /repeated cursor/);
    assert.equal(await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8'), original);
});

test('rejects malformed catalog IDs', async t => {
    const options = await fixture(t, (url, response) => {
        response.end(JSON.stringify({items: [{id: '{id}'}], nextCursor: null}));
    });
    await assert.rejects(updateSitemap(options), /Invalid catalog ID/);
    assert.equal(await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8'), original);
});

test('rejects malformed pagination responses', async t => {
    const options = await fixture(t, (url, response) => {
        response.end(JSON.stringify({items: [{id: '1'}]}));
    });
    await assert.rejects(updateSitemap(options), /Invalid catalog page/);
    assert.equal(await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8'), original);
});

test('an empty public catalog removes obsolete property entries', async t => {
    const options = await fixture(t, (url, response) => {
        response.end(JSON.stringify({items: [], nextCursor: null}));
    });
    assert.deepEqual(await updateSitemap(options), {properties: 0, urls: 1});
    assert.ok(!(await fs.readFile(path.join(options.root, 'sitemap.xml'), 'utf8')).includes('property.html'));
});
