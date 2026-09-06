const assert = require('node:assert/strict');
const vm = require('node:vm');
const scripts = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));

function element(tag, text = '', attrs = {}, children = []) {
  const el = {
    nodeType: 1, tagName: tag, textContent: text, children,
    childNodes: [...(text ? [{nodeType: 3, nodeValue: text}] : []), ...children],
    isConnected: true, clicks: 0, value: attrs.value ?? '',
    getAttribute: n => attrs[n] ?? null,
    hasAttribute: n => n in attrs,
    getBoundingClientRect: () => ({width: 100, height: 20}),
    click() { this.clicks++; },
    closest(selector) {
      if (selector === '[') throw Error('invalid selector');
      if (selector === '.secret' && attrs.class === 'secret') return this;
      return this.parent?.closest(selector) ?? null;
    },
  };
  children.forEach(child => { child.parent = el; });
  return el;
}
function page(...children) {
  return vm.createContext({
    window: {getComputedStyle: () => ({display: 'block', visibility: 'visible'})},
    document: {
      body: element('BODY', '', {}, children), title: 'test',
      querySelector: selector => { if (selector === '[') throw Error('invalid selector'); return null; },
      getElementById: () => null,
    },
    location: {href: 'https://example.test'},
  });
}
function run(name, context, ref) {
  return vm.runInContext(ref ? scripts[name].replaceAll('REF', ref) : scripts[name], context);
}

const original = element('BUTTON', 'View details');
const oldPage = page(original);
const oldRef = run('snapshot', oldPage).nodes[0].ref;
assert.equal(run('nextSnapshot', oldPage).nodes[0].ref, oldRef, 'same node keeps its handle');
assert.equal(run('click', oldPage, oldRef).status, 'ok');
assert.equal(original.clicks, 1, 'the guarded expression actually clicks');

const danger = element('BUTTON', 'Delete account');
const nextPage = page(danger);
assert.equal(run('status', nextPage, oldRef), 'no-snapshot');
const nextRef = run('nextSnapshot', nextPage).nodes[0].ref;
assert.notEqual(nextRef, oldRef, 'two documents cannot reuse e1');
assert.equal(run('click', nextPage, oldRef).status, 'unknown');
assert.equal(danger.clicks, 0, 'stale handle never clicks the replacement');

original.isConnected = false;
assert.equal(run('resolve', oldPage, oldRef), null);
assert.equal(run('click', oldPage, oldRef).status, 'detached');
assert.equal(original.clicks, 1);
const replacement = element('BUTTON', 'Replacement');
oldPage.document.body.children = [replacement];
assert.notEqual(run('nextSnapshot', oldPage).nodes[0].ref, oldRef, 'removed IDs are never recycled');

// A Document replacement can retain the Window; checking only the global is insufficient.
nextPage.document = page(element('BUTTON', 'New document')).document;
assert.equal(run('status', nextPage, nextRef), 'no-snapshot');
assert.notEqual(run('snapshot', nextPage).nodes[0].ref, nextRef);

const secretControls = [
  element('INPUT', '', {type: 'password', value: 'password-secret'}),
  element('INPUT', '', {autocomplete: 'section-login one-time-code', value: 'otp-secret'}),
  element('INPUT', '', {autocomplete: 'cc-number', value: 'card-secret'}),
  element('INPUT', '', {autocomplete: 'new-password', value: 'new-secret'}),
  element('TEXTAREA', 'textarea-secret', {class: 'secret', value: 'textarea-secret'}),
];
const redactedLink = element('A', 'link-secret', {href: '/link-secret'}, []);
const group = element('DIV', '', {class: 'secret'}, [redactedLink]);
const ancestor = element('BUTTON', 'Public ', {}, [element('SPAN', 'nested-secret', {class: 'secret'})]);
const ordinary = element('INPUT', '', {value: ' abc\ndefghijklmnop'});
const snapshot = run('redactedSnapshot', page(...secretControls, group, ancestor, ordinary));
const encoded = JSON.stringify(snapshot);
for (const secret of ['password-secret', 'otp-secret', 'card-secret', 'new-secret', 'textarea-secret', 'link-secret', 'nested-secret']) {
  assert.equal(encoded.includes(secret), false, `${secret} leaked through snapshot fields`);
}
for (const control of snapshot.nodes.slice(0, 5)) {
  assert.equal(control.redacted, true);
  assert.equal(control.value, '[redacted]');
}
assert.equal(snapshot.nodes.at(-1).value, ' abc\ndef…', 'ordinary values retain whitespace and obey the budget');
assert.equal(run('invalidSnapshot', page(ordinary)).error, 'Invalid snapshot redaction selector');

const defaults = run('snapshot', page(element('INPUT', '', {value: 'a'.repeat(1000)})));
assert.equal(defaults.nodes[0].value.length, 201);
assert.equal(run('snapshot', page(secretControls[0])).nodes[0].value, '[redacted]');
console.log('snapshot safety regressions passed');
