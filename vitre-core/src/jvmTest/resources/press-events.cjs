const assert = require('node:assert/strict');
const vm = require('node:vm');
const scripts = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));

function dispatch(script, cancelKeydown = false) {
    const events = [];
    let focused = false;
    const element = {
        focus() { focused = true; },
        dispatchEvent(event) {
            assert.ok(focused);
            events.push(event);
            return !(cancelKeydown && event.type === 'keydown');
        },
    };
    const status = vm.runInNewContext(script, {
        document: {
            querySelector(selector) {
                assert.equal(selector, '#q');
                return element;
            },
        },
        KeyboardEvent: class {
            constructor(type, options) {
                Object.assign(this, { charCode: 0 }, options, { type });
            }
        },
    });
    assert.equal(status, 'ok');
    return events;
}

for (const [name, key, code, physical, character] of [
    ['a', 'a', 'KeyA', 65, 97],
    ['A', 'A', 'KeyA', 65, 65],
    ['1', '1', 'Digit1', 49, 49],
    ['!', '!', 'Digit1', 49, 33],
    ['é', 'é', '', 0, 233],
    ['Space', ' ', 'Space', 32, 32],
    ['Enter', 'Enter', 'Enter', 13, 13],
    ['Escape', 'Escape', 'Escape', 27, null],
]) {
    const events = dispatch(scripts[name]);
    assert.deepEqual(events.map(event => event.type), character === null
        ? ['keydown', 'keyup'] : ['keydown', 'keypress', 'keyup'], name);
    for (const event of events) {
        const isPress = event.type === 'keypress';
        assert.equal(event.key, key, name);
        assert.equal(event.code, code, name);
        assert.equal(event.keyCode, isPress ? character : physical, name);
        assert.equal(event.which, isPress ? character : physical, name);
        assert.equal(event.charCode, isPress ? character : 0, name);
        assert.equal(event.bubbles, true, name);
        assert.equal(event.cancelable, true, name);
    }
    assert.deepEqual(dispatch(scripts[name], true).map(event => event.type),
        ['keydown', 'keyup'], name);
}
