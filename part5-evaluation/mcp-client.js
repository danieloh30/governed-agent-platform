/* Session-aware MCP transport for the standalone Part 5 console. */
class McpClient {
  constructor(endpoint) { this.endpoint = endpoint; this.session = null; this.protocol = null; this.id = 0; }
  async send(method, params = {}, notification = false) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
    if (this.session) headers['Mcp-Session-Id'] = this.session;
    if (this.protocol) headers['MCP-Protocol-Version'] = this.protocol;
    const message = { jsonrpc: '2.0', method, params };
    if (!notification) message.id = ++this.id;
    const response = await fetch(this.endpoint, { method: 'POST', headers, body: JSON.stringify(message), signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`MCP HTTP ${response.status}: ${await response.text()}`);
    const session = response.headers.get('Mcp-Session-Id');
    if (session) this.session = session;
    if (notification || response.status === 202 || response.status === 204) return null;
    const text = await response.text();
    let data;
    if (response.headers.get('Content-Type')?.includes('text/event-stream')) {
      for (const event of text.split(/\r?\n\r?\n/)) {
        const payload = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (!payload) continue;
        const candidate = JSON.parse(payload);
        if (candidate.id === message.id) { data = candidate; break; }
      }
      if (!data) throw new Error('MCP stream contained no matching response');
    } else data = JSON.parse(text);
    if (data.error && method !== 'tools/call') throw new Error(data.error.message || 'MCP request failed');
    if (method === 'initialize') {
      if (!data.result?.serverInfo) throw new Error('Invalid MCP initialization response');
      this.protocol = data.result.protocolVersion;
      await this.send('notifications/initialized', {}, true);
    }
    return data;
  }
}
