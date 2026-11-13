'use strict';
const path = require('node:path');
require('esbuild').build({
  entryPoints: [path.join(__dirname, '../src/extension.cjs')],
  outfile: path.join(__dirname, '../dist/extension.cjs'),
  bundle: true, platform: 'node', format: 'cjs', target: 'node20', external: ['vscode'],
}).catch(error => { console.error(error); process.exitCode = 1; });
