'use strict';

const vscode = require('vscode');
const fs = require('node:fs/promises');
const path = require('node:path');
const { AssetResolver } = require('./assets.cjs');
const { COMMAND, render, source } = require('./preview.cjs');

async function readText(file) {
  const document = vscode.workspace.textDocuments.find(document => document.uri.scheme === 'file'
    && path.resolve(document.uri.fsPath) === path.resolve(file));
  if (document) return document.getText();
  if ((await fs.stat(file)).size > 2_000_000) throw new Error('资产源码过大');
  return fs.readFile(file, 'utf8');
}

function activate(context) {
  const output = vscode.window.createOutputChannel('ALAS 素材预览');
  context.subscriptions.push(output);
  context.subscriptions.push(vscode.languages.registerHoverProvider({ language: 'python', scheme: 'file' }, {
    async provideHover(document, position, token) {
      try {
        const definition = await new AssetResolver(readText).resolve(document.uri.fsPath, document.offsetAt(position));
        if (!definition?.frames || token.isCancellationRequested) return undefined;
        const rendered = await render(definition, token);
        if (!rendered || token.isCancellationRequested) return undefined;
        const markdown = new vscode.MarkdownString(rendered);
        markdown.supportHtml = true;
        // Only our validated open-image handler may be invoked by these links.
        // Asset text is escaped and never grants general command trust.
        markdown.isTrusted = { enabledCommands: [COMMAND] };
        return new vscode.Hover(markdown, document.getWordRangeAtPosition(position));
      } catch (error) {
        output.appendLine(`预览失败：${error.message}`);
        return undefined;
      }
    },
  }));
  context.subscriptions.push(vscode.commands.registerCommand(COMMAND, async request => {
    try {
      if (!request || typeof request.file !== 'string' || typeof request.name !== 'string'
          || !request.file.endsWith('.py') || !Number.isInteger(request.frame) || request.frame < 0 || request.frame >= 16) {
        throw new Error('原图入口参数无效');
      }
      // Re-read the declaration rather than accepting an arbitrary image path
      // from a command URI or retaining stale frame paths after source edits.
      const definition = await new AssetResolver(readText).named(request.file, request.name);
      if (!definition?.frames?.[request.frame]) throw new Error('素材定义已变化，请重新悬停');
      const file = await source(definition, definition.frames[request.frame]);
      await vscode.commands.executeCommand('vscode.open', vscode.Uri.file(file));
      return vscode.Uri.file(file);
    } catch (error) {
      output.appendLine(`打开原图失败：${error.message}`);
      vscode.window.showWarningMessage(`无法打开原图：${error.code === 'ENOENT' ? '图片文件不存在' : error.message}`);
      return undefined;
    }
  }));
}

module.exports = { activate };
