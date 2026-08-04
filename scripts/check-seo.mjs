import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const htmlFiles = fs.readdirSync(root).filter(file => file.endsWith('.html'))
    .map(file => path.join(root, file))
    .concat(fs.readdirSync(path.join(root, 'properties')).filter(file => file.endsWith('.html')).map(file => path.join(root, 'properties', file)));
const errors = [];
const requiredTypes = new Map([
    ['index.html', ['WebSite', 'WebPage', 'Person', 'RealEstateAgent', 'Service']],
    ['faq.html', ['FAQPage']],
    ['about.html', ['ProfilePage', 'Person']],
    ['contacts.html', ['ContactPage', 'RealEstateAgent']],
    ['sell-your-apartment.html', ['WebPage', 'Service', 'FAQPage']]
]);

for (const file of htmlFiles) {
    const relative = path.relative(root, file).replaceAll('\\', '/');
    const html = fs.readFileSync(file, 'utf8');
    const indexable = !/<meta\s+name="robots"\s+content="[^"]*noindex/i.test(html);
    if (!/<html\s+lang="uk"/i.test(html)) errors.push(`${relative}: missing Ukrainian document language`);
    for (const [label, pattern] of [
        ['title', /<title>[^<]+<\/title>/i],
        ['description', /<meta\s+name="description"\s+content="[^"]+"/i],
        ['h1', /<h1[\s>]/i]
    ]) if (!pattern.test(html)) errors.push(`${relative}: missing ${label}`);
    if (indexable && !/<link\s+rel="canonical"\s+href="https:\/\/rieltor\.dpdns\.org\/[^"]*"/i.test(html)) errors.push(`${relative}: missing HTTPS canonical`);

    const schemaTypes = new Set();
    for (const match of html.matchAll(/<script\s+type="application\/ld\+json">([\s\S]*?)<\/script>/gi)) {
        try {
            const schema = JSON.parse(match[1]);
            const nodes = schema['@graph'] || [schema];
            for (const node of nodes) {
                const types = Array.isArray(node['@type']) ? node['@type'] : [node['@type']];
                for (const type of types.filter(Boolean)) schemaTypes.add(type);
            }
        } catch (error) {
            errors.push(`${relative}: invalid JSON-LD (${error.message})`);
        }
    }
    for (const type of requiredTypes.get(relative) || []) if (!schemaTypes.has(type)) errors.push(`${relative}: missing ${type} JSON-LD`);

    for (const match of html.matchAll(/(?:href|src)="(\/[^"]+)"/gi)) {
        const local = match[1].split(/[?#]/)[0];
        if (local === '/') continue;
        const target = path.join(root, decodeURIComponent(local.slice(1)));
        if (!fs.existsSync(target)) errors.push(`${relative}: missing local target ${local}`);
    }
}

for (const file of ['index.html', 'about.html', 'contacts.html', 'sell-your-apartment.html', 'faq.html']) {
    const html = fs.readFileSync(path.join(root, file), 'utf8');
    if (!/<link\s+rel="alternate"\s+type="text\/plain"\s+href="\/llms\.txt"/i.test(html)) errors.push(`${file}: missing llms.txt discovery link`);
}

const llms = fs.readFileSync(path.join(root, 'llms.txt'), 'utf8');
for (const expected of ['Ірина Ліннік', 'https://rieltor.dpdns.org/sell-your-apartment.html', '+380 66 372 71 02', 'демонстраційні дані']) {
    if (!llms.includes(expected)) errors.push(`llms.txt: missing ${expected}`);
}
if (!fs.existsSync(path.join(root, 'llms-full.txt'))) errors.push('missing llms-full.txt');

const robots = fs.readFileSync(path.join(root, 'robots.txt'), 'utf8');
for (const agent of ['GPTBot', 'OAI-SearchBot', 'ChatGPT-User', 'ClaudeBot', 'PerplexityBot']) {
    if (!robots.includes(`User-agent: ${agent}`)) errors.push(`robots.txt: missing ${agent}`);
}

const sitemap = fs.readFileSync(path.join(root, 'sitemap.xml'), 'utf8');
const sitemapUrls = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => match[1]);
for (const url of sitemapUrls) {
    if (!url.startsWith('https://rieltor.dpdns.org/')) errors.push(`sitemap: non-HTTPS URL ${url}`);
    const pathname = new URL(url).pathname;
    const target = pathname === '/' ? path.join(root, 'index.html') : path.join(root, pathname.slice(1));
    if (!fs.existsSync(target)) errors.push(`sitemap: missing target ${pathname}`);
}

if (errors.length) {
    console.error(errors.join('\n'));
    process.exit(1);
}
console.log(`SEO check passed: ${htmlFiles.length} HTML files, ${sitemapUrls.length} sitemap URLs.`);
