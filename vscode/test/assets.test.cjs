'use strict';
const { test, before } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { AssetResolver, findRoot } = require('../src/assets.cjs');
const { crop, source, render, COMMAND } = require('../src/preview.cjs');
const { PNG } = require('pngjs');
const { fixture } = require('./fixtures.cjs');
let sample;
before(async () => { sample = await fixture(); });

test('declarations preserve original directories, all frames, None, and area rather than search', async () => {
  const asset = await new AssetResolver().named(sample.wrapper, 'MAP_EXIT');
  assert.equal(asset.root, sample.root);
  assert.equal(asset.frames.length, 4);
  assert.deepEqual(asset.frames[0].area, [1, 1, 4, 3]);
  assert.equal(asset.frames[1].file, './assets/share/map/MAP_EXIT.2.png');
  assert.equal(asset.frames[2].namespace, 'cn');
  assert.equal(asset.frames[2].problem, '此版本没有素材');
  assert.equal(asset.frames[3].namespace, 'jp');
  const direct = await new AssetResolver().named(sample.wrapper, 'DIRECT');
  assert.equal(direct.frames.length, 1);
});

test('import and assignment aliases resolve at real usage offsets', async () => {
  const asset = await new AssetResolver().resolve(sample.consumer, sample.consumerText.indexOf('result = LOCAL') + 11);
  assert.equal(asset.name, 'MAP_EXIT');
  assert.equal(asset.file, sample.wrapper);
});

test('relative imports, reexports, and module-qualified usages locate their actual declaration', async () => {
  for (const code of [
    'from .assets.assets_map import MAP_EXIT as LOCAL\nresult = LOCAL\n',
    'from tasks.map.assets import MAP_EXIT as LOCAL\nresult = LOCAL\n',
    'from tasks.map.assets import assets_map as generated\nresult = generated.MAP_EXIT\n',
    'import tasks.map.assets.assets_map as generated\nresult = generated.MAP_EXIT\n',
    'import tasks.map.assets.assets_map\nresult = tasks.map.assets.assets_map.MAP_EXIT\n',
  ]) {
    const file = await sample.write('tasks/map/import_probe.py', code);
    const asset = await new AssetResolver().resolve(file, code.lastIndexOf(code.includes('result = LOCAL') ? 'LOCAL' : 'MAP_EXIT') + 2);
    assert.equal(asset?.file, sample.wrapper, code);
  }
});

test('ordinary variables, local shadows, parameters, comments, and cycles do not receive asset docs', async () => {
  const resolver = new AssetResolver();
  assert.equal(await resolver.named(sample.wrapper, 'ordinary'), null);
  assert.equal(await resolver.named(sample.wrapper, 'CYCLE1'), null);
  assert.equal(await resolver.resolve(sample.wrapper, sample.wrapperText.indexOf('not-an-asset') + 2), null);
  for (const offset of [sample.consumerText.indexOf('return EXIT') + 9, sample.consumerText.lastIndexOf('return EXIT') + 9]) {
    assert.equal(await new AssetResolver().resolve(sample.consumer, offset), null);
  }
});

test('two repositories with duplicate names keep their own source images', async () => {
  const other = await fixture();
  const asset = await new AssetResolver().named(other.wrapper, 'MAP_EXIT');
  assert.equal(await findRoot(other.wrapper), other.root);
  assert.equal(await source(asset, asset.frames[0]), path.join(other.root, 'assets/share/map/MAP_EXIT.png'));
  assert.notEqual(asset.root, sample.root);
});

test('embedded crops retain dimensions and exact source pixels, with linked images and source actions', async () => {
  const asset = await new AssetResolver().named(sample.wrapper, 'MAP_EXIT');
  const image = await crop(asset, asset.frames[0]);
  assert.equal(image.width, 3);
  assert.equal(image.height, 2);
  const decoded = PNG.sync.read(image.png);
  assert.deepEqual([...decoded.data.subarray(0, 4)], [20, 30, 73, 255]);
  const markdown = await render(asset);
  assert.equal((markdown.match(/data:image\/png;base64,/g) || []).length, 3);
  assert.equal((markdown.match(/打开原图\]/g) || []).length, 3);
  assert.ok(markdown.includes('此版本没有素材'));
  assert.ok(markdown.includes('assets/share/map/MAP\\_EXIT\\.png'));
  const links = [...markdown.matchAll(/command:alasAssetPreview\.openSource\?([^\s)]+)/g)];
  assert.equal(links.length, 6);
  assert.deepEqual(JSON.parse(decodeURIComponent(links[0][1])), [{ file: sample.wrapper, name: 'MAP_EXIT', frame: 0 }]);
  assert.equal(COMMAND, 'alasAssetPreview.openSource');
});

test('invalid paths, missing images, corrupt PNGs, and invalid areas are rejected', async () => {
  const asset = await new AssetResolver().named(sample.wrapper, 'MAP_EXIT');
  for (const file of ['../outside.png', './module/base/button.py', './assets/../module/base/button.py', './assets/missing.png']) {
    await assert.rejects(source(asset, { ...asset.frames[0], file }));
  }
  for (const area of [[-1, 0, 2, 2], [0, 0, 100, 100], [1, 1, 1, 3], null]) {
    await assert.rejects(crop(asset, { ...asset.frames[0], area }));
  }
  await sample.write('assets/share/corrupt.png', Buffer.from('not a PNG'));
  await assert.rejects(crop(asset, { ...asset.frames[0], file: './assets/share/corrupt.png' }));
});

test('unsaved source changes are read through the supplied document loader', async () => {
  const resolver = new AssetResolver(async file => file === sample.wrapper
    ? sample.wrapperText.replace('area=((1, 1, 4, 3))', 'area=(0, 0, 2, 2)') : fs.readFile(file, 'utf8'));
  const asset = await resolver.named(sample.wrapper, 'MAP_EXIT');
  assert.deepEqual(asset.frames[0].area, [0, 0, 2, 2]);
});

test('dynamic file expressions are reported without executing project code', async () => {
  const file = await sample.write('dynamic.py', "DYNAMIC = Button(file=f'./assets/{danger()}.png', area=(0, 0, 2, 2))\n");
  const asset = await new AssetResolver().named(file, 'DYNAMIC');
  assert.equal(asset.frames[0].file, null);
  assert.equal(asset.frames[0].problem, '无法读取静态图片路径');
});
