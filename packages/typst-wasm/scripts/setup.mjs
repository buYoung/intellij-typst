import path from 'node:path';
import { catalog, packageRoot, run } from './common.mjs';

run('rustup', ['target', 'add', 'wasm32-unknown-unknown']);
for (const [name, version] of [['wasm-bindgen-cli', catalog.wasmBindgenVersion], ['cargo-about', '0.9.1']]) {
  const features = name === 'cargo-about' ? ['--features', 'cli'] : [];
  run('cargo', ['install', name, '--version', version, '--locked', ...features, '--root', path.join(packageRoot, '.tools')]);
}
