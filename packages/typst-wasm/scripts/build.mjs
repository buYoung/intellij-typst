import { mkdirSync, copyFileSync, writeFileSync, readFileSync, rmSync, renameSync, mkdtempSync } from 'node:fs';
import path from 'node:path';
import { catalog, packageRoot, repoRoot, packageVersion, sourceFiles, sha256, run, capture, repository, tagFor } from './common.mjs';

const args = process.argv.slice(2);
const hasEngine = args.length === 2 && args[0] === '--engine' && catalog.versions.includes(args[1]);
if (args.length && !hasEngine) throw new Error('Usage: build.mjs [--engine <catalog version>]');
const versions = hasEngine ? [args[1]] : catalog.versions;
const releaseVersion = packageVersion();
const tools = path.join(packageRoot, '.tools/bin');
const executable = name => path.join(tools, `${name}${process.platform === 'win32' ? '.exe' : ''}`);
const env = { ...process.env, PATH: `${tools}${path.delimiter}${process.env.PATH}` };
if (capture(executable('wasm-bindgen'), ['--version']) !== `wasm-bindgen ${catalog.wasmBindgenVersion}`) {
  throw new Error('wasm-bindgen version mismatch. Run pnpm wasm:setup.');
}
const rustVersion = capture('rustc', ['--version']);
if (!rustVersion.startsWith('rustc 1.98.1 ')) throw new Error('Release builds require Rust 1.98.1.');
if (!capture('cargo', ['about', '--version'], { env }).includes('0.9.1')) throw new Error('Run pnpm wasm:setup for cargo-about 0.9.1.');
const dist = path.join(packageRoot, 'dist');
mkdirSync(dist, { recursive: true });
const lock = path.join(dist, '.build-lock');
mkdirSync(lock);
const staging = mkdtempSync(path.join(dist, '.build-'));
try {
  const manifest = {
    schemaVersion: 1,
    apiVersion: 1,
    releaseVersion,
    tag: tagFor(releaseVersion),
    repository,
    target: 'wasm32-unknown-unknown',
    wasmBindgenVersion: catalog.wasmBindgenVersion,
    rustVersion,
    sources: sourceFiles(),
    engines: [],
  };
  for (const version of versions) {
    const engineManifest = path.join(packageRoot, 'engines', version, 'Cargo.toml');
    const target = path.join(packageRoot, 'target');
    run('cargo', ['build', '--release', '--locked', '--target', manifest.target, '--manifest-path', engineManifest, '--target-dir', target]);
    const engineOutput = path.join(staging, version);
    mkdirSync(engineOutput);
    // Distinct library names keep cached outputs from different engines apart.
    const libraryName = `typstninja_wasm_v${version.replaceAll('.', '_')}`;
    run(executable('wasm-bindgen'), [path.join(target, manifest.target, 'release', `${libraryName}.wasm`), '--target', 'web', '--out-name', 'typst_wasm', '--out-dir', engineOutput]);
    for (const file of ['index.js', 'index.d.ts']) copyFileSync(path.join(packageRoot, 'src', file), path.join(engineOutput, file));
    for (const [source, name] of [[path.join(repoRoot, 'LICENSE'), 'LICENSE'], [path.join(packageRoot, 'README.md'), 'README.md']]) {
      copyFileSync(source, path.join(engineOutput, name));
    }
    writeFileSync(path.join(engineOutput, 'package.json'), JSON.stringify({type:'module',version,typstVersion:version,distributionVersion:releaseVersion,exports:{'.':{types:'./index.d.ts',import:'./index.js'},'./wasm':'./typst_wasm_bg.wasm'}}, null, 2)+'\n');
    const metadata = JSON.parse(capture('cargo', ['metadata', '--locked', '--format-version', '1', '--manifest-path', engineManifest, '--filter-platform', manifest.target], { maxBuffer: 16 * 1024 * 1024 }));
    const typst = metadata.packages.filter(item => item.name === 'typst');
    if (typst.length !== 1 || typst[0].version !== version) throw new Error(`Unexpected Typst dependency for ${version}`);
    const mismatched = metadata.packages.filter(item => item.name.startsWith('typst-') && item.version !== version);
    if (mismatched.length) throw new Error(`Mixed Typst component versions: ${mismatched.map(item => `${item.name}@${item.version}`).join(', ')}`);
    const assets = metadata.packages.find(item => item.name === 'typst-assets' && item.version === version);
    if (!assets) throw new Error(`Missing font provenance for ${version}`);
    copyFileSync(path.join(path.dirname(assets.manifest_path), 'NOTICE'), path.join(engineOutput, 'FONT-NOTICES.txt'));
    run('cargo', ['about', 'generate', path.join(packageRoot, 'about.hbs'), '--manifest-path', engineManifest, '--config', path.join(packageRoot, 'about.toml'), '--locked', '--fail', '--output-file', path.join(engineOutput, 'THIRD-PARTY-NOTICES.txt')], { env });
    const bytes = readFileSync(path.join(engineOutput, 'typst_wasm_bg.wasm'));
    const verification = JSON.parse(capture('node', [path.join(packageRoot, 'scripts/verify-engine.mjs'), engineOutput, version]));
    const file = `typst-wasm-${version}.zip`;
    run('python3', [path.join(packageRoot, 'scripts/archive.py'), engineOutput, path.join(staging, file)]);
    const zip = readFileSync(path.join(staging, file));
    manifest.engines.push({ version, file, sizeBytes: zip.length, sha256: sha256(zip), wasmSizeBytes: bytes.length, wasmSha256: sha256(bytes), verification, url: `https://github.com/${repository}/releases/download/${manifest.tag}/${file}` });
    console.info(`Verified and packaged Typst ${version}: ${zip.length} bytes`);
  }
  writeFileSync(path.join(staging, 'manifest.json'), JSON.stringify(manifest, null, 2)+'\n');
  const checksums = [...manifest.engines.map(item => `${item.sha256}  ${item.file}`), `${sha256(readFileSync(path.join(staging, 'manifest.json')))}  manifest.json`];
  writeFileSync(path.join(staging, 'SHA256SUMS'), checksums.join('\n')+'\n');
  const output = path.join(dist, hasEngine ? `partial-${versions[0]}` : releaseVersion);
  rmSync(output, { recursive: true, force: true });
  renameSync(staging, output);
  console.info(`Distribution ready: ${output}`);
} finally {
  rmSync(lock, { recursive: true, force: true });
  rmSync(staging, { recursive: true, force: true });
}
