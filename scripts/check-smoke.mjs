import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requiredPages = ['index.html', 'buy.html', 'sell-your-apartment.html', 'contacts.html'];
const errors = [];
const walk = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const target = path.join(directory, entry.name);
  return entry.isDirectory() && !['.git', 'node_modules'].includes(entry.name) ? walk(target) : [target];
});

for (const page of requiredPages) if (!fs.existsSync(path.join(root, page))) errors.push(`missing required page: ${page}`);
for (const file of walk(root).filter(file => file.endsWith('.html'))) {
  const html = fs.readFileSync(file, 'utf8');
  const relative = path.relative(root, file).replaceAll('\\', '/');
  if ((html.match(/<h1[\s>]/gi) || []).length !== 1) errors.push(`${relative}: expected exactly one h1`);
  if (!/<main[\s>]/i.test(html)) errors.push(`${relative}: missing main landmark`);
  for (const match of html.matchAll(/(?:href|src)=["']([^"'#?]+)["']/gi)) {
    const reference = match[1];
    if (/^(?:https?:|mailto:|tel:|data:)/i.test(reference)) continue;
    const target = reference.startsWith('/') ? path.join(root, reference.slice(1)) : path.resolve(path.dirname(file), reference);
    if (!fs.existsSync(target)) errors.push(`${relative}: missing local target ${reference}`);
  }
}

for (const file of walk(path.join(root, 'js')).filter(file => file.endsWith('.js'))) {
  const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
  if (result.status !== 0) errors.push(`${path.relative(root, file)}: JavaScript syntax error\n${result.stderr.trim()}`);
}

if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`Smoke check passed: ${requiredPages.length} required pages, local references and JavaScript syntax.`);
