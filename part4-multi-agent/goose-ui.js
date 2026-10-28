/* Optional live Goose session. ACP transport and A2A business decisions stay separate. */
(function () {
  const el = id => document.getElementById('goose-' + id);
  let client, sessionId, busy = false, connecting = false, pollTimer, polling = false;
  let assistantText, turnUncertain = false;
  let decisionEpoch = 0, deciding = false;
  let connectionAttempt = 0;
  const tools = new Map(), permissions = new Map(), tasks = new Map();
  let baseline = new Set(), approvedTaskId = null;
  const prompt = 'Use the refund-investigation skill to investigate the disputed payment for CUST-4091 '
    + 'and request one simulated $2500 refund. Collect the audit evidence, leave approval to me, '
    + 'and fill the evidence report template. Do not call the admin approval or rejection endpoints. '
    + 'When approval is pending, report the task ID and finish this turn so I can decide in the console.';

  function state(label) { el('status').textContent = label; }
  function controls() {
    const ready = !!sessionId && !busy && !turnUncertain;
    el('connect').disabled = !!client || connecting;
    el('disconnect').disabled = !client;
    el('investigate').disabled = !ready || [...tasks.values()].some(task => task.operation === 'process-refund');
    el('send').disabled = !ready;
    el('prompt').disabled = !ready;
    el('continue').disabled = !ready || !approvedTaskId;
    el('cancel').disabled = !busy;
    // Avoid resetting the shared workflow counters while observing a live agent.
    document.getElementById('btn-reset').disabled = !!client;
  }
  function message(role, text) {
    const item = document.createElement('div'); item.className = 'goose-message';
    const label = document.createElement('strong'); label.textContent = role;
    const content = document.createElement('span'); content.textContent = text;
    item.append(label, content); el('transcript').append(item);
    el('transcript').scrollTop = el('transcript').scrollHeight;
    return content;
  }
  function update(notification) {
    if (notification.sessionId !== sessionId) return;
    const value = notification.update || {};
    if (value.sessionUpdate === 'agent_message_chunk' && value.content?.type === 'text') {
      if (!assistantText) assistantText = message('Goose', '');
      assistantText.textContent += value.content.text;
      el('transcript').scrollTop = el('transcript').scrollHeight;
    } else if (value.sessionUpdate === 'tool_call' || value.sessionUpdate === 'tool_call_update') {
      let item = tools.get(value.toolCallId);
      if (!item) {
        const node = document.createElement('div'); node.className = 'goose-tool';
        const title = document.createElement('strong'), output = document.createElement('pre');
        node.append(title, output); el('tools').append(node);
        item = { title, output, name: 'Tool', status: 'pending' }; tools.set(value.toolCallId, item);
      }
      item.name = value.title || item.name; item.status = value.status || item.status;
      item.title.textContent = `${item.name} · ${item.status}`;
      if (value.content || value.rawInput || value.rawOutput) {
        item.output.textContent = JSON.stringify(value.content || value.rawOutput || value.rawInput, null, 2).slice(0, 12000);
      }
      el('tools').scrollTop = el('tools').scrollHeight;
    }
  }
  function permission(request, reply) {
    if (request.params.sessionId !== sessionId) { reply({ outcome: { outcome: 'cancelled' } }); return; }
    const box = document.createElement('div'); box.className = 'goose-permission';
    const title = document.createElement('strong'); title.textContent = 'Goose tool permission — not refund approval';
    const details = document.createElement('pre');
    details.textContent = JSON.stringify(request.params.toolCall, null, 2).slice(0, 16000);
    const choices = document.createElement('div'); choices.className = 'goose-actions';
    const finish = result => { reply(result); permissions.delete(request.id); box.remove(); };
    for (const option of request.params.options || []) {
      if (!['allow_once', 'reject_once'].includes(option.kind)) continue;
      const button = document.createElement('button'); button.className = 'btn btn-secondary';
      button.textContent = option.name;
      button.addEventListener('click', () => finish({ outcome: { outcome: 'selected', optionId: option.optionId } }));
      choices.append(button);
    }
    const cancel = document.createElement('button'); cancel.className = 'btn btn-outline'; cancel.textContent = 'Decline tool request';
    cancel.addEventListener('click', () => finish({ outcome: { outcome: 'cancelled' } })); choices.append(cancel);
    box.append(title, details, choices); el('permissions').append(box);
    permissions.set(request.id, () => finish({ outcome: { outcome: 'cancelled' } }));
  }
  function stopPermissions() { for (const cancel of [...permissions.values()]) cancel(); }
  function disconnected() {
    connectionAttempt++;
    client = null; sessionId = null; busy = false; connecting = false;
    clearInterval(pollTimer); stopPermissions(); state('disconnected'); controls();
    message('Session', 'Disconnected. Workflow tasks are not deleted or retried. Check pending tasks before starting another investigation.');
  }
  async function connect() {
    const attempt = ++connectionAttempt;
    let connection;
    connecting = true; controls(); el('error').textContent = ''; state('connecting');
    try {
      if (!['localhost', '127.0.0.1'].includes(location.hostname)) throw new Error('Open the local SPA on localhost or 127.0.0.1');
      const response = await fetch('target/goose-ui.json', { cache: 'no-store' });
      if (!response.ok) throw new Error('Goose is not enabled. Restart with WITH_GOOSE=true ./start-all.sh and configure your Goose model.');
      const config = await response.json();
      const url = new URL(config.url);
      if (url.protocol !== 'ws:' || !['localhost', '127.0.0.1'].includes(url.hostname) || url.pathname !== '/acp') throw new Error('Invalid local Goose connection settings');
      url.searchParams.set('token', config.token);
      baseline = new Set((await fetchJson('/api/tasks')).map(task => task.id));
      tasks.clear(); tools.clear(); approvedTaskId = null; assistantText = null; turnUncertain = false;
      el('transcript').replaceChildren(); el('tools').replaceChildren();
      connection = new GooseAcpClient({ onUpdate: update, onPermission: permission,
        onClose: () => { if (client === connection) disconnected(); } });
      client = connection;
      await connection.connect(url.toString());
      const session = await connection.request('session/new', { cwd: config.cwd, mcpServers: [],
        _meta: { sessionTitle: 'Acme refund investigation', client: 'acme-governance-console' } });
      if (client !== connection) return;
      if (!session.sessionId) throw new Error('Goose did not return a session ID');
      sessionId = session.sessionId;
      el('session').textContent = `Goose session ${sessionId} · project skill: refund-investigation`;
      message('Session', 'Connected to Goose. Load the investigation prompt to begin. Tool calls and skill loading will appear when Goose emits them.');
      state('ready'); pollTimer = setInterval(pollTasks, 1200);
    } catch (error) {
      if (attempt !== connectionAttempt) return;
      client = null; sessionId = null; connection?.close();
      el('error').textContent = error.message; state('connection failed');
    } finally { if (attempt === connectionAttempt) { connecting = false; controls(); } }
  }
  async function send() {
    const text = el('prompt').value.trim();
    if (!text || !sessionId || busy || turnUncertain) return;
    const connection = client, id = sessionId;
    busy = true; assistantText = null; el('error').textContent = ''; controls(); state('Goose working');
    message('You', text); el('prompt').value = '';
    try {
      const result = await connection.request('session/prompt', { sessionId: id,
        prompt: [{ type: 'text', text }] }, 600000);
      if (client !== connection) return;
      state(result.stopReason === 'cancelled' ? 'turn cancelled' : 'ready');
      await pollTasks();
    } catch (error) {
      if (client !== connection) return;
      el('error').textContent = error.message;
      // A lost prompt response does not prove the agent stopped. Require a disconnect.
      turnUncertain = true; state('turn outcome uncertain');
    } finally { if (client === connection) { busy = false; assistantText = null; controls(); } }
  }
  async function pollTasks() {
    if (polling || !sessionId || deciding) return;
    polling = true;
    const epoch = decisionEpoch, id = sessionId;
    try {
      const observed = await fetchJson('/api/tasks');
      if (epoch !== decisionEpoch || id !== sessionId) return;
      for (const task of observed) {
        if (baseline.has(task.id)) continue;
        const previous = tasks.get(task.id);
        if (!previous) taskCount++;
        if (previous?.state !== task.state) {
          if (previous?.state === 'input-required') pendingCount--;
          if (task.state === 'input-required') pendingCount++;
          if (task.state === 'completed') completedCount++;
          addLog('goose', 'a2a', `Observed ${esc(task.operation)} · ${esc(task.id)} · <strong>${esc(task.state)}</strong>`);
        }
        tasks.set(task.id, task);
        if (currentHitlTaskId === task.id && task.state !== 'input-required') {
          currentHitlTaskId = null; document.getElementById('hitl-panel').classList.remove('visible');
          approvedTaskId = task.id; controls();
        }
      }
      const pending = [...tasks.values()].find(task => task.state === 'input-required');
      if (pending && !currentHitlTaskId) {
        currentHitlTaskId = pending.id;
        for (const [field, value] of Object.entries({ 'task-id': pending.id, operation: pending.operation,
          risk: pending.riskLevel, reason: pending.approvalReason })) document.getElementById('hitl-' + field).textContent = value || '';
        document.getElementById('hitl-panel').classList.add('visible');
        setSmPhase('sm-hitl-gate', 'hitl'); setLayerState('tl-hitl', 'hitl');
        document.getElementById('sm-status').textContent = 'input-required';
      }
      updateStats();
      controls();
    } catch (error) { el('error').textContent = `Workflow status unavailable: ${error.message}. Do not resubmit to check status.`; }
    finally { polling = false; }
  }

  window.addEventListener('workflow-decision', event => {
    const task = tasks.get(event.detail.id);
    if (!task) return;
    // The existing approval handler already updated shared counters.
    task.state = event.detail.decision === 'approve' ? 'completed' : 'failed';
    approvedTaskId = task.id; controls();
    message('Operator decision', `${event.detail.decision} for task ${task.id}. Continue Investigation polls this same task; it does not submit another refund.`);
  });
  window.addEventListener('workflow-decision-start', () => { deciding = true; decisionEpoch++; });
  window.addEventListener('workflow-decision-end', () => { deciding = false; decisionEpoch++; });
  el('connect').addEventListener('click', connect);
  el('send').addEventListener('click', send);
  el('investigate').addEventListener('click', () => { el('prompt').value = prompt; el('prompt').focus(); });
  el('continue').addEventListener('click', () => {
    el('prompt').value = `The operator has decided on refund task ${approvedTaskId}. Use the refund-investigation skill to poll this same task and finish the evidence report. Do not submit another refund or call any admin decision endpoint.`;
    el('prompt').focus();
  });
  el('cancel').addEventListener('click', () => {
    client?.notify('session/cancel', { sessionId }); stopPermissions(); state('cancelling turn');
    message('Session', 'Cancellation requested. Any workflow already submitted remains subject to its existing approval state.');
  });
  el('disconnect').addEventListener('click', () => {
    client?.notify('session/cancel', { sessionId }); stopPermissions(); client?.close();
  });
  controls();
})();
