import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const htmlFiles = fs.readdirSync(root).filter(file => file.endsWith('.html'))
  .map(file => path.join(root, file))
  .concat(fs.readdirSync(path.join(root, 'properties')).filter(file => file.endsWith('.html')).map(file => path.join(root, 'properties', file)));
const errors = [];

for (const file of htmlFiles) {
  const relative = path.relative(root, file).replaceAll('\\', '/');
  const html = fs.readFileSync(file, 'utf8');
  const indexable = !/<meta\s+name="robots"\s+content="[^"]*noindex/i.test(html);
  for (const [label, pattern] of [
    ['title', /<title>[^<]+<\/title>/i],
    ['description', /<meta\s+name="description"\s+content="[^"]+"/i],
    ['h1', /<h1[\s>]/i]
  ]) if (!pattern.test(html)) errors.push(`${relative}: missing ${label}`);
  if (indexable && !/<link\s+rel="canonical"\s+href="https:\/\/rieltor\.dpdns\.org\/[^"]*"/i.test(html)) errors.push(`${relative}: missing HTTPS canonical`);

  for (const match of html.matchAll(/<script\s+type="application\/ld\+json">([\s\S]*?)<\/script>/gi)) {
    try { JSON.parse(match[1]); } catch (error) { errors.push(`${relative}: invalid JSON-LD (${error.message})`); }
  }

  for (const match of html.matchAll(/(?:href|src)="(\/[^"]+)"/gi)) {
    const local = match[1].split(/[?#]/)[0];
    if (local === '/') continue;
    const target = path.join(root, decodeURIComponent(local.slice(1)));
    if (!fs.existsSync(target)) errors.push(`${relative}: missing local target ${local}`);
  }
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
