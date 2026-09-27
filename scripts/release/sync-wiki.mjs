import { readFileSync, writeFileSync, mkdtempSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { repository, repoRoot, run, capture, tagFor, packageVersion, sha256 } from '../../packages/typst-wasm/scripts/common.mjs';

export async function syncWiki(tag = tagFor(packageVersion())) {
  if (!/^typst-wasm-v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag)) throw new Error('Invalid WASM release tag');
  const release = JSON.parse(capture('gh', ['release', 'view', tag, '--repo', repository, '--json', 'isDraft,url,tagName']));
  if (release.isDraft) throw new Error('Wiki download lists require a published release.');
  const directory = mkdtempSync(path.join(tmpdir(), 'typstninja-wiki-'));
  try {
    run('gh', ['release', 'download', tag, '--repo', repository, '--pattern', 'manifest.json', '--pattern', 'SHA256SUMS', '--dir', directory]);
    const manifestBytes = readFileSync(path.join(directory, 'manifest.json'));
    const checksums = readFileSync(path.join(directory, 'SHA256SUMS'), 'utf8');
    if (!checksums.split('\n').includes(`${sha256(manifestBytes)}  manifest.json`)) throw new Error('Published manifest checksum mismatch');
    const manifest = JSON.parse(manifestBytes);
    if (manifest.schemaVersion !== 1 || manifest.tag !== tag || manifest.repository !== repository) throw new Error('Unexpected published manifest');
    for (const engine of manifest.engines) {
      if (!/^0\.\d+\.\d+$/.test(engine.version) || !/^[a-f0-9]{64}$/.test(engine.sha256) || !Number.isSafeInteger(engine.sizeBytes) || engine.sizeBytes < 1 || !engine.verification?.isModuleLoaded) throw new Error('Invalid published engine metadata');
      if (!checksums.split('\n').includes(`${engine.sha256}  typst-wasm-${engine.version}.zip`)) throw new Error('Published engine checksum mismatch');
      if (engine.url !== `https://github.com/${repository}/releases/download/${tag}/typst-wasm-${engine.version}.zip`) throw new Error('Unexpected asset URL');
    }
    const wiki = path.join(directory, 'wiki');
    const gitAuth = ['-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential'];
    run('git', [...gitAuth, 'clone', '--depth', '1', `https://github.com/${repository}.wiki.git`, wiki]);
    const wikiGit = args => capture('git', args, { cwd: wiki });
    copyFileSync(path.join(repoRoot, 'docs/wiki/Typst-WASM.md'), path.join(wiki, 'Typst-WASM.md'));
    const homePath = path.join(wiki, 'Home.md');
    let home = readFileSync(homePath, 'utf8');
    if (!home.includes('(Typst-WASM)')) home += '\n\n- [Typst WASM 사용법과 지원 범위](Typst-WASM)\n';
    if (!home.includes('(Typst-WASM-Versions)')) home += '\n- [Typst WASM 버전별 다운로드](Typst-WASM-Versions)\n';
    writeFileSync(homePath, home);
    const rows = manifest.engines.map(engine => `| ${engine.version} | [ZIP](${engine.url}) | ${engine.sizeBytes.toLocaleString('en-US')} | \`${engine.sha256}\` |`);
    const page = `# Typst WASM 버전별 다운로드\n\n배포 묶음: **${manifest.releaseVersion}** · [GitHub Release](${release.url})\n\n이 목록은 게시된 manifest를 기준으로 생성한다. 엔진 버전과 배포 묶음 버전은 별개다.\n\n| Typst 엔진 | 다운로드 | ZIP 크기(bytes) | SHA-256 |\n| --- | --- | ---: | --- |\n${rows.join('\n')}\n\n[manifest.json](https://github.com/${repository}/releases/download/${tag}/manifest.json) · [SHA256SUMS](https://github.com/${repository}/releases/download/${tag}/SHA256SUMS)\n\n타깃: \`${manifest.target}\`, wasm-bindgen: \`${manifest.wasmBindgenVersion}\`. WASM 인스턴스 생성과 버전 일치 검증을 통과한 산출물이다. 기능·제약은 [사용법](Typst-WASM)을 따른다.\n`;
    writeFileSync(path.join(wiki, 'Typst-WASM-Versions.md'), page);
    if (!wikiGit(['status', '--porcelain'])) { console.info('Wiki is already current.'); return; }
    const branch = wikiGit(['branch', '--show-current']);
    run('git', ['add', 'Home.md', 'Typst-WASM.md', 'Typst-WASM-Versions.md'], { cwd: wiki });
    run('git', ['commit', '-m', `docs(Typst WASM ${manifest.releaseVersion} 갱신): ${branch}\n\n1. WASM API와 지원 범위 갱신\n2. 게시된 엔진별 다운로드와 체크섬 반영`], { cwd: wiki });
    run('git', [...gitAuth, 'push', 'origin', `HEAD:refs/heads/${branch}`], { cwd: wiki });
    console.info(`Wiki updated: https://github.com/${repository}/wiki/Typst-WASM`);
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const args = process.argv.slice(2);
  if (args.length && (args.length !== 2 || args[0] !== '--tag')) throw new Error('Usage: wiki:sync [--tag typst-wasm-vX.Y.Z]');
  await syncWiki(args[1]);
}
