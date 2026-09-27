import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { repository, run, capture, sha256 } from '../../packages/typst-wasm/scripts/common.mjs';
import { readDistribution, verifyCommittedSources } from './distribution.mjs';
import { syncWiki } from './sync-wiki.mjs';

function existingRelease(tag) {
  try { return JSON.parse(capture('gh', ['api', `repos/${repository}/releases/tags/${tag}`])); }
  catch (error) {
    if (!String(error.stderr).includes('HTTP 404')) throw error;
    // GitHub's tag endpoint omits drafts until the tag exists. The authenticated
    // release list includes these drafts, which must be resumed, not recreated.
    const pages = JSON.parse(capture('gh', ['api', `repos/${repository}/releases`, '--paginate', '--slurp']));
    const drafts = pages.flat().filter(item => item.draft && item.tag_name === tag);
    if (drafts.length > 1) throw new Error(`Multiple drafts use ${tag}; resolve them before publishing.`);
    return drafts[0] ?? null;
  }
}

export async function publishWasm() {
  const { directory, manifest, names } = readDistribution();
  const commit = verifyCommittedSources(manifest);
  run('node', ['packages/typst-wasm/scripts/versions.mjs', '--check']);
  // The release must refer to source already available on GitHub, never a local-only commit.
  capture('gh', ['api', `repos/${repository}/commits/${commit}`, '--jq', '.sha']);
  // Fail before creating a release when the Wiki cannot be reached.
  capture('git', ['-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'ls-remote', `https://github.com/${repository}.wiki.git`, 'HEAD']);
  const temporary = mkdtempSync(path.join(tmpdir(), 'typstninja-release-'));
  try {
    const notes = `Typst WASM 배포 묶음 ${manifest.releaseVersion}\n\n지원 엔진: ${manifest.engines.map(item => item.version).join(', ')}\n\n각 ZIP에는 WASM·ES module·TypeScript 선언·라이선스 고지가 포함됩니다. PDF·SVG와 메모리 파일·폰트·진단 API를 제공합니다.\n\n소스 커밋: ${commit}\n\n[사용법과 제약](https://github.com/${repository}/wiki/Typst-WASM) · [버전별 다운로드](https://github.com/${repository}/wiki/Typst-WASM-Versions)\n\nIntelliJ 플러그인 배포는 포함하지 않습니다.\n`;
    const notesPath = path.join(temporary, 'notes.md');
    writeFileSync(notesPath, notes);
    let release = existingRelease(manifest.tag);
    if (!release) {
      const prerelease = manifest.releaseVersion.includes('-') ? ['--prerelease'] : [];
      run('gh', ['release', 'create', manifest.tag, '--repo', repository, '--target', commit, '--draft', '--latest=false', ...prerelease, '--title', `Typst WASM ${manifest.releaseVersion}`, '--notes-file', notesPath]);
      release = existingRelease(manifest.tag);
      if (!release) throw new Error('Created draft could not be read. Retry publishing after inspecting GitHub Releases.');
    }
    let remoteRef;
    try { remoteRef = JSON.parse(capture('gh', ['api', `repos/${repository}/git/ref/tags/${manifest.tag}`])); }
    catch (error) { if (!release.draft || !String(error.stderr).includes('HTTP 404')) throw error; }
    const targetCommit = remoteRef
      ? remoteRef.object.type === 'tag'
        ? JSON.parse(capture('gh', ['api', `repos/${repository}/git/tags/${remoteRef.object.sha}`])).object.sha
        : remoteRef.object.sha
      : JSON.parse(capture('gh', ['api', `repos/${repository}/commits/${release.target_commitish}`])).sha;
    if (targetCommit !== commit) throw new Error('Existing release tag or draft points to a different commit.');
    if (release.prerelease !== manifest.releaseVersion.includes('-')) throw new Error('Existing release prerelease status does not match the version.');
    if (release.assets.some(asset => !names.includes(asset.name))) throw new Error('Unexpected existing release assets; refusing to modify this release.');
    const missing = names.filter(name => !release.assets.some(asset => asset.name === name));
    if (!release.draft && missing.length) throw new Error('Published releases are immutable; use a new distribution version.');
    if (missing.length) run('gh', ['release', 'upload', manifest.tag, ...missing.map(name => path.join(directory, name)), '--repo', repository]);
    const downloaded = path.join(temporary, 'downloaded');
    run('gh', ['release', 'download', manifest.tag, '--repo', repository, '--dir', downloaded]);
    for (const name of names) {
      if (sha256(readFileSync(path.join(downloaded, name))) !== sha256(readFileSync(path.join(directory, name)))) {
        throw new Error(`Uploaded asset checksum mismatch: ${name}. No publication change was made.`);
      }
    }
    if (release.draft) run('gh', ['release', 'edit', manifest.tag, '--repo', repository, '--draft=false', '--latest=false', '--notes-file', notesPath]);
    console.info(`Published: https://github.com/${repository}/releases/tag/${manifest.tag}`);
    try { await syncWiki(manifest.tag); }
    catch (error) {
      throw new Error(`Release is published; Wiki update failed. Retry: pnpm wiki:sync --tag ${manifest.tag}`, { cause: error });
    }
  } finally { rmSync(temporary, { recursive: true, force: true }); }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv.length > 2) throw new Error('wasm:publish takes no arguments; it publishes the current built distribution.');
  await publishWasm();
}
