import { capture, catalog } from './common.mjs';
import semver from 'semver';

if (process.argv.slice(2).join(' ') !== '--check') throw new Error('Usage: versions.mjs --check');
const releases = JSON.parse(capture('gh', ['api', '--paginate', '--slurp', 'repos/typst/typst/releases?per_page=100'])).flat();
const versions = releases.filter(item => !item.draft && !item.prerelease && /^v\d+\.\d+\.\d+$/.test(item.tag_name))
  .map(item => item.tag_name.slice(1)).filter(version => semver.gte(version, catalog.minimum)).sort(semver.compare);
const missing = versions.filter(version => !catalog.versions.includes(version));
const unknown = catalog.versions.filter(version => !versions.includes(version));
if (missing.length || unknown.length) throw new Error(`Update versions.json, exact engine manifests/locks and Wiki. Missing: ${missing.join(', ') || 'none'}; not stable upstream: ${unknown.join(', ') || 'none'}`);
console.info(`Verified ${versions.length} upstream stable versions: ${versions.join(', ')}`);
