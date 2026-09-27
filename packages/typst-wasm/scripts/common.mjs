import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const packageRoot = fileURLToPath(new URL('../', import.meta.url));
export const repoRoot = path.resolve(packageRoot, '../..');
export const repository = 'buYoung/intellij-typst';
export const catalog = JSON.parse(readFileSync(path.join(packageRoot, 'versions.json'), 'utf8'));
export const packageVersion = () => JSON.parse(readFileSync(path.join(packageRoot, 'package.json'), 'utf8')).version;
export const sha256 = data => createHash('sha256').update(data).digest('hex');
export const tagFor = version => `typst-wasm-v${version}`;
export const run = (command, args, options = {}) => execFileSync(command, args, {
  cwd: repoRoot, stdio: 'inherit', ...options,
});
export const capture = (command, args, options = {}) => run(command, args, {
  encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...options,
}).trim();

export function sourceFiles() {
  const collect = directory => readdirSync(directory, { withFileTypes: true })
    .flatMap(entry => entry.isDirectory() ? collect(path.join(directory, entry.name)) : [path.join(directory, entry.name)]);
  const files = ['package.json', 'versions.json', 'build.rs', 'README.md', 'about.toml', 'about.hbs', '.release-it.json']
    .map(name => path.join(packageRoot, name));
  files.push(...collect(path.join(packageRoot, 'src')), ...collect(path.join(packageRoot, 'scripts')));
  for (const version of catalog.versions) {
    files.push(...['Cargo.toml', 'Cargo.lock'].map(name => path.join(packageRoot, 'engines', version, name)));
  }
  files.push(...['LICENSE', 'docs/wiki/Typst-WASM.md', 'scripts/release.mjs', 'package.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', '.release-targets.json'].map(file => path.join(repoRoot, file)));
  files.push(...collect(path.join(repoRoot, 'scripts/release')));
  files.push(...['samples/verify/00-markup.typ', 'samples/verify/04-math.typ', 'samples/verify-invalid.typ'].map(file => path.join(repoRoot, file)));
  return files.sort().map(file => ({ path: path.relative(repoRoot, file).split(path.sep).join('/'), sha256: sha256(readFileSync(file)) }));
}
