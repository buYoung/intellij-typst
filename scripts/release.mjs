import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { select, input } from '@inquirer/prompts';
import semver from 'semver';
import release, { Config } from 'release-it';
import { InquirerPrompt, ReleaseStopped, requireAnswer } from './release/release-prompts.mjs';
import { publishWasm } from './release/publish-wasm.mjs';

const root = realpathSync(fileURLToPath(new URL('../', import.meta.url)));
const guardPath = fileURLToPath(new URL('./release/release-prompts.mjs', import.meta.url));
const prompt = new InquirerPrompt();
// Apply before Config.init(): snapshot expansion also rewrites Git/npm options.
const interactiveOptions = {
  ci: false,
  'only-version': false,
  'release-version': false,
  changelog: false,
  'dry-run': false,
  snapshot: false,
  preRelease: false
};
const readJSON = file => JSON.parse(readFileSync(file, 'utf8'));
const git = (...args) => execFileSync('git', args, {
  cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']
}).trim();
let headBefore;
let targetDirectory;
let hasReportedReleaseError = false;

// The WASM package manifest owns the distribution version.
function readCurrentVersion(directory) {
  return readJSON(path.join(directory, 'package.json')).version;
}

function checkVersionSource(options, directory) {
  if (!options.npm || options.npm.ignoreVersion) {
    throw new Error('WASM release versioning requires package.json version management.');
  }
  for (const name of ['package.json', 'package-lock.json', 'npm-shrinkwrap.json']) {
    const file = path.join(directory, name);
    if (!existsSync(file)) continue;
    try { git('ls-files', '--error-unmatch', '--', path.relative(root, file)); }
    catch (error) {
      if (error.status !== 1) throw error;
      throw new Error(`Track the version input before releasing: ${path.relative(root, file)}`);
    }
  }
}

async function chooseTarget() {
  const rootPackage = readJSON(path.join(root, 'package.json'));
  const manifestPath = path.join(root, '.release-targets.json');
  const declarations = Array.isArray(rootPackage.workspaces)
    ? rootPackage.workspaces : rootPackage.workspaces?.packages;
  const projects = JSON.parse(execFileSync('pnpm', ['list', '--recursive', '--depth', '-1', '--json'], {
    cwd: root, encoding: 'utf8'
  }));
  // pnpm-workspace.yaml can contain only build settings in a single project.
  // Use actual workspace projects, declarations, or the setup-verified manifest.
  const isMonorepo = projects.some(project => realpathSync(project.path) !== root) ||
    Boolean(declarations?.length) || existsSync(manifestPath);
  if (!isMonorepo) return { name: rootPackage.name || 'project', path: '.', config: true };

  if (!existsSync(manifestPath)) {
    throw new Error('Define verified service apps in .release-targets.json during setup.');
  }
  const { serviceApps } = readJSON(manifestPath);
  if (!Array.isArray(serviceApps) || !serviceApps.length || serviceApps.some(app =>
    typeof app.name !== 'string' || typeof app.path !== 'string' || !app.name || !app.path
  ) || new Set(serviceApps.map(app => app.path)).size !== serviceApps.length) {
    throw new Error('Expected a nonempty list of distinct service-app paths.');
  }
  return requireAnswer(select({
    message: '배포할 대상을 선택하세요:',
    choices: serviceApps.map(app => ({ value: app, name: app.name, description: app.path, disabled: app.isEnabled === false ? '자리표시자: 배포 실행 없음' : false }))
  }), 'service app');
}

async function chooseVersion(currentVersion) {
  const increments = [
    ['patch'], ['minor'], ['prepatch', 'alpha'], ['preminor', 'beta'],
    ['prerelease', semver.prerelease(currentVersion)?.[0] || 'rc'],
    ['major'], ['premajor', 'alpha']
  ];
  const choices = increments.map(([increment, identifier]) => {
    const version = semver.inc(currentVersion, increment, String(identifier || ''));
    return { value: version, name: `${increment}: ${currentVersion} → ${version}` };
  }).filter(option => option.value && semver.gt(option.value, currentVersion));
  const selected = await requireAnswer(select({
    message: `WASM 배포 묶음의 버전 선택 (현재: ${currentVersion}):`,
    choices: [...choices, { value: 'custom', name: '버전 직접 입력' }]
  }), 'version');
  if (selected !== 'custom') return selected;
  const entered = await requireAnswer(input({
    message: `다음 배포 버전 (현재: ${currentVersion}):`,
    validate: value => !semver.valid(value) || value.includes('+') || !semver.gt(value, currentVersion)
      ? `${currentVersion}보다 큰 SemVer를 빌드 메타데이터 없이 입력하세요.` : true
  }), 'version');
  return semver.valid(entered);
}

function reportState() {
  if (!headBefore) return;
  try {
    console.info(`HEAD before: ${headBefore}\nHEAD now: ${git('rev-parse', 'HEAD')}`);
    console.info(`Remaining index/worktree changes:\n${git('status', '--short') || '(clean)'}`);
    if (targetDirectory) {
      console.info(`Version on disk: ${readCurrentVersion(targetDirectory)}`);
    }
    if (prompt.tagName) {
      let tagRef;
      const ref = `refs/tags/${prompt.tagName}`;
      try {
        git('show-ref', '--verify', '--quiet', '--', ref);
        tagRef = git('show-ref', '--verify', '--', ref);
      } catch (error) {
        if (error.status !== 1) throw error;
        tagRef = '(not present locally)';
      }
      console.info(`Local tag ${prompt.tagName}: ${tagRef}`);
    }
    const pushState = prompt.completed.includes('push') ? 'push command completed' :
      prompt.attempted.includes('push') ? 'push attempted; remote state requires inspection' : 'push not attempted';
    console.info(pushState);
  } catch (error) {
    console.warn(`Could not fully inspect remaining state: ${error.message}`);
  }
}

try {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error('An interactive terminal is required; no release work was started.');
  }
  if (process.argv.length > 2) {
    throw new Error('Run pnpm release without arguments; choose the version in the prompt.');
  }
  process.chdir(root);
  // Git commit consumes the whole index, including newly staged files elsewhere.
  if (git('status', '--porcelain', '--untracked-files=no')) {
    throw new Error('Tracked files and the index must be clean across the repository before releasing.');
  }
  headBefore = git('rev-parse', 'HEAD');
  const target = await chooseTarget();
  targetDirectory = realpathSync(path.resolve(root, target.path));
  const relativeTarget = path.relative(root, targetDirectory);
  if (relativeTarget === '..' || relativeTarget.startsWith(`..${path.sep}`) || path.isAbsolute(relativeTarget)) {
    throw new Error('The selected service app must be inside this project.');
  }
  process.chdir(targetDirectory);
  const config = new Config({ config: target.config ?? true, ...interactiveOptions });
  await config.init();
  const options = config.getContext();
  if (!options.git || !options.git.commit || !options.git.tag || !options.git.push) {
    throw new Error('The interactive flow requires git commit, tag, and push to be enabled.');
  }
  if ((options.npm && options.npm.publish !== false) || options.github?.release || options.gitlab?.release) {
    throw new Error('npm and release-it hosted publishing must be disabled; GitHub assets are published after verification.');
  }
  checkVersionSource(options, targetDirectory);
  // With --update, unrelated untracked files stay outside the index. With --all,
  // require pre-existing untracked files in the staging scope to be handled first.
  if (options.git.addUntrackedFiles &&
      git('ls-files', '--others', '--exclude-standard', '--', relativeTarget || '.')) {
    throw new Error('Untracked files would enter the release commit; handle them before releasing.');
  }
  const currentVersion = readCurrentVersion(targetDirectory);
  if (!semver.valid(currentVersion)) throw new Error('The selected version source needs a valid semver.');
  const selectedVersion = await chooseVersion(currentVersion);
  const branch = git('branch', '--show-current');
  const upstream = git('rev-parse', '--abbrev-ref', '@{upstream}');
  if (upstream !== `origin/${branch}`) throw new Error('Release requires origin upstream for the current branch.');
  const tag = `typst-wasm-v${selectedVersion}`;
  try {
    await release({
      ...options,
      config: false,
      extends: false,
      ...interactiveOptions,
      increment: selectedVersion,
      // Preserve interrupted work for inspection without automatic local rollback.
      git: {
        ...options.git,
        requireCleanWorkingDir: false,
        commitMessage: `chore(Typst WASM ${selectedVersion} 배포): ${branch}\n\n1. WASM 배포 묶음 버전 확정`,
        pushRepo: '',
        pushArgs: ['--atomic', '--no-follow-tags', 'origin', `HEAD:refs/heads/${branch}`, `refs/tags/${tag}:refs/tags/${tag}`],
      },
      plugins: {
        [guardPath]: { currentVersion, selectedVersion },
        ...options.plugins
      }
    }, { prompt });
  } catch (error) {
    // release-it 21.0.1 logs API errors before rethrowing them.
    hasReportedReleaseError = true;
    throw error;
  }
  process.chdir(root);
  await publishWasm();
  console.info(`${target.name} ${selectedVersion}: GitHub Releases와 Wiki 게시 완료`);
} catch (error) {
  if (!hasReportedReleaseError) {
    if (error instanceof ReleaseStopped) console.info(error.message);
    else console.error(error.message);
  }
  process.exitCode = 1;
} finally {
  process.chdir(root);
  reportState();
}
