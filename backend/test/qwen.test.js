import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_QWEN_ENDPOINT,
  DEFAULT_QWEN_MODEL,
  buildQwenChatCompletionRequest,
  createQwenConfig,
  parseQwenChatCompletion
} from '../src/qwen.js';

test('createQwenConfig uses DashScope Qwen 3.6 Plus defaults without exposing app tokens', () => {
  const config = createQwenConfig({
    QWEN_API_KEY: 'test-qwen-key',
    APP_API_TOKEN: 'app-token-that-must-not-be-used'
  });

  assert.equal(config.baseUrl, DEFAULT_QWEN_ENDPOINT);
  assert.equal(config.model, DEFAULT_QWEN_MODEL);
  assert.equal(config.apiKey, 'test-qwen-key');
});

test('buildQwenChatCompletionRequest asks for strict JSON and disables thinking', () => {
  const request = validRequest();

  const payload = buildQwenChatCompletionRequest(request, DEFAULT_QWEN_MODEL);

  assert.equal(payload.model, 'qwen3.6-plus');
  assert.deepEqual(payload.response_format, { type: 'json_object' });
  assert.equal(payload.enable_thinking, false);
  assert.match(payload.messages[0].content, /JSON/i);
  assert.match(payload.messages[0].content, /CLICK_NODE/);
  assert.match(payload.messages[0].content, /如果无法确定/);
  assert.equal(typeof payload.messages[1].content, 'string');
  assert.doesNotMatch(payload.messages[1].content, /raw-image-data/);
});

test('buildQwenChatCompletionRequest sends screenshots as image_url content', () => {
  const request = validRequest({
    screen: {
      ...validRequest().screen,
      screenshotBase64: 'raw-image-data'
    }
  });

  const payload = buildQwenChatCompletionRequest(request, DEFAULT_QWEN_MODEL);
  const userContent = payload.messages[1].content;

  assert.equal(Array.isArray(userContent), true);
  assert.equal(userContent[0].type, 'text');
  assert.doesNotMatch(userContent[0].text, /raw-image-data/);
  assert.equal(userContent[1].type, 'image_url');
  assert.equal(userContent[1].image_url.url, 'data:image/jpeg;base64,raw-image-data');
});

test('parseQwenChatCompletion parses assistant JSON content', () => {
  const parsed = parseQwenChatCompletion({
    choices: [
      {
        message: {
          content: '{"spoken":"当前页面有 WLAN。","requiresConfirmation":false,"actions":[]}'
        }
      }
    ]
  });

  assert.deepEqual(parsed, {
    spoken: '当前页面有 WLAN。',
    requiresConfirmation: false,
    actions: []
  });
});

function validRequest(overrides = {}) {
  return {
    sessionId: 'session-1',
    locale: 'zh-CN',
    utterance: '这里有什么',
    screen: {
      packageName: 'com.android.settings',
      activityName: 'Settings',
      nodes: [{ nodeId: 'node_0', text: 'WLAN', role: 'Button', clickable: true }],
      screenshotBase64: null
    },
    ...overrides
  };
}
