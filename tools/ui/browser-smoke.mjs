// Browser checks use synthetic events, never a live model or the user's workspace.
import { readFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const html = readFileSync(new URL('../../src/main/resources/com/mewagent/remote/index.html', import.meta.url), 'utf8');
const output = fileURLToPath(new URL('../../build/ui-review/', import.meta.url));
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, colorScheme: 'dark', reducedMotion: 'reduce' });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('https://cdn.jsdelivr.net/**', route => route.fulfill({ contentType: 'application/javascript', body: '' }));
  await page.route('http://mewagent.test/**', route => route.fulfill({ contentType: 'text/html', body: html }));
  await page.addInitScript(() => {
    window.testSent = [];
    window.WebSocket = class {
      static OPEN = 1;
      readyState = 1;
      constructor() {
        setTimeout(() => {
          this.onopen?.();
          this.onmessage?.({ data: JSON.stringify({ type: 'connected', data: {
            session: '20261118-2045', cwd: '/workspace/mewagent', branch: 'feature/ui', model: 'test-model',
            permissionMode: 'default', streaming: false, stopping: false
          }}) });
          this.onmessage?.({ data: JSON.stringify({ type: 'sessions', data: [
            { id: '20261118-2045', title: '修复分页边界并补充测试', relativeTime: 'just now', messageCount: 8 },
            { id: '20261117-2045', title: '检查 Agent 权限与工具执行', relativeTime: '1 day ago', messageCount: 12 }
          ]}) });
        }, 20);
      }
      send(raw) { window.testSent.push(JSON.parse(raw)); }
      close() { this.readyState = 3; }
    };
  });
  await page.goto('http://mewagent.test');
  await page.waitForSelector('.welcome');
  await page.screenshot({ path: output + 'desktop-welcome-dark.png' });
  await page.evaluate(() => {
    addUser('修复分页参数越界问题，并补充边界测试。');
    addAssistant('我会检查分页实现、修改边界处理，然后执行测试。').querySelector('.bubble').textContent = '我会检查分页实现、修改边界处理，然后执行测试。';
    addToolUse({ toolId: 'read', toolName: 'ReadFile', args: { file_path: 'src/main/java/Pagination.java' } });
    updateToolResult({ toolId: 'read', toolName: 'ReadFile', output: '1  public List<String> page(int page, int size) {\n2      return items.subList((page - 1) * size, page * size);\n3  }', elapsed: .1, isError: false });
    addToolUse({ toolId: 'edit', toolName: 'EditFile', args: { file_path: 'src/main/java/Pagination.java' } });
    updateToolResult({ toolId: 'edit', toolName: 'EditFile', output: '--- a/Pagination.java\n+++ b/Pagination.java\n@@ -1,3 +1,5 @@\n public List<String> page(int page, int size) {\n+    if (page < 1 || size < 1) throw new IllegalArgumentException();\n+    if ((page - 1) * size >= items.size()) return List.of();\n-    return items.subList((page - 1) * size, page * size);\n+    return items.subList((page - 1) * size, Math.min(page * size, items.size()));\n }', elapsed: .3, isError: false });
    addToolUse({ toolId: 'bash', toolName: 'Bash', args: { command: './gradlew test' } });
    updateToolResult({ toolId: 'bash', toolName: 'Bash', output: 'BUILD SUCCESSFUL\n7 tests completed, 0 failed', elapsed: 2.3, isError: false });
    handleMessage({ type: 'usage', data: { inputTokens: 12468, outputTokens: 2157 } });
    handleMessage({ type: 'loop_complete', data: { elapsed: 8.6, cancelled: false } });
  });
  await page.screenshot({ path: output + 'desktop-conversation-dark.png' });
  await page.evaluate(() => setTheme('light'));
  await page.screenshot({ path: output + 'desktop-conversation-light.png' });
  for (const width of [390, 640, 768]) {
    await page.setViewportSize({ width, height: 844 });
    const bounds = await page.evaluate(() => {
      const send = document.getElementById('send-btn').getBoundingClientRect();
      const center = document.getElementById('center').getBoundingClientRect();
      return { sendLeft: send.left, sendRight: send.right, centerRight: center.right, width: innerWidth };
    });
    assert.ok(bounds.sendLeft >= 0 && bounds.sendRight <= width + 1, JSON.stringify(bounds));
    assert.ok(bounds.centerRight <= width + 1, JSON.stringify(bounds));
    await page.screenshot({ path: output + `conversation-${width}.png` });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.click('#sidebar-toggle');
  await page.waitForSelector('#app.sidebar-open');
  await page.screenshot({ path: output + 'mobile-drawer.png' });
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'false');
  await page.click('#settings-btn');
  await page.selectOption('#font-scale', '1.2');
  await page.screenshot({ path: output + 'mobile-settings-large.png' });
  await page.keyboard.press('Escape');
  const input = page.locator('#input');
  await input.fill('中文输入确认');
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.equal(await page.evaluate(() => testSent.filter(e => e.type === 'user_message').length), 0);
  await input.press('Enter');
  await page.getByRole('button', { name: '停止', exact: true }).click();
  assert.equal(await page.evaluate(() => testSent.filter(e => e.type === 'cancel').length), 1);
  await page.evaluate(() => handleMessage({ type: 'loop_complete', data: { elapsed: .5, cancelled: true } }));
  assert.equal(await page.locator('#send-btn').textContent(), '发送');
  assert.deepEqual(errors, []);
  console.log('Browser smoke checks passed; screenshots: build/ui-review');
} finally {
  await browser.close();
}
