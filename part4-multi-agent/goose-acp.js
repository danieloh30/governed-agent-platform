/* Small ACP WebSocket client shared by the SPA and protocol tests. */
(function (root) {
  class GooseAcpClient {
    constructor({ onUpdate = () => {}, onPermission = () => {}, onClose = () => {}, socketFactory } = {}) {
      this.onUpdate = onUpdate; this.onPermission = onPermission; this.onClose = onClose;
      this.socketFactory = socketFactory || (url => new WebSocket(url));
      this.pending = new Map(); this.nextId = 0; this.socket = null;
    }
    async connect(url) {
      if (this.socket) throw new Error('Disconnect the existing Goose connection first');
      const socket = this.socketFactory(url); this.socket = socket;
      socket.onmessage = event => this.receive(event.data);
      socket.onclose = () => {
        if (this.socket !== socket) return;
        this.socket = null;
        for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(new Error('Goose connection closed')); }
        this.pending.clear(); this.onClose();
      };
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { socket.close(); reject(new Error('Goose connection timed out')); }, 10000);
        socket.onopen = () => { clearTimeout(timer); resolve(); };
        socket.onerror = () => { clearTimeout(timer); socket.close(); reject(new Error('Cannot connect to Goose; check the optional launcher and its log')); };
      });
      return this.request('initialize', { protocolVersion: 1, clientCapabilities: {},
        clientInfo: { name: 'acme-governance-console', version: '1.0' } });
    }
    request(method, params, timeoutMs = 90000) {
      if (!this.socket || this.socket.readyState !== 1) return Promise.reject(new Error('Goose is not connected'));
      const id = ++this.nextId;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new Error(`${method} timed out; check the session before retrying`));
        }, timeoutMs);
        this.pending.set(id, { resolve, reject, timer });
        this.socket.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
      });
    }
    notify(method, params) {
      if (this.socket?.readyState === 1) this.socket.send(JSON.stringify({ jsonrpc: '2.0', method, params }));
    }
    reply(id, result) {
      if (this.socket?.readyState === 1) this.socket.send(JSON.stringify({ jsonrpc: '2.0', id, result }));
    }
    receive(raw) {
      let message; try { message = JSON.parse(raw); } catch { return; }
      if (message.method === 'session/update') { this.onUpdate(message.params); return; }
      if (message.method === 'session/request_permission' && message.id !== undefined) {
        this.onPermission(message, result => this.reply(message.id, result)); return;
      }
      if (message.method && message.id !== undefined) {
        this.socket?.send(JSON.stringify({ jsonrpc: '2.0', id: message.id,
          error: { code: -32601, message: 'This client does not provide that capability' } })); return;
      }
      const entry = this.pending.get(message.id);
      if (!entry) return;
      this.pending.delete(message.id); clearTimeout(entry.timer);
      if (message.error) {
        const detail = typeof message.error.data === 'string' ? message.error.data : '';
        entry.reject(new Error([message.error.message, detail].filter(Boolean).join(': ')));
      } else entry.resolve(message.result);
    }
    close() { this.socket?.close(); }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { GooseAcpClient };
  else root.GooseAcpClient = GooseAcpClient;
})(globalThis);
