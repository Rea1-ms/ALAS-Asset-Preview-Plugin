'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const vscode = require('vscode');
const { fixture } = require('./fixtures.cjs');

async function run() {
  const sample = await fixture();
  const extension = vscode.extensions.getExtension('Rea1-ms.alas-asset-preview');
  assert.ok(extension, 'The extension is registered in the actual editor host');
  await extension.activate();
  const document = await vscode.workspace.openTextDocument(vscode.Uri.file(sample.consumer));
  await vscode.window.showTextDocument(document);
  const position = document.positionAt(sample.consumerText.indexOf('result = LOCAL') + 11);
  const hovers = await vscode.commands.executeCommand('vscode.executeHoverProvider', document.uri, position);
  const contents = hovers.flatMap(hover => hover.contents).find(content => content.value?.includes('data:image/png;base64,'));
  assert.ok(contents, 'The native hover provider returns cropped images at an imported usage');
  assert.equal(contents.supportHtml, true);
  assert.deepEqual(contents.isTrusted, { enabledCommands: ['alasAssetPreview.openSource'] });
  const link = /command:alasAssetPreview\.openSource\?([^\s)]+)/.exec(contents.value);
  assert.ok(link, 'The hover contains a clickable source image action');
  const request = JSON.parse(decodeURIComponent(link[1]))[0];
  const opened = await vscode.commands.executeCommand('alasAssetPreview.openSource', request);
  assert.equal(opened.fsPath.toLowerCase(), path.join(sample.root, 'assets/share/map/MAP_EXIT.png').toLowerCase());
  const imageTabs = vscode.window.tabGroups.all.flatMap(group => group.tabs).filter(tab => tab.input?.uri?.fsPath === opened.fsPath);
  assert.ok(imageTabs.some(tab => tab.input instanceof vscode.TabInputCustom
    && tab.input.viewType === 'imagePreview.previewEditor'), 'The actual editor opens the source in its built-in image viewer');
  const second = await vscode.commands.executeCommand('alasAssetPreview.openSource', { ...request, frame: 1 });
  assert.ok(second.fsPath.endsWith('MAP_EXIT.2.png'), 'The second frame opens its own image');
  console.log('PASS: VS Code extension-host hover, trusted image actions, and native image tabs');
}
module.exports = { run };
