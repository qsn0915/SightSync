import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_QWEN_ENDPOINT,
  DEFAULT_QWEN_MODEL,
  buildQwenChatCompletionRequest,
  createQwenConfig,
  parseQwenChatCompletion
} from '../src/qwen.js';

test('createQwenConfig uses DashScope Qwen 3.7 Plus defaults without exposing app tokens', () => {
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

  assert.equal(payload.model, 'qwen3.7-plus');
  assert.deepEqual(payload.response_format, { type: 'json_object' });
  assert.equal(payload.enable_thinking, false);
  assert.match(payload.messages[0].content, /JSON/i);
  assert.match(payload.messages[0].content, /CLICK_NODE/);
  assert.match(payload.messages[0].content, /如果无法确定/);
  assert.match(payload.messages[0].content, /页面主题/);
  assert.match(payload.messages[0].content, /主要内容/);
  assert.match(payload.messages[0].content, /可操作项/);
  assert.match(payload.messages[0].content, /风险提示/);
  assert.match(payload.messages[0].content, /简短读屏/);
  assert.match(payload.messages[0].content, /一到两句覆盖页面所有主要类别/);
  assert.match(payload.messages[0].content, /不得只截取前几项/);
  assert.doesNotMatch(payload.messages[0].content, /只保留页面主题和少量主要内容/);
  assert.match(payload.messages[0].content, /详细读屏/);
  assert.match(payload.messages[0].content, /只说明可操作项/);
  assert.match(payload.messages[0].content, /截图/);
  assert.match(payload.messages[0].content, /隐私/);
  assert.equal(typeof payload.messages[1].content, 'string');
  assert.match(payload.messages[1].content, /screen_summary_v2/);
  assert.match(payload.messages[1].content, /屏幕摘要 JSON/);
  assert.match(payload.messages[1].content, /包含敏感信息，禁止截图/);
  assert.doesNotMatch(payload.messages[1].content, /privacy_sensitive_content/);
  assert.match(payload.messages[1].content, /screenshotPolicy/);
  assert.doesNotMatch(payload.messages[1].content, /raw-image-data/);
  assert.doesNotMatch(payload.messages[1].content, /android\.widget\.Button/);
  assert.doesNotMatch(payload.messages[1].content, /屏幕节点 JSON/);
  assert.doesNotMatch(payload.messages[1].content, /com\.android\.settings/);
  assert.doesNotMatch(payload.messages[1].content, /node_0/);
  assert.doesNotMatch(payload.messages[1].content, /"role":"Button"/);
});

test('buildQwenChatCompletionRequest keeps raw nodes only for action planning', () => {
  const payload = buildQwenChatCompletionRequest(validRequest({
    utterance: '点击 WLAN',
    screen: {
      ...validRequest().screen,
      nodes: [{ nodeId: 'node_0', text: 'WLAN', role: 'Button', clickable: true }]
    }
  }), DEFAULT_QWEN_MODEL);

  assert.match(payload.messages[1].content, /屏幕节点 JSON/);
  assert.match(payload.messages[1].content, /node_0/);
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
      nodes: [
        { nodeId: 'technical', text: 'android.widget.Button', role: 'TextView' },
        { nodeId: 'node_0', text: 'WLAN', role: 'Button', clickable: true }
      ],
      screenshotBase64: null,
      screenshotPolicy: {
        attachScreenshot: false,
        reason: 'privacy_sensitive_content',
        privacyBlocked: true
      }
    },
    ...overrides
  };
}
