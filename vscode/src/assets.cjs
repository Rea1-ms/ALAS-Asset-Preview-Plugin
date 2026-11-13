'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const { parser } = require('@lezer/python');

function children(node) {
  const result = [];
  for (let child = node.firstChild; child; child = child.nextSibling) if (child.name !== 'Comment') result.push(child);
  return result;
}
const textOf = (text, node) => text.slice(node.from, node.to);
const inside = (root, file) => {
  const relative = path.relative(root, file);
  return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
};

async function findRoot(file) {
  for (let directory = path.dirname(file); ; directory = path.dirname(directory)) {
    try {
      const [button, assets] = await Promise.all([
        fs.stat(path.join(directory, 'module/base/button.py')), fs.stat(path.join(directory, 'assets')),
      ]);
      if (button.isFile() && assets.isDirectory()) return directory;
    } catch (error) { if (!['ENOENT', 'ENOTDIR'].includes(error.code)) throw error; }
    if (path.dirname(directory) === directory) return null;
  }
}

function stringValue(text, node) {
  if (!node || node.name !== 'String') return null;
  const match = /^([ru]?)("""|'''|"|')([\s\S]*)\2$/i.exec(textOf(text, node));
  if (!match) return null;
  if (match[1].toLowerCase() === 'r') return match[3];
  const escapes = { n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', v: '\v', a: '\x07', '\\': '\\', '"': '"', "'": "'", '\n': '' };
  return match[3].replace(/\\(u[\da-f]{4}|U[\da-f]{8}|x[\da-f]{2}|[0-7]{1,3}|[\s\S])/gi, (_, escaped) => {
    if (escaped in escapes) return escapes[escaped];
    if (/^[uUx]/.test(escaped)) {
      const point = parseInt(escaped.slice(1), 16);
      return point <= 0x10ffff ? String.fromCodePoint(point) : '\ufffd';
    }
    if (/^[0-7]/.test(escaped)) return String.fromCodePoint(parseInt(escaped, 8));
    return `\\${escaped}`;
  });
}

function unwrap(node) {
  while (node?.name === 'ParenthesizedExpression') node = children(node).find(child => !['(', ')'].includes(child.name));
  return node;
}
function argumentsOf(call, text) {
  const args = children(call).find(child => child.name === 'ArgList');
  const result = new Map();
  if (!args) return result;
  let group = [];
  function consume() {
    if (group.length === 3 && group[0].name === 'VariableName' && group[1].name === 'AssignOp') {
      result.set(textOf(text, group[0]), unwrap(group[2]));
    }
    group = [];
  }
  for (const node of children(args).filter(child => !['(', ')'].includes(child.name))) {
    if (node.name === ',') consume(); else group.push(node);
  }
  consume();
  return result;
}
const callee = (call, text) => call?.name === 'CallExpression' ? textOf(text, call.firstChild) : '';

function framesOf(call, text) {
  const frames = [];
  function collect(namespace, node) {
    node = unwrap(node);
    if (!node) return;
    if (['TupleExpression', 'ArrayExpression'].includes(node.name)) {
      for (const child of children(node).filter(child => !['(', ')', '[', ']', ','].includes(child.name))) collect(namespace, child);
    } else if (node.name === 'None') {
      frames.push({ namespace, file: null, area: null, problem: '此版本没有素材' });
    } else if (callee(node, text) === 'Button') {
      const args = argumentsOf(node, text);
      const file = stringValue(text, args.get('file'));
      const areaNode = unwrap(args.get('area'));
      let area = null;
      if (areaNode && ['TupleExpression', 'ArrayExpression'].includes(areaNode.name)) {
        const numbers = children(areaNode).filter(child => !['(', ')', '[', ']', ','].includes(child.name)).map(child => textOf(text, child));
        if (numbers.length === 4 && numbers.every(number => /^-?\d+$/.test(number))) {
          const parsed = numbers.map(Number);
          if (parsed.every(Number.isSafeInteger)) area = parsed;
        }
      }
      frames.push({ namespace, file, area, problem: file === null ? '无法读取静态图片路径' : area === null ? '无法读取静态识别区域' : null });
    } else {
      frames.push({ namespace, file: null, area: null, problem: '无法读取静态按钮定义' });
    }
  }
  if (callee(call, text) === 'Button') collect('', call);
  else if (callee(call, text) === 'ButtonWrapper') {
    for (const [namespace, value] of argumentsOf(call, text)) if (namespace !== 'name') collect(namespace, value);
  }
  return frames;
}

function importBindings(statement, text) {
  const nodes = children(statement);
  const result = new Map();
  const importIndex = nodes.findIndex(node => node.name === 'import');
  const from = nodes[0]?.name === 'from';
  const module = from ? nodes.slice(1, importIndex).map(node => textOf(text, node)).join('') : null;
  let group = [];
  function consume() {
    if (!group.length || group.some(node => node.name === '*')) { group = []; return; }
    const asIndex = group.findIndex(node => node.name === 'as');
    const imported = (asIndex < 0 ? group : group.slice(0, asIndex)).map(node => textOf(text, node)).join('');
    const local = asIndex < 0 ? (from ? imported : imported.split('.')[0]) : textOf(text, group[asIndex + 1]);
    result.set(local, from ? { kind: 'import', module, name: imported } : { kind: 'module', module: asIndex < 0 ? local : imported });
    group = [];
  }
  for (const node of nodes.slice(importIndex + 1).filter(child => !['(', ')'].includes(child.name))) {
    if (node.name === ',') consume(); else group.push(node);
  }
  consume();
  return result;
}

function scopeBinding(scope, name, text) {
  let result;
  function visit(node) {
    if (node.name === 'ImportStatement') {
      const binding = importBindings(node, text).get(name);
      if (binding) result = binding;
    } else if (node.name === 'AssignStatement') {
      const parts = children(node);
      const operator = parts.findIndex(part => part.name === 'AssignOp');
      const targets = parts.slice(0, operator);
      if (targets.some(target => target.name === 'VariableName' && textOf(text, target) === name)) {
        result = parts.filter(part => part.name === 'AssignOp').length === 1
          ? { kind: 'value', node: unwrap(parts[operator + 1]), from: targets[0].from } : { kind: 'opaque' };
      }
    } else if (['FunctionDefinition', 'ClassDefinition', 'LambdaExpression'].includes(node.name)) {
      if (children(node).some(child => child.name === 'VariableName' && textOf(text, child) === name)) result = { kind: 'opaque' };
    } else {
      for (const child of children(node)) visit(child);
    }
  }
  for (const statement of children(scope)) visit(statement);
  if (scope.name === 'Body' && ['FunctionDefinition', 'LambdaExpression'].includes(scope.parent?.name)) {
    const params = children(scope.parent).find(node => node.name === 'ParamList');
    if (params && children(params).some(node => node.name === 'VariableName' && textOf(text, node) === name)) return { kind: 'opaque' };
  }
  return result;
}

class AssetResolver {
  constructor(readText = file => fs.readFile(file, 'utf8')) { this.readText = readText; this.modules = new Map(); }
  async module(file) {
    file = path.resolve(file);
    if (!this.modules.has(file)) {
      const text = await this.readText(file);
      if (text.length > 2_000_000) throw new Error('资产源码过大');
      this.modules.set(file, { file, text, tree: parser.parse(text), root: await findRoot(file) });
    }
    return this.modules.get(file);
  }
  async moduleFile(context, module, allowNamespace = false) {
    if (!context.root || !/^[.\w]+$/.test(module)) return null;
    const relative = /^\.+/.exec(module)?.[0].length || 0;
    let base = relative ? path.dirname(context.file) : context.root;
    for (let level = 1; level < relative; level++) base = path.dirname(base);
    const candidate = path.join(base, ...module.slice(relative).split('.').filter(Boolean));
    if (!inside(context.root, candidate)) return null;
    const candidates = [candidate + '.py', path.join(candidate, '__init__.py')];
    if (allowNamespace) candidates.push(candidate);
    for (const file of candidates) {
      try {
        const real = await fs.realpath(file);
        const stat = await fs.stat(real);
        if (inside(await fs.realpath(context.root), real) && (stat.isFile() || (allowNamespace && stat.isDirectory()))) return real;
      } catch (error) { if (!['ENOENT', 'ENOTDIR'].includes(error.code)) throw error; }
    }
    return null;
  }
  async resolve(file, offset) {
    const context = await this.module(file);
    if (!context.root) return null;
    let node = context.tree.resolveInner(offset, 1);
    if (!['VariableName', 'PropertyName'].includes(node.name)) node = context.tree.resolveInner(offset, -1);
    if (!['VariableName', 'PropertyName'].includes(node.name)) return null;
    if (node.parent?.name === 'MemberExpression') node = node.parent;
    return this.expression(context, node, new Set(), 0);
  }
  async named(file, name) {
    const context = await this.module(file);
    return this.reference(context, name, context.tree.topNode, new Set(), 0);
  }
  async reference(context, name, at, visited, depth) {
    if (depth >= 16) return null;
    let binding, scope = at;
    for (; scope; scope = scope.parent) {
      if (!['Script', 'Body'].includes(scope.name)) continue;
      binding = scopeBinding(scope, name, context.text);
      if (binding) break;
    }
    if (!binding || binding.kind === 'opaque') return null;
    const key = `${context.file}:${scope.from}:${name}`;
    if (visited.has(key)) return null;
    visited.add(key);
    if (binding.kind === 'module') return { ...binding, context };
    if (binding.kind === 'import') {
      const imported = await this.moduleFile(context, binding.module);
      if (imported) {
        const target = await this.module(imported);
        const resolved = await this.reference(target, binding.name, target.tree.topNode, visited, depth + 1);
        if (resolved) return resolved;
      }
      const submodule = `${binding.module}${binding.module.endsWith('.') ? '' : '.'}${binding.name}`;
      return (await this.moduleFile(context, submodule, true)) ? { kind: 'module', module: submodule, context } : null;
    }
    if (['ButtonWrapper', 'Button'].includes(callee(binding.node, context.text))) {
      const frames = framesOf(binding.node, context.text);
      return frames.length ? { name, root: context.root, file: context.file, offset: binding.from, frames } : null;
    }
    return this.expression(context, binding.node, visited, depth + 1);
  }
  async expression(context, node, visited, depth) {
    node = unwrap(node);
    if (!node || depth >= 16) return null;
    if (node.name === 'VariableName') return this.reference(context, textOf(context.text, node), node, visited, depth);
    if (node.name === 'MemberExpression') {
      const parts = children(node);
      const base = await this.expression(context, parts[0], visited, depth + 1);
      if (base?.kind !== 'module') return null;
      const member = textOf(context.text, parts.at(-1));
      const file = await this.moduleFile(base.context, base.module);
      if (file) {
        const target = await this.module(file);
        const result = await this.reference(target, member, target.tree.topNode, visited, depth + 1);
        if (result) return result;
      }
      const module = `${base.module}.${member}`;
      return (await this.moduleFile(base.context, module, true)) ? { kind: 'module', module, context: base.context } : null;
    }
    return null;
  }
}

module.exports = { AssetResolver, findRoot, inside, framesOf, stringValue };
