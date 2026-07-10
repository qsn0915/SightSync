import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validateAssistRequest,
  validateAssistResponse,
  validateTranscribeRequest,
  createFallbackAssistResponse,
  detectScreenReadingMode,
  validateScreenReadingProviderResponse,
  sanitizeAssistResponse
} from '../src/protocol.js';

test('validateAssistRequest accepts minimal valid request', () => {
  const result = validateAssistRequest({
    sessionId: 'session-1',
    locale: 'zh-CN',
    utterance: '这里有什么',
    screen: {
      packageName: 'com.android.settings',
      activityName: 'Settings',
      nodes: [],
      screenshotBase64: null
    }
  });

  assert.equal(result.valid, true);
});

test('validateAssistRequest rejects missing utterance', () => {
  const result = validateAssistRequest({
    sessionId: 'session-1',
    locale: 'zh-CN',
    screen: { packageName: 'com.android.settings', nodes: [] }
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'utterance is required');
});

test('validateAssistResponse rejects unknown actions', () => {
  const result = validateAssistResponse({
    spoken: '我会执行脚本。',
    requiresConfirmation: false,
    actions: [{ type: 'RUN_SCRIPT' }]
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'unsupported action type: RUN_SCRIPT');
});

test('validateAssistResponse requires nodeId for CLICK_NODE', () => {
  const result = validateAssistResponse({
    spoken: '我会点击。',
    requiresConfirmation: false,
    actions: [{ type: 'CLICK_NODE' }]
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'CLICK_NODE requires nodeId');
});

test('validateAssistResponse accepts a bounded multi-step plan and rejects mixed actions', () => {
  const response = boundedPlanResponse();

  assert.equal(validateAssistResponse(response).valid, true);
  assert.equal(validateAssistResponse({
    ...response,
    actions: [{ type: 'GLOBAL_BACK' }]
  }).reason, 'actions and plan are mutually exclusive');
  assert.equal(validateAssistResponse({
    ...response,
    requiresConfirmation: true
  }).reason, 'plan confirmation must be declared per ACTION step');
});

test('validateAssistResponse requires legacy multi-action responses to use plan', () => {
  const result = validateAssistResponse({
    spoken: '我会连续操作。',
    requiresConfirmation: false,
    actions: [{ type: 'GLOBAL_BACK' }, { type: 'GLOBAL_HOME' }]
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'single-step response allows at most one action; use plan for multiple steps');
});

test('validateAssistResponse rejects unbounded or unsafe plan steps', () => {
  const base = boundedPlanResponse();
  const invalidCases = [
    [{ ...base, plan: { ...base.plan, steps: [base.plan.steps[0]] } }, 'plan supports 2 to 8 steps'],
    [{ ...base, plan: { ...base.plan, steps: Array.from({ length: 9 }, (_, index) => ({
      ...base.plan.steps[0], id: `step_${index}`
    })) } }, 'plan supports 2 to 8 steps'],
    [{ ...base, plan: { ...base.plan, maxDurationMillis: 60001 } }, 'plan maxDurationMillis must be between 1000 and 60000'],
    [{ ...base, plan: { ...base.plan, maxConsecutiveFailures: 3 } }, 'plan maxConsecutiveFailures must be between 1 and 2'],
    [{ ...base, plan: { ...base.plan, maxDurationMillis: 9000 } }, 'plan step timeouts must not exceed maxDurationMillis'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step) => ({ ...step, id: 'duplicate' })) } }, 'plan step ids must be unique'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 0
      ? { ...step, action: { type: 'RUN_SCRIPT' } }
      : step) } }, 'unsupported action type: RUN_SCRIPT'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 0
      ? { ...step, precondition: {} }
      : step) } }, 'plan step open_browser requires a page precondition'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 0
      ? { ...step, timeoutMillis: 20000 }
      : step) } }, 'plan step open_browser timeoutMillis must be between 250 and 15000'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 1
      ? { ...step, kind: 'RUN_SCRIPT' }
      : step) } }, 'unsupported plan step kind: RUN_SCRIPT'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 1
      ? { ...step, action: { type: 'GLOBAL_BACK' } }
      : step) } }, 'WAIT_FOR_UI step wait_browser must not contain action'],
    [{ ...base, plan: { ...base.plan, steps: base.plan.steps.map((step, index) => index === 1
      ? { ...step, requiresConfirmation: true }
      : step) } }, 'WAIT_FOR_UI step wait_browser must not require confirmation']
  ];

  for (const [response, reason] of invalidCases) {
    assert.equal(validateAssistResponse(response).reason, reason);
  }
});

test('sanitizeAssistResponse removes unknown nested plan fields', () => {
  const response = boundedPlanResponse();
  response.plan.providerInternal = 'secret';
  response.plan.steps[0].providerInternal = 'secret';
  response.plan.steps[0].action.providerInternal = 'secret';
  response.plan.steps[0].precondition.providerInternal = 'secret';

  const sanitized = sanitizeAssistResponse(response);

  assert.equal(sanitized.plan.providerInternal, undefined);
  assert.equal(sanitized.plan.steps[0].providerInternal, undefined);
  assert.equal(sanitized.plan.steps[0].action.providerInternal, undefined);
  assert.equal(sanitized.plan.steps[0].precondition.providerInternal, undefined);
  assert.deepEqual(sanitized.plan.steps[0].action, {
    type: 'OPEN_APP',
    appPackage: 'com.android.chrome'
  });
});

test('createFallbackAssistResponse describes current page without actions', () => {
  const response = createFallbackAssistResponse({
    utterance: '这里有什么',
    screen: {
      nodes: [
        { nodeId: 'node_settings', text: '设置', role: 'TextView' },
        { nodeId: 'node_wlan', text: 'WLAN', role: 'Button', clickable: true },
        { nodeId: 'node_bluetooth', text: '蓝牙', role: 'Button', clickable: true }
      ]
    }
  });

  assert.equal(response.requiresConfirmation, false);
  assert.deepEqual(response.actions, []);
  assert.match(response.spoken, /页面主题/);
  assert.match(response.spoken, /主要内容/);
  assert.match(response.spoken, /可操作项/);
  assert.match(response.spoken, /设置/);
  assert.match(response.spoken, /WLAN/);
});

test('createFallbackAssistResponse supports screen reading modes without actions', () => {
  const screen = {
    nodes: [
      { nodeId: 'node_settings', text: '设置', role: 'TextView' },
      { nodeId: 'node_wlan', text: 'WLAN', role: 'Button', clickable: true, actionableType: 'click', region: 'top' },
      {
        nodeId: 'node_search',
        contentDescription: '搜索设置',
        role: 'EditText',
        editable: true,
        actionableType: 'input',
        inputContext: '设置搜索框',
        region: 'top'
      }
    ]
  };

  const brief = createFallbackAssistResponse({ utterance: '简短读屏', screen });
  const detailed = createFallbackAssistResponse({ utterance: '详细读一下当前页面', screen });
  const actionsOnly = createFallbackAssistResponse({ utterance: '只说明可操作项', screen });

  assert.deepEqual(brief.actions, []);
  assert.deepEqual(detailed.actions, []);
  assert.deepEqual(actionsOnly.actions, []);
  assert.match(brief.spoken, /^智能总结暂不可用，先为你朗读当前可见内容。/);
  assert.match(detailed.spoken, /^智能总结暂不可用，先为你朗读当前可见内容。/);
  assert.match(actionsOnly.spoken, /^智能总结暂不可用，先为你朗读当前可见内容。/);
  assert.match(brief.spoken, /简短读屏/);
  assert.doesNotMatch(brief.spoken, /可操作项/);
  assert.match(detailed.spoken, /详细读屏/);
  assert.match(detailed.spoken, /设置搜索框/);
  assert.match(actionsOnly.spoken, /可操作项/);
  assert.match(actionsOnly.spoken, /WLAN/);
  assert.doesNotMatch(actionsOnly.spoken, /主要内容/);
});

test('detectScreenReadingMode recognizes fixed reading commands only', () => {
  assert.equal(detectScreenReadingMode('简短读屏'), 'brief');
  assert.equal(detectScreenReadingMode('详细读屏。'), 'detailed');
  assert.equal(detectScreenReadingMode('只说明可操作项'), 'actions');
  assert.equal(detectScreenReadingMode('这里有什么'), 'standard');
  assert.equal(detectScreenReadingMode('点击 WLAN'), null);
});

test('validateScreenReadingProviderResponse rejects actions and technical output', () => {
  const summary = {
    mainContent: ['设置', 'WLAN'],
    actionableItems: [{ label: 'WLAN' }]
  };

  assert.equal(validateScreenReadingProviderResponse({
    spoken: '当前页面是设置。',
    requiresConfirmation: false,
    actions: [{ type: 'CLICK_NODE', nodeId: 'wlan' }]
  }, summary).valid, false);

  assert.equal(validateScreenReadingProviderResponse({
    spoken: '发现 android.widget.Button 和 node_12。',
    requiresConfirmation: false,
    actions: []
  }, summary).valid, false);
});

test('validateScreenReadingProviderResponse rejects multi-step plans', () => {
  const result = validateScreenReadingProviderResponse(boundedPlanResponse(), {
    mainContent: ['设置'],
    actionableItems: []
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'screen reading response must not contain actions, plan, or confirmation');
});

test('validateScreenReadingProviderResponse allows source names but rejects excessive unknown english', () => {
  const summary = {
    mainContent: ['设置', '蓝心 AI', 'Wi-Fi', 'WLAN'],
    actionableItems: [{ label: 'Bluetooth' }]
  };

  assert.equal(validateScreenReadingProviderResponse({
    spoken: '这是设置页面，可以进入蓝心 AI、Wi-Fi、WLAN 和 Bluetooth。',
    requiresConfirmation: false,
    actions: []
  }, summary).valid, true);

  assert.equal(validateScreenReadingProviderResponse({
    spoken: 'This Settings Control Debug Panel 当前不可用。',
    requiresConfirmation: false,
    actions: []
  }, summary).valid, false);
});

test('createFallbackAssistResponse clicks a unique named target', () => {
  const response = createFallbackAssistResponse({
    utterance: '点击确定',
    screen: {
      nodes: [
        { nodeId: 'node_ok', text: '确定', role: 'Button', clickable: true },
        { nodeId: 'node_cancel', text: '取消', role: 'Button', clickable: true }
      ]
    }
  });

  assert.equal(response.requiresConfirmation, false);
  assert.deepEqual(response.actions, [{ type: 'CLICK_NODE', nodeId: 'node_ok' }]);
  assert.match(response.spoken, /确定/);
});

test('createFallbackAssistResponse asks instead of clicking ambiguous targets', () => {
  const response = createFallbackAssistResponse({
    utterance: '点击确定',
    screen: {
      nodes: [
        { nodeId: 'node_ok_1', text: '确定', role: 'Button', clickable: true },
        { nodeId: 'node_ok_2', text: '确定', role: 'Button', clickable: true }
      ]
    }
  });

  assert.equal(response.requiresConfirmation, false);
  assert.deepEqual(response.actions, []);
  assert.match(response.spoken, /多个/);
});

test('createFallbackAssistResponse maps direct navigation and scroll commands to actions', () => {
  assert.deepEqual(
    createFallbackAssistResponse({ utterance: '返回', screen: { nodes: [] } }).actions,
    [{ type: 'GLOBAL_BACK' }]
  );
  assert.deepEqual(
    createFallbackAssistResponse({ utterance: '回到主页', screen: { nodes: [] } }).actions,
    [{ type: 'GLOBAL_HOME' }]
  );
  assert.deepEqual(
    createFallbackAssistResponse({ utterance: '向下滚动', screen: { nodes: [] } }).actions,
    [{ type: 'SCROLL_FORWARD' }]
  );
  assert.deepEqual(
    createFallbackAssistResponse({ utterance: '向上滚动', screen: { nodes: [] } }).actions,
    [{ type: 'SCROLL_BACKWARD' }]
  );
});

test('createFallbackAssistResponse enters text into the only editable node', () => {
  const response = createFallbackAssistResponse({
    utterance: '输入：测试内容',
    screen: {
      nodes: [
        { nodeId: 'node_title', text: '搜索', role: 'TextView', editable: false },
        { nodeId: 'node_input', text: '', role: 'EditText', editable: true }
      ]
    }
  });

  assert.equal(response.requiresConfirmation, false);
  assert.deepEqual(response.actions, [
    { type: 'SET_TEXT', nodeId: 'node_input', text: '测试内容' }
  ]);
  assert.match(response.spoken, /测试内容/);
});

test('createFallbackAssistResponse marks high risk clicks for confirmation', () => {
  const response = createFallbackAssistResponse({
    utterance: '点击删除',
    screen: {
      nodes: [
        { nodeId: 'node_delete', text: '删除', role: 'Button', clickable: true }
      ]
    }
  });

  assert.equal(response.requiresConfirmation, true);
  assert.deepEqual(response.actions, [{ type: 'CLICK_NODE', nodeId: 'node_delete' }]);
});

test('validateTranscribeRequest accepts short audio payloads', () => {
  const result = validateTranscribeRequest({
    locale: 'zh-CN',
    mimeType: 'audio/mp4',
    audioBase64: 'AAAA'
  });

  assert.equal(result.valid, true);
});

test('validateTranscribeRequest rejects unsupported audio mime types', () => {
  const result = validateTranscribeRequest({
    locale: 'zh-CN',
    mimeType: 'text/plain',
    audioBase64: 'AAAA'
  });

  assert.equal(result.valid, false);
  assert.equal(result.reason, 'unsupported audio mimeType: text/plain');
});

function boundedPlanResponse() {
  return {
    spoken: '我会分步完成。',
    requiresConfirmation: false,
    actions: [],
    plan: {
      goal: '打开浏览器并等待页面稳定',
      maxDurationMillis: 10000,
      maxConsecutiveFailures: 2,
      steps: [
        {
          id: 'open_browser',
          kind: 'ACTION',
          action: { type: 'OPEN_APP', appPackage: 'com.android.chrome' },
          precondition: { packageName: 'com.android.launcher' },
          timeoutMillis: 5000,
          requiresConfirmation: false
        },
        {
          id: 'wait_browser',
          kind: 'WAIT_FOR_UI',
          precondition: { packageName: 'com.android.chrome' },
          timeoutMillis: 5000,
          requiresConfirmation: false
        }
      ]
    }
  };
}
