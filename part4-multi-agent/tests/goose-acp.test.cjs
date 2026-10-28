const { test } = require('node:test');
const assert = require('node:assert/strict');
const { GooseAcpClient } = require('../goose-acp.js');

class Socket {
  readyState = 1;
  sent = [];
  send(value) { this.sent.push(JSON.parse(value)); }
  close() { this.readyState = 3; this.onclose?.(); }
  receive(value) { this.onmessage({ data: JSON.stringify(value) }); }
}
function fixture(options = {}) {
  const socket = new Socket();
  const client = new GooseAcpClient({ ...options, socketFactory: () => socket });
  return { socket, client };
}
async function connected(options) {
  const fixtureValue = fixture(options);
  const { client, socket } = fixtureValue;
  const connecting = client.connect('ws://127.0.0.1/acp');
  socket.onopen();
  await Promise.resolve();
  socket.receive({ id: 1, result: { protocolVersion: 1 } });
  await connecting;
  return fixtureValue;
}

test('initializes real ACP capabilities without filesystem or terminal delegation', async () => {
  const { client, socket } = await connected();
  assert.equal(socket.sent[0].method, 'initialize');
  assert.deepEqual(socket.sent[0].params.clientCapabilities, {});
  client.close();
});

test('routes interleaved streamed updates and the matching response', async () => {
  const updates = [];
  const { client, socket } = await connected({ onUpdate: value => updates.push(value) });
  const result = client.request('session/prompt', { sessionId: 'one' });
  const update = { sessionId: 'one', update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Evidence' } } };
  socket.receive({ method: 'session/update', params: update });
  socket.receive({ id: 2, result: { stopReason: 'end_turn' } });
  assert.deepEqual(updates, [update]);
  assert.equal((await result).stopReason, 'end_turn');
  client.close();
});

test('permission requests wait for an explicit choice and reply with its option ID', async () => {
  let choose;
  const { client, socket } = await connected({ onPermission: (request, reply) => { choose = reply; } });
  socket.receive({ id: 'permission-1', method: 'session/request_permission', params: { sessionId: 'one', options: [] } });
  assert.equal(socket.sent.length, 1, 'must not auto-approve');
  choose({ outcome: { outcome: 'selected', optionId: 'allow-once' } });
  assert.deepEqual(socket.sent[1], { jsonrpc: '2.0', id: 'permission-1', result: { outcome: { outcome: 'selected', optionId: 'allow-once' } } });
  client.close();
});

test('unsupported agent requests receive method-not-found instead of running local commands', async () => {
  const { client, socket } = await connected();
  socket.receive({ id: 12, method: 'terminal/create', params: { command: 'whoami' } });
  assert.equal(socket.sent[1].error.code, -32601);
  client.close();
});

test('disconnect rejects pending calls without retrying a prompt', async () => {
  const { client, socket } = await connected();
  const pending = client.request('session/prompt', { sessionId: 'one' });
  client.close();
  await assert.rejects(pending, /connection closed/);
  assert.equal(socket.sent.filter(value => value.method === 'session/prompt').length, 1);
  assert.equal(client.pending.size, 0);
});

test('a timed out turn is not silently resubmitted', async () => {
  const { client, socket } = await connected();
  await assert.rejects(client.request('session/prompt', {}, 5), /timed out/);
  assert.equal(socket.sent.filter(value => value.method === 'session/prompt').length, 1);
  client.close();
});

test('surfaces server errors including provider setup details', async () => {
  const { client, socket } = await connected();
  const pending = client.request('session/new', {});
  socket.receive({ id: 2, error: { code: -32603, message: 'Provider unavailable', data: 'Configure a model' } });
  await assert.rejects(pending, /Provider unavailable: Configure a model/);
  client.close();
});
