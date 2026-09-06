const assert = require('node:assert/strict');
const vm = require('node:vm');
const scripts = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));

function element(overrides = {}) {
  return Object.assign({
    nodeType: 1, isConnected: true, clicks: 0, parentElement: null,
    style: {display: 'block', opacity: '1', visibility: 'visible'},
    matches: selector => { assert.equal(selector, ':disabled'); return false; },
    hasAttribute: () => false, getAttribute: () => null,
    getClientRects: () => [{width: 100, height: 30}],
    click() { this.clicks++; },
  }, overrides);
}

function run(kind, nodes, invalid = false) {
  let queries = 0;
  const context = vm.createContext({
    window: {__vitre: {byRef: new Map(nodes.length ? [['e1', nodes[0]]] : [])}},
    getComputedStyle: el => el.style,
    document: {
      querySelectorAll(selector) {
        queries++;
        assert.equal(selector, 'button[data-name="it\'s safe"]');
        if (invalid) throw Error('invalid selector');
        return nodes;
      },
      evaluate(expression, scope, resolver, type) {
        queries++;
        assert.equal(expression, '//button');
        assert.equal(type, 7, 'strict XPath requires every match');
        return {snapshotLength: nodes.length, snapshotItem: i => nodes[i]};
      },
    },
  });
  context.window.__vitre.document = context.document;
  const result = vm.runInContext(scripts[kind], context);
  if (kind !== 'handle') assert.equal(queries, 1, 'target is resolved once');
  return result;
}

for (const kind of ['css', 'xpath', 'handle']) {
  const good = element();
  assert.equal(run(kind, [good]), true);
  assert.equal(good.clicks, 1);
  assert.match(run(kind, []), /No element matched/);

  const cases = [
    [element({isConnected: false}), /removed/],
    [element({nodeType: 2}), /not an element/],
    [element({click: undefined}), /not an element/],
    [element({matches: () => true}), /disabled/],
    [element({inert: true}), /inert/],
    [element({hasAttribute: n => n === 'inert'}), /inert/],
    [element({getAttribute: n => n === 'aria-disabled' ? 'true' : null}), /aria-disabled/],
    [element({hidden: true}), /hidden/],
    [element({style: {display: 'none', opacity: '1', visibility: 'visible'}}), /hidden/],
    [element({style: {display: 'block', opacity: '0', visibility: 'visible'}}), /hidden/],
    [element({style: {display: 'block', opacity: '1', visibility: 'hidden'}}), /hidden/],
    [element({getClientRects: () => []}), /layout box/],
    [element({getClientRects: () => [{width: 0, height: 30}]}), /layout box/],
  ];
  for (const [target, reason] of cases) {
    // Detached handles resolve to null before ClickJs sees them. The engine's enclosing atomic
    // handle guard supplies the richer detached rejection (covered by ActionOutcomeTest).
    assert.match(run(kind, [target]), kind === 'handle' && !target.isConnected ? /No element matched/ : reason);
    assert.equal(target.clicks, 0, 'rejected action must not dispatch');
  }
  for (const parent of [element({inert: true}), element({hidden: true}),
      element({getAttribute: n => n === 'aria-disabled' ? 'true' : null})]) {
    const target = element({parentElement: parent});
    assert.equal(typeof run(kind, [target]), 'string');
    assert.equal(target.clicks, 0);
  }
}

for (const kind of ['css', 'xpath']) {
  const nodes = [element(), element()];
  assert.match(run(kind, nodes), /matched 2 elements/);
  assert.deepEqual(nodes.map(n => n.clicks), [0, 0]);
}
assert.match(run('css', [], true), /locator is invalid/);
