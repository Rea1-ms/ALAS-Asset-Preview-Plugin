'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const { PNG } = require('pngjs');
const { inside } = require('./assets.cjs');
const COMMAND = 'alasAssetPreview.openSource';

async function source(definition, frame) {
  if (!frame?.file || typeof frame.file !== 'string') throw new Error('缺少图片路径');
  const assets = path.resolve(definition.root, 'assets');
  const file = path.resolve(definition.root, frame.file.replaceAll('\\', '/'));
  if (!inside(assets, file) || !file.toLowerCase().endsWith('.png')) throw new Error('图片必须位于当前项目的素材目录');
  const [realAssets, realFile] = await Promise.all([fs.realpath(assets), fs.realpath(file)]);
  if (!inside(realAssets, realFile)) throw new Error('图片链接指向素材目录之外');
  const stat = await fs.stat(realFile);
  if (!stat.isFile() || stat.size > 32_000_000) throw new Error('图片文件无效或过大');
  return realFile;
}

async function crop(definition, frame) {
  const file = await source(definition, frame);
  const input = await fs.readFile(file);
  if (input.length < 33 || !input.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
      || input.toString('ascii', 12, 16) !== 'IHDR') throw new Error('图片格式无效');
  const width = input.readUInt32BE(16), height = input.readUInt32BE(20);
  if (width * height > 16_000_000 || !width || !height) throw new Error('图片尺寸过大或无效');
  const area = frame.area;
  if (!Array.isArray(area) || area.length !== 4 || !area.every(Number.isSafeInteger)) throw new Error('缺少静态识别区域');
  const [left, top, right, bottom] = area;
  if (left < 0 || top < 0 || right > width || bottom > height || right <= left || bottom <= top) throw new Error('识别区域超出图片范围');
  const original = PNG.sync.read(input);
  const result = new PNG({ width: right - left, height: bottom - top });
  for (let row = 0; row < result.height; row++) {
    original.data.copy(result.data, row * result.width * 4,
      ((top + row) * width + left) * 4, ((top + row) * width + right) * 4);
  }
  return { file, width: result.width, height: result.height, png: PNG.sync.write(result) };
}

const escape = value => String(value).replace(/[\\`*_{}[\]()<>#+.!|&]/g, '\\$&');
const commandLink = (definition, frame) => `command:${COMMAND}?${encodeURIComponent(JSON.stringify([
  { file: definition.file, name: definition.name, frame },
]))}`;

async function render(definition, token) {
  let markdown = `**${escape(definition.name)}**\n\n`;
  for (const [index, frame] of definition.frames.slice(0, 16).entries()) {
    if (token?.isCancellationRequested) return null;
    const label = frame.file?.replaceAll('\\', '/').replace(/^\.\//, '') || frame.namespace || '素材';
    markdown += `**${escape(label)}**\n\n`;
    if (frame.problem) { markdown += `${escape(frame.problem)}\n\n`; continue; }
    try {
      const image = await crop(definition, frame);
      const link = commandLink(definition, index);
      const scale = Math.min(3, 420 / image.width, 200 / image.height);
      const width = Math.max(1, Math.round(image.width * scale));
      const height = Math.max(1, Math.round(image.height * scale));
      markdown += `[打开原图](${link})\n\n[<img src="data:image/png;base64,${image.png.toString('base64')}" width="${width}" height="${height}" alt="点击打开原图">](${link})\n\n`;
      markdown += `${image.width} × ${image.height} 像素 · 识别区域 (${frame.area.join(', ')})\n\n`;
    } catch (error) {
      const message = error.code === 'ENOENT' ? '图片文件不存在' : error.message;
      markdown += `预览不可用：${escape(message)}\n\n`;
    }
  }
  if (definition.frames.length > 16) markdown += `仅显示前 16 项，共 ${definition.frames.length} 项。\n`;
  return markdown;
}

module.exports = { COMMAND, source, crop, render, commandLink };
