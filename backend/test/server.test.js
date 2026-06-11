import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../src/server.js';

test('POST /v1/assist requires bearer token', async () => {
  const { server, baseUrl } = await listen();
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(validRequest())
    });

    assert.equal(response.status, 401);
  } finally {
    await close(server);
  }
});

test('POST /v1/assist returns fallback response when provider is not configured', async () => {
  const { server, baseUrl } = await listen();
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        ...validRequest(),
        utterance: '请分析这个设置页面'
      })
    });
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.requiresConfirmation, false);
    assert.deepEqual(body.actions, []);
    assert.match(body.spoken, /WLAN/);
  } finally {
    await close(server);
  }
});

test('POST /v1/assist calls Qwen provider and validates returned protocol', async () => {
  let capturedUrl;
  let capturedOptions;
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus'
    },
    fetchImpl: async (url, options) => {
      capturedUrl = url;
      capturedOptions = options;
      return {
        ok: true,
        json: async () => ({
          choices: [
            {
              message: {
                content: '{"spoken":"当前页面有 WLAN。","requiresConfirmation":false,"actions":[]}'
              }
            }
          ]
        })
      };
    }
  });
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        ...validRequest(),
        utterance: '请分析这个设置页面'
      })
    });
    const body = await response.json();
    const providerBody = JSON.parse(capturedOptions.body);

    assert.equal(response.status, 200);
    assert.equal(capturedUrl, 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions');
    assert.equal(capturedOptions.headers.Authorization, 'Bearer test-qwen-key');
    assert.equal(providerBody.model, 'qwen3.6-plus');
    assert.deepEqual(providerBody.response_format, { type: 'json_object' });
    assert.deepEqual(body, {
      spoken: '当前页面有 WLAN。',
      requiresConfirmation: false,
      actions: []
    });
  } finally {
    await close(server);
  }
});

test('POST /v1/assist handles clear local commands before calling provider', async () => {
  let fetchCalls = 0;
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus'
    },
    fetchImpl: async () => {
      fetchCalls += 1;
      return {
        ok: true,
        json: async () => ({
          choices: [
            {
              message: {
                content: '{"spoken":"不应调用 provider。","requiresConfirmation":false,"actions":[]}'
              }
            }
          ]
        })
      };
    }
  });
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        ...validRequest(),
        utterance: '点击 WLAN',
        screen: {
          ...validRequest().screen,
          nodes: [
            { nodeId: 'node_wlan', text: 'WLAN', role: 'Button', clickable: true }
          ]
        }
      })
    });
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(fetchCalls, 0);
    assert.deepEqual(body.actions, [{ type: 'CLICK_NODE', nodeId: 'node_wlan' }]);
  } finally {
    await close(server);
  }
});

test('POST /v1/assist handles screen reading locally before calling provider', async () => {
  let fetchCalls = 0;
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.7-plus'
    },
    fetchImpl: async () => {
      fetchCalls += 1;
      return {
        ok: true,
        json: async () => ({
          choices: [
            {
              message: {
                content: '{"spoken":"不应调用 provider。","requiresConfirmation":false,"actions":[]}'
              }
            }
          ]
        })
      };
    }
  });
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        ...validRequest(),
        utterance: '这里有什么',
        screen: {
          ...validRequest().screen,
          nodes: [
            { nodeId: 'node_settings', text: '设置', role: 'TextView' },
            { nodeId: 'node_wlan', text: 'WLAN', role: 'Button', clickable: true }
          ]
        }
      })
    });
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(fetchCalls, 0);
    assert.equal(body.requiresConfirmation, false);
    assert.deepEqual(body.actions, []);
    assert.match(body.spoken, /设置/);
    assert.match(body.spoken, /WLAN/);
  } finally {
    await close(server);
  }
});

test('POST /v1/assist rejects provider actions outside the V1 allowlist', async () => {
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus'
    },
    fetchImpl: async () => ({
      ok: true,
      json: async () => ({
        choices: [
          {
            message: {
              content: '{"spoken":"我会执行脚本。","requiresConfirmation":false,"actions":[{"type":"RUN_SCRIPT"}]}'
            }
          }
        ]
      })
    })
  });
  try {
    const response = await fetch(`${baseUrl}/v1/assist`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        ...validRequest(),
        utterance: '请分析这个设置页面'
      })
    });
    const body = await response.json();

    assert.equal(response.status, 502);
    assert.equal(body.error, 'ai response invalid');
    assert.match(body.detail, /unsupported action type: RUN_SCRIPT/);
  } finally {
    await close(server);
  }
});

test('POST /v1/transcribe calls Qwen ASR and returns recognized text', async () => {
  let capturedOptions;
  const { server, baseUrl } = await listen({
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus',
      asrModel: 'qwen3-asr-flash'
    },
    fetchImpl: async (_url, options) => {
      capturedOptions = options;
      return {
        ok: true,
        json: async () => ({
          choices: [
            {
              message: {
                content: '点击确定'
              }
            }
          ]
        })
      };
    }
  });
  try {
    const response = await fetch(`${baseUrl}/v1/transcribe`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer dev-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        locale: 'zh-CN',
        mimeType: 'audio/mp4',
        audioBase64: 'AAAA'
      })
    });
    const body = await response.json();
    const providerBody = JSON.parse(capturedOptions.body);

    assert.equal(response.status, 200);
    assert.equal(capturedOptions.headers.Authorization, 'Bearer test-qwen-key');
    assert.equal(providerBody.model, 'qwen3-asr-flash');
    assert.equal(body.text, '点击确定');
  } finally {
    await close(server);
  }
});

test('POST /v1/transcribe returns 504 before the Android client call timeout when ASR stalls', async () => {
  const controller = new AbortController();
  const { server, baseUrl } = await listen({
    providerTimeoutMillis: 10,
    qwenConfig: {
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      apiKey: 'test-qwen-key',
      model: 'qwen3.6-plus',
      asrModel: 'qwen3-asr-flash'
    },
    fetchImpl: async () => new Promise(() => {})
  });
  try {
    const result = await Promise.race([
      fetch(`${baseUrl}/v1/transcribe`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Authorization': 'Bearer dev-token',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          locale: 'zh-CN',
          mimeType: 'audio/mp4',
          audioBase64: 'AAAA'
        })
      }).then(async (response) => ({
        status: response.status,
        body: await response.json()
      })),
      delay(200).then(() => 'client timed out')
    ]);

    if (result === 'client timed out') {
      controller.abort();
    }
    assert.notEqual(result, 'client timed out');
    assert.equal(result.status, 504);
    assert.equal(result.body.error, 'ai provider timeout');
  } finally {
    server.closeAllConnections?.();
    await close(server);
  }
});

function validRequest() {
  return {
    sessionId: 'session-1',
    locale: 'zh-CN',
    utterance: '这里有什么',
    screen: {
      packageName: 'com.android.settings',
      activityName: 'Settings',
      nodes: [{ text: 'WLAN', role: 'Button', clickable: true }],
      screenshotBase64: null
    }
  };
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function listen(options) {
  const server = createServer(options);
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      resolve({ server, baseUrl: `http://127.0.0.1:${address.port}` });
    });
  });
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}
