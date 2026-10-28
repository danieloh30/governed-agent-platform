// Exercise the browser controller with a minimal DOM and controlled ACP/A2A boundaries.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
  children = []; listeners = new Map(); value = ''; disabled = false; _text = '';
  classList = { values: new Set(), add(value) { this.values.add(value); }, remove(value) { this.values.delete(value); } };
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this.children = []; }
  set innerHTML(value) { throw new Error('Agent output must not be assigned as HTML'); }
  append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
  replaceChildren(...children) { this.children = []; this._text = ''; this.append(...children); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); }
  addEventListener(name, handler) { this.listeners.set(name, handler); }
  focus() {}
  async click() { assert.equal(this.disabled, false, 'cannot click a disabled control'); await this.listeners.get('click')?.(); }
}

function fixture() {
  const elements = new Map(), intervals = [], events = new Map();
  const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  const declaredIds = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
  let backendTasks = [], instance;
  const get = name => {
    assert.ok(declaredIds.has(name), `Controller requested missing HTML element ${name}`);
    if (!elements.has(name)) elements.set(name, new Element());
    return elements.get(name);
  };
  class Acp {
    calls = [];
    constructor(callbacks) { this.callbacks = callbacks; instance = this; }
    async connect() {}
    async request(method, params) {
      this.calls.push({ method, params });
      if (method === 'session/new') return { sessionId: 'goose-one' };
      return this.promptResponse();
    }
    promptResponse = async () => ({ stopReason: 'end_turn' });
    notify() {}
    close() { this.callbacks.onClose(); }
  }
  const context = vm.createContext({
    document: { getElementById: get, createElement: () => new Element() },
    window: { addEventListener: (name, handler) => events.set(name, handler) },
    location: { hostname: 'localhost' }, URL, GooseAcpClient: Acp,
    fetch: async () => ({ ok: true, json: async () => ({ url: 'ws://127.0.0.1:3284/acp', token: 'test-only', cwd: '/tmp/workspace' }) }),
    fetchJson: async () => structuredClone(backendTasks),
    setInterval: handler => { intervals.push(handler); return 1; }, clearInterval() {},
    taskCount: 0, pendingCount: 0, completedCount: 0, currentHitlTaskId: null,
    updateStats() {}, addLog() {}, esc: value => value, setSmPhase() {}, setLayerState() {},
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../goose-ui.js'), 'utf8'), context);
  return { get, context, events, intervals, instance: () => instance,
    setTasks(value) { backendTasks = value; },
    update(value) { instance.callbacks.onUpdate({ sessionId: 'goose-one', update: value }); } };
}

test('investigation and continuation are explicit sends around a real pending workflow decision', async () => {
  const ui = fixture();
  await ui.get('goose-connect').click();
  await ui.get('goose-investigate').click();
  assert.match(ui.get('goose-prompt').value, /one simulated \$2500 refund/);
  assert.equal(ui.instance().calls.filter(call => call.method === 'session/prompt').length, 0);
  ui.setTasks([
    { id: 'audit', operation: 'generate-report', state: 'completed' },
    { id: 'refund', operation: 'process-refund', state: 'input-required', riskLevel: 'high', approvalReason: 'Amount above threshold' },
  ]);
  await ui.intervals[0]();
  assert.equal(ui.context.currentHitlTaskId, 'refund');
  assert.equal(ui.get('hitl-operation').textContent, 'process-refund');
  assert.equal(ui.get('goose-investigate').disabled, true, 'do not offer another canned refund request');
  assert.equal(ui.get('goose-continue').disabled, true);
  ui.events.get('workflow-decision')({ detail: { id: 'refund', decision: 'reject' } });
  assert.equal(ui.get('goose-continue').disabled, false);
  await ui.get('goose-continue').click();
  assert.match(ui.get('goose-prompt').value, /poll this same task/);
  assert.match(ui.get('goose-prompt').value, /refund/);
  assert.equal(ui.instance().calls.filter(call => call.method === 'session/prompt').length, 0);
});

test('streams text safely and keeps tool activity separate from the conversation', async () => {
  const ui = fixture(); await ui.get('goose-connect').click();
  ui.instance().promptResponse = async () => {
    ui.update({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: '<img src=x>' } });
    ui.update({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: ' evidence' } });
    ui.update({ sessionUpdate: 'tool_call', toolCallId: 'read-skill', title: 'Read SKILL.md', status: 'completed', rawOutput: '<script>bad()</script>' });
    return { stopReason: 'end_turn' };
  };
  ui.get('goose-prompt').value = 'Investigate'; await ui.get('goose-send').click();
  assert.match(ui.get('goose-transcript').textContent, /<img src=x> evidence/);
  assert.match(ui.get('goose-tools').textContent, /Read SKILL.md/);
  assert.equal(ui.get('goose-send').disabled, false);
});

test('requires a one-time tool permission decision without offering persistent approval', async () => {
  const ui = fixture(); await ui.get('goose-connect').click();
  let outcome;
  ui.instance().callbacks.onPermission({ id: 'p1', params: { sessionId: 'goose-one', toolCall: { title: 'Read skill' }, options: [
    { kind: 'allow_once', optionId: 'once', name: 'Allow once' },
    { kind: 'allow_always', optionId: 'always', name: 'Allow always' },
  ] } }, value => outcome = value);
  assert.equal(outcome, undefined);
  const box = ui.get('goose-permissions').children[0];
  assert.match(box.textContent, /not refund approval/);
  assert.doesNotMatch(box.textContent, /Allow always/);
  await box.children[2].children[0].click();
  assert.equal(outcome.outcome.optionId, 'once');
  assert.equal(ui.get('goose-permissions').children.length, 0);
});

test('lost turn results disable another send until disconnect and do not retry', async () => {
  const ui = fixture(); await ui.get('goose-connect').click();
  ui.instance().promptResponse = async () => { throw new Error('session/prompt timed out'); };
  ui.get('goose-prompt').value = 'Investigate'; await ui.get('goose-send').click();
  assert.equal(ui.get('goose-send').disabled, true);
  assert.equal(ui.get('goose-status').textContent, 'turn outcome uncertain');
  assert.equal(ui.instance().calls.filter(call => call.method === 'session/prompt').length, 1);
  await ui.get('goose-disconnect').click();
  assert.equal(ui.get('goose-connect').disabled, false);
});
