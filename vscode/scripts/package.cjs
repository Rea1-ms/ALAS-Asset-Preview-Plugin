'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { createVSIX } = require('@vscode/vsce');

(async () => {
  await require('esbuild').build({
    entryPoints: [path.join(__dirname, '../src/extension.cjs')], outfile: path.join(__dirname, '../dist/extension.cjs'),
    bundle: true, platform: 'node', format: 'cjs', target: 'node20', external: ['vscode'],
  });
  const output = path.resolve(__dirname, '../../dist/vscode');
  await fs.mkdir(output, { recursive: true });
  const version = require('../package.json').version;
  const packagePath = path.join(output, `alas-asset-preview-${version}-${Date.now()}.vsix`);
  await createVSIX({ cwd: path.resolve(__dirname, '..'), packagePath, dependencies: false, allowMissingRepository: false });
  console.log(`Plugin archive: ${packagePath}`);
})().catch(error => { console.error(error); process.exitCode = 1; });
