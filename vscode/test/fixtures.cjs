'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { PNG } = require('pngjs');

async function write(root, relative, contents) {
  const file = path.join(root, relative);
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, contents);
  return file;
}

async function fixture(parent = path.resolve(__dirname, '../../.cache/vscode-tests')) {
  await fs.mkdir(parent, { recursive: true });
  const root = await fs.mkdtemp(path.join(parent, 'alas-'));
  await write(root, 'module/base/button.py', "raise RuntimeError('Preview must never execute Python')\n");
  const png = new PNG({ width: 8, height: 6 });
  for (let y = 0; y < 6; y++) for (let x = 0; x < 8; x++) {
    png.data.set([x * 20, y * 30, 73, 255], (y * 8 + x) * 4);
  }
  for (const file of ['assets/share/map/MAP_EXIT.png', 'assets/share/map/MAP_EXIT.2.png', 'assets/jp/map/MAP_EXIT.png']) {
    await write(root, file, PNG.sync.write(png));
  }
  const wrapperText = `from module.base.button import Button, ButtonWrapper
# A comment mentioning Button(file='not-an-asset') must not be parsed.
MAP_EXIT = ButtonWrapper(
    name='MAP_EXIT',
    share=(
        Button(file='./assets/share/map/MAP_EXIT.png', area=((1, 1, 4, 3)), search=(0, 0, 8, 6)),
        Button(file='./assets/share/map/MAP_EXIT.2.png', area=(2, 2, 6, 5)),
    ),
    cn=None,
    jp=Button(file=r'./assets/jp/map/MAP_EXIT.png', area=(1, 1, 4, 3)),
)
DIRECT = Button(file='./assets/share/map/MAP_EXIT.png', area=(1, 1, 4, 3))
ordinary = 42
CYCLE1 = CYCLE2
CYCLE2 = CYCLE1
`;
  const wrapper = await write(root, 'tasks/map/assets/assets_map.py', wrapperText);
  await write(root, 'tasks/map/assets/__init__.py', 'from .assets_map import MAP_EXIT\n');
  const consumerText = `from tasks.map.assets.assets_map import (MAP_EXIT as EXIT, DIRECT)
LOCAL = EXIT
result = LOCAL
def shadow(EXIT):
    return EXIT
def local_shadow():
    EXIT = 42
    return EXIT
`;
  const consumer = await write(root, 'tasks/map/consumer.py', consumerText);
  return { root, wrapper, wrapperText, consumer, consumerText, write: (file, text) => write(root, file, text) };
}
module.exports = { fixture };
