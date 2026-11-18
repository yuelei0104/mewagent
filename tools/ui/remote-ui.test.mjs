import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Window } from 'happy-dom';

const html = readFileSync(new URL('../../src/main/resources/com/mewagent/remote/index.html', import.meta.url), 'utf8');
const script = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');

function setup(t, connect = true) {
  const window = new Window({ url: 'http://localhost:18888', settings: {
    disableJavaScriptFileLoading: true, disableCSSFileLoading: true, disableJavaScriptEvaluation: true
  }});
  const sockets = [];
  class FakeSocket {
    static OPEN = 1;
    readyState = 0;
    sent = [];
    constructor() { sockets.push(this); }
    send(raw) { if (this.fail) throw new Error('connection lost'); this.sent.push(JSON.parse(raw)); }
    close() { this.readyState = 3; }
    message(type, data) { this.onmessage?.({ data: JSON.stringify({ type, data }) }); }
    open(data = {}) {
      this.readyState = 1; this.onopen?.();
      this.message('connected', { session: '20261118-1200', cwd: '/work/project', branch: 'feature/ui',
        model: 'test-model', permissionMode: 'default', streaming: false, stopping: false, ...data });
    }
  }
  window.WebSocket = FakeSocket;
  window.document.write(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, ''));
  window.eval(script);
  if (connect) sockets[0].open();
  t.after(() => window.happyDOM.close());
  const input = window.document.getElementById('input');
  const key = options => input.dispatchEvent(new window.KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Enter', ...options }));
  const messages = type => sockets.flatMap(s => s.sent).filter(m => m.type === type);
  return { window, document: window.document, sockets, input, key, messages };
}

test('IME confirmation and Shift+Enter do not submit; ordinary Enter submits once', t => {
  const f = setup(t); f.input.value = '中文测试';
  f.input.dispatchEvent(new f.window.CompositionEvent('compositionstart', { bubbles: true }));
  f.key({ isComposing: true }); f.key({ keyCode: 229 });
  assert.equal(f.messages('user_message').length, 0);
  f.input.dispatchEvent(new f.window.CompositionEvent('compositionend', { bubbles: true }));
  f.key({ shiftKey: true }); assert.equal(f.messages('user_message').length, 0);
  f.key({}); assert.equal(f.messages('user_message').length, 1);
  assert.equal(f.messages('user_message')[0].data.content, '中文测试');
});

test('offline send preserves text and attachments without adding a user message', t => {
  const f = setup(t, false); f.input.value = 'keep this draft';
  f.window.eval("attachments=[{path:'uploads/example.txt',name:'example.txt',size:12}]");
  f.window.runCommand(f.input.value);
  assert.equal(f.input.value, 'keep this draft');
  assert.equal(f.window.eval('attachments.length'), 1);
  assert.equal(f.document.querySelectorAll('.msg-user').length, 0);
  assert.equal(f.messages('user_message').length, 0);
});

test('synchronous send failure preserves draft and attachments', t => {
  const f = setup(t); f.input.value = 'unsent'; f.sockets[0].fail = true;
  f.window.eval("attachments=[{path:'uploads/x.txt',name:'x.txt',size:2}]");
  f.window.runCommand('unsent');
  assert.equal(f.input.value, 'unsent');
  assert.equal(f.window.eval('attachments.length'), 1);
  assert.equal(f.document.querySelectorAll('.msg-user').length, 0);
});

test('Stop waits for server completion and preserves the next draft', t => {
  const f = setup(t); f.input.value = 'run task'; f.key({});
  assert.equal(f.document.getElementById('send-btn').textContent, '停止');
  f.input.value = 'next draft'; f.key({});
  assert.equal(f.messages('cancel').length, 0, 'Enter in the next draft must not cancel');
  f.document.getElementById('send-btn').click();
  f.window.stopRun();
  assert.equal(f.messages('cancel').length, 1);
  assert.equal(f.document.getElementById('send-btn').textContent, '正在停止…');
  f.sockets[0].message('loop_complete', { elapsed: 1.2, cancelled: true });
  assert.equal(f.document.getElementById('send-btn').textContent, '发送');
  assert.equal(f.input.value, 'next draft');
  assert.match(f.document.querySelector('.done-indicator').textContent, /已停止/);
});

test('reconnect synchronizes an active task and retains a disconnected draft', t => {
  const f = setup(t); f.input.value = 'draft';
  f.sockets[0].readyState = 3; f.sockets[0].onclose({ code: 1006 });
  f.window.runCommand('draft'); assert.equal(f.messages('user_message').length, 0);
  assert.equal(f.document.getElementById('send-btn').textContent, '重新连接');
  f.document.getElementById('send-btn').click();
  f.sockets[1].open({ streaming: true });
  assert.equal(f.input.value, 'draft');
  assert.equal(f.document.getElementById('send-btn').textContent, '停止');
  assert.equal(f.document.querySelector('.new-session-btn').disabled, true);
});

test('runtime metadata is server supplied and has no fabricated main branch', t => {
  const f = setup(t);
  assert.equal(f.document.getElementById('ws-branch').textContent, 'feature/ui');
  assert.equal(f.document.getElementById('es-model').textContent, 'test-model');
  f.sockets[0].message('runtime_state', { branch: '', permissionMode: 'plan', streaming: false });
  assert.equal(f.document.getElementById('es-branch').textContent, '非 Git 仓库');
  assert.equal(f.document.getElementById('es-permission').textContent, '计划模式');
});

test('drawer keyboard dismissal and persistent font-size controls work', t => {
  const f = setup(t); f.window.toggleSidebar(true);
  assert.equal(f.document.getElementById('sidebar-toggle').getAttribute('aria-expanded'), 'true');
  f.document.dispatchEvent(new f.window.KeyboardEvent('keydown', { key: 'Escape' }));
  assert.equal(f.document.getElementById('sidebar-backdrop').hidden, true);
  f.window.setFontScale(1.2); f.window.openSettings();
  assert.equal(f.window.localStorage.getItem('mewagent-font-scale'), '1.2');
  assert.equal(f.document.getElementById('font-scale').value, '1.2');
  f.window.setFontScale(100); assert.equal(f.window.localStorage.getItem('mewagent-font-scale'), '1');
});

test('completed tool output and quoted file paths can be copied verbatim', async t => {
  const f = setup(t); const copied = [];
  Object.defineProperty(f.window, 'isSecureContext', { value: true });
  Object.defineProperty(f.window.navigator, 'clipboard', { value: { writeText: async value => copied.push(value) } });
  const filename = '/work/a"quoted".java';
  f.window.addToolUse({ toolId: '1', toolName: 'ReadFile', args: { file_path: filename } });
  f.window.updateToolResult({ toolId: '1', toolName: 'ReadFile', output: '1  hello\n2  world', elapsed: .1, isError: false });
  const card = f.document.querySelector('.tool-block');
  assert.equal(card.querySelector('.tool-file').dataset.path, filename);
  await f.window.copyText(filename, card.querySelector('.tool-file'));
  f.window.copyToolOutput(card.querySelector('.tool-copy'));
  await Promise.resolve();
  assert.deepEqual(copied, [filename, '1  hello\n2  world']);
});

test('cancelled permission dialogs stay disabled during later tasks', t => {
  const f = setup(t); f.sockets[0].message('runtime_state', { streaming: true });
  f.window.addPermissionDialog({ id: 'p1', toolName: 'Bash', description: 'run tests' });
  f.window.stopRun(); f.sockets[0].message('loop_complete', { elapsed: .1, cancelled: true });
  f.sockets[0].message('runtime_state', { streaming: true, stopping: false });
  assert.ok([...f.document.querySelectorAll('#perm-p1 button')].every(b => b.disabled));
});
