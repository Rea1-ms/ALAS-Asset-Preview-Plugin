'use strict';
const path = require('node:path');
const fs = require('node:fs/promises');
const { downloadAndUnzipVSCode, runTests } = require('@vscode/test-electron');

(async () => {
  const cache = path.resolve(__dirname, '../../.cache/vscode-host');
  await fs.mkdir(cache, { recursive: true });
  const vscodeExecutablePath = process.env.ALAS_PREVIEW_VSCODE_EXECUTABLE || await downloadAndUnzipVSCode({
    version: '1.96.4', cachePath: path.join(cache, 'runtime'),
  });
  const stamp = Date.now().toString();
  await runTests({
    vscodeExecutablePath,
    extensionDevelopmentPath: path.resolve(__dirname, '..'),
    extensionTestsPath: path.join(__dirname, 'host.cjs'),
    launchArgs: ['--disable-extensions', '--skip-welcome', '--skip-release-notes', '--disable-workspace-trust',
      '--user-data-dir', path.join(cache, stamp, 'user'), '--extensions-dir', path.join(cache, stamp, 'extensions')],
  });
})().catch(error => { console.error(error); process.exitCode = 1; });
