// Optional integration check: real Goose ACP handshake, session creation and one prompt.
// Run with a configured provider; --connect-only avoids creating a model session.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const http = require('node:http');
const crypto = require('node:crypto');
const { GooseAcpClient } = require('./goose-acp.js');

async function main() {
  const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'target/goose-ui.json'), 'utf8'));
  const url = new URL(config.url); url.searchParams.set('token', config.token);
  async function upgrade(origin, authenticated) {
    const target = new URL(url); target.protocol = 'http:';
    if (!authenticated) target.search = '';
    return new Promise((resolve, reject) => {
      const request = http.request(target, { headers: { Connection: 'Upgrade', Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'), Origin: origin } });
      request.on('upgrade', (response, socket) => { socket.destroy(); resolve(response.statusCode); });
      request.on('response', response => { response.resume(); resolve(response.statusCode); });
      request.on('error', reject); request.setTimeout(10000, () => request.destroy(new Error('WebSocket origin check timed out')));
      request.end();
    });
  }
  // Origin must match the port passed to start-goose.sh (the default SPA port is 8889).
  const origin = process.env.GOOSE_SPA_ORIGIN || 'http://localhost:8889';
  assert.equal(await upgrade(origin, false), 401, 'Unauthenticated browser connection must fail');
  assert.equal(await upgrade('https://untrusted.example', true), 403, 'Foreign browser origin must fail');
  assert.equal(await upgrade(origin, true), 101, 'Configured SPA origin must be accepted');
  console.log('PASS Goose authentication and exact SPA browser origin checks');
  let text = '';
  const client = new GooseAcpClient({ onUpdate: event => {
    if (event.update?.sessionUpdate === 'agent_message_chunk') text += event.update.content?.text || '';
  }, onPermission: (_request, reply) => reply({ outcome: { outcome: 'cancelled' } }) });
  try {
    const initialized = await client.connect(url.toString());
    assert.equal(initialized.protocolVersion, 1);
    console.log('PASS real Goose ACP WebSocket initialization');
    if (process.argv.includes('--connect-only')) return;
    const session = await client.request('session/new', { cwd: config.cwd, mcpServers: [] });
    assert.ok(session.sessionId);
    console.log('PASS real Goose session creation');
    await client.request('session/prompt', { sessionId: session.sessionId,
      prompt: [{ type: 'text', text: 'Reply with the text ACP connection works. Do not use any tools.' }] }, 120000);
    assert.ok(text.trim(), 'No streamed assistant text received');
    console.log('PASS real Goose session streams an assistant reply');
  } finally { client.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
