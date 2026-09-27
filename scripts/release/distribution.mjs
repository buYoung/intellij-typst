import { readFileSync } from 'node:fs';
import path from 'node:path';
import { packageRoot, packageVersion, catalog, sourceFiles, sha256, capture, run, tagFor } from '../../packages/typst-wasm/scripts/common.mjs';

export function readDistribution() {
  const version = packageVersion();
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) throw new Error('Invalid distribution version');
  const directory = path.join(packageRoot, 'dist', version);
  const manifest = JSON.parse(readFileSync(path.join(directory, 'manifest.json'), 'utf8'));
  if (manifest.schemaVersion !== 1 || manifest.releaseVersion !== version || manifest.tag !== tagFor(version)) {
    throw new Error('Distribution manifest mismatch. Rebuild with pnpm wasm:build.');
  }
  const actual = manifest.engines.map(item => item.version);
  if (JSON.stringify(actual) !== JSON.stringify(catalog.versions)) throw new Error('Only the complete engine catalog can be published.');
  if (JSON.stringify(sourceFiles()) !== JSON.stringify(manifest.sources)) throw new Error('Sources changed after the build. Rebuild before publishing.');
  const checksums = new Map(readFileSync(path.join(directory, 'SHA256SUMS'), 'utf8').trim().split('\n').map(line => {
    const match = /^([a-f0-9]{64})  (manifest\.json|typst-wasm-\d+\.\d+\.\d+\.zip)$/.exec(line);
    if (!match) throw new Error('Invalid checksum line');
    return [match[2], match[1]];
  }));
  if (checksums.size !== catalog.versions.length + 1) throw new Error('Incomplete checksum list');
  const names = ['manifest.json', 'SHA256SUMS'];
  for (const engine of manifest.engines) {
    if (engine.file !== `typst-wasm-${engine.version}.zip`) throw new Error('Invalid engine filename');
    if (engine.verification?.isModuleLoaded !== true || engine.verification?.hasDiagnostics !== true ||
        engine.verification.compiled?.length !== 4 || engine.verification.compiled.some(item => item.pageCount < 1)) {
      throw new Error(`Incomplete artifact verification for ${engine.version}`);
    }
    const content = readFileSync(path.join(directory, engine.file));
    if (content.length !== engine.sizeBytes || sha256(content) !== engine.sha256 || checksums.get(engine.file) !== engine.sha256) {
      throw new Error(`Artifact checksum mismatch: ${engine.file}`);
    }
    names.push(engine.file);
  }
  if (sha256(readFileSync(path.join(directory, 'manifest.json'))) !== checksums.get('manifest.json')) throw new Error('Manifest checksum mismatch');
  return { directory, manifest, names };
}

export function verifyCommittedSources(manifest) {
  const commit = capture('git', ['rev-parse', 'HEAD']);
  for (const source of manifest.sources) {
    const content = captureBytes(['show', `${commit}:${source.path}`]);
    if (sha256(content) !== source.sha256) throw new Error(`Built source is not in HEAD: ${source.path}`);
  }
  return commit;
}

const captureBytes = args => run('git', args, { stdio: ['ignore', 'pipe', 'pipe'] });
