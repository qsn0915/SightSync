import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';

test('server starts when executed as the main script on Windows paths', async () => {
  const port = await freePort();
  const child = spawn(process.execPath, [path.join(process.cwd(), 'src/server.js')], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      PORT: String(port),
      QWEN_API_KEY: '',
      DASHSCOPE_API_KEY: '',
      AI_API_KEY: ''
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  try {
    const output = await waitForOutput(child, /AI proxy listening/, 2_000);
    assert.match(output, /AI proxy listening/);

    const response = await fetch(`http://127.0.0.1:${port}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        sessionId: 'session-1',
        locale: 'zh-CN',
        utterance: '这里有什么',
        screen: {
          packageName: 'com.android.settings',
          activityName: 'Settings',
          nodes: [{ nodeId: 'node_wlan', text: 'WLAN', role: 'Button', clickable: true }],
          screenshotBase64: null
        }
      })
    });

    assert.equal(response.status, 200);
  } finally {
    child.kill();
  }
});

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close((error) => {
        if (error) reject(error);
        else resolve(port);
      });
    });
    server.on('error', reject);
  });
}

function waitForOutput(child, pattern, timeoutMillis) {
  return new Promise((resolve, reject) => {
    let output = '';
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error(`timed out waiting for ${pattern}; output: ${output}`));
    }, timeoutMillis);

    const onData = (chunk) => {
      output += chunk.toString();
      if (pattern.test(output)) {
        cleanup();
        resolve(output);
      }
    };
    const onExit = (code, signal) => {
      cleanup();
      reject(new Error(`server exited with code ${code} signal ${signal}; output: ${output}`));
    };
    const cleanup = () => {
      clearTimeout(timeout);
      child.stdout.off('data', onData);
      child.stderr.off('data', onData);
      child.off('exit', onExit);
    };

    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', onExit);
  });
}
