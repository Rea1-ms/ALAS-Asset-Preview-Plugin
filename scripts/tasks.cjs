'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
let local = {};
const localPath = path.join(root, '.idea/asset-preview.local.json');
if (fs.existsSync(localPath)) local = JSON.parse(fs.readFileSync(localPath, 'utf8'));

function run(executable, args) {
  const result = spawnSync(executable, args, { cwd: root, stdio: 'inherit', env: process.env, windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(executable)} exited with ${result.status}`);
}
const node = script => run(process.execPath, [script]);
function pycharm(test) {
  const ideHome = process.env.ALAS_PREVIEW_IDE_HOME || local.ideHome;
  if (!ideHome) throw new Error('Set ALAS_PREVIEW_IDE_HOME or ideHome in .idea/asset-preview.local.json.');
  const args = ['-NoProfile', '-File', 'pycharm/build.ps1', '-IdeHome', ideHome];
  if (test) args.push('-Test');
  run('pwsh', args);
  if (test) run('pwsh', ['-NoProfile', '-File', 'pycharm/check_in_ide.ps1', '-IdeHome', ideHome]);
}

try {
  switch (process.argv[2]) {
    case 'build': pycharm(false); node('vscode/scripts/package.cjs'); break;
    case 'test':
      run(process.execPath, ['--test', 'vscode/test/assets.test.cjs']);
      pycharm(true);
      node('vscode/scripts/build.cjs');
      node('vscode/test/run-host.cjs');
      break;
    case 'pycharm-build': pycharm(false); break;
    case 'pycharm-test': pycharm(true); break;
    case 'vscode-host': node('vscode/scripts/build.cjs'); node('vscode/test/run-host.cjs'); break;
    default: throw new Error('Unknown task');
  }
} catch (error) { console.error(error.message); process.exitCode = 1; }
