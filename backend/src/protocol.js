import { buildScreenSummary, buildScreenSummarySpoken } from './screen-summary.js';

const ALLOWED_ACTIONS = new Set([
  'SPEAK',
  'CLICK_NODE',
  'SET_TEXT',
  'SCROLL_FORWARD',
  'SCROLL_BACKWARD',
  'GLOBAL_BACK',
  'GLOBAL_HOME',
  'OPEN_APP'
]);

const ALLOWED_PLAN_STEP_KINDS = new Set([
  'ACTION',
  'WAIT_FOR_UI',
  'VALIDATE_PAGE'
]);

const MIN_PLAN_STEPS = 2;
const MAX_PLAN_STEPS = 8;
const MIN_PLAN_DURATION_MILLIS = 1000;
const MAX_PLAN_DURATION_MILLIS = 60000;
const MIN_PLAN_STEP_TIMEOUT_MILLIS = 250;
const MAX_PLAN_STEP_TIMEOUT_MILLIS = 15000;
const MAX_PLAN_CONSECUTIVE_FAILURES = 2;

const ALLOWED_AUDIO_MIME_TYPES = new Set([
  'audio/mp4',
  'audio/m4a',
  'audio/aac',
  'audio/mpeg',
  'audio/wav',
  'audio/x-wav',
  'audio/webm'
]);

const HIGH_RISK_KEYWORDS = [
  '支付',
  '付款',
  '转账',
  '购买',
  '删除',
  '清空',
  '发送',
  '提交',
  '确认订单',
  '注销',
  '退出登录'
];

const MAX_SESSION_ID_LENGTH = 128;
const MAX_LOCALE_LENGTH = 32;
const MAX_UTTERANCE_LENGTH = 4_000;
const MAX_NODE_COUNT = 200;
const MAX_NODE_TEXT_LENGTH = 1_000;
const MAX_SCREENSHOT_BASE64_LENGTH = 900_000;
const MAX_AUDIO_BASE64_LENGTH = 900_000;

export function validateAssistRequest(body) {
  if (!isPlainObject(body)) return invalid('request body is required');
  if (!isNonEmptyString(body.sessionId)) return invalid('sessionId is required');
  if (body.sessionId.length > MAX_SESSION_ID_LENGTH) return invalid('sessionId is too long');
  if (!isNonEmptyString(body.locale)) return invalid('locale is required');
  if (body.locale.length > MAX_LOCALE_LENGTH) return invalid('locale is too long');
  if (!isNonEmptyString(body.utterance)) return invalid('utterance is required');
  if (body.utterance.length > MAX_UTTERANCE_LENGTH) return invalid('utterance is too long');
  if (!isPlainObject(body.screen)) return invalid('screen is required');
  if (!isNonEmptyString(body.screen.packageName)) return invalid('screen.packageName is required');
  if (body.screen.packageName.length > 255) return invalid('screen.packageName is too long');
  if (body.screen.activityName != null && typeof body.screen.activityName !== 'string') {
    return invalid('screen.activityName must be a string or null');
  }
  if (!Array.isArray(body.screen.nodes)) return invalid('screen.nodes must be an array');
  if (body.screen.nodes.length > MAX_NODE_COUNT) return invalid('screen.nodes exceeds limit');
  for (const node of body.screen.nodes) {
    const nodeValidation = validateScreenNode(node);
    if (!nodeValidation.valid) return nodeValidation;
  }
  if (body.screen.screenshotBase64 != null) {
    if (typeof body.screen.screenshotBase64 !== 'string') {
      return invalid('screen.screenshotBase64 must be a string or null');
    }
    if (body.screen.screenshotBase64.length > MAX_SCREENSHOT_BASE64_LENGTH) {
      return invalid('screen.screenshotBase64 is too large');
    }
    if (!isValidBase64(body.screen.screenshotBase64)) {
      return invalid('screen.screenshotBase64 must be valid base64');
    }
  }
  return { valid: true };
}

export function validateAssistResponse(response) {
  if (!response || typeof response !== 'object') return invalid('response is required');
  if (!isNonEmptyString(response.spoken)) return invalid('spoken is required');
  if (typeof response.requiresConfirmation !== 'boolean') {
    return invalid('requiresConfirmation must be boolean');
  }
  if (!Array.isArray(response.actions)) return invalid('actions must be an array');
  if (response.actions.length > 1) return invalid('only one action is allowed');

  if (response.plan != null && response.actions.length > 0) {
    return invalid('actions and plan are mutually exclusive');
  }
  if (response.plan != null && response.requiresConfirmation) {
    return invalid('plan confirmation must be declared per ACTION step');
  }
  if (response.plan == null && response.actions.length > 1) {
    return invalid('single-step response allows at most one action; use plan for multiple steps');
  }

  for (const action of response.actions) {
    const actionValidation = validateAction(action);
    if (!actionValidation.valid) return actionValidation;
  }

  if (response.plan != null) {
    const planValidation = validateAgentPlan(response.plan);
    if (!planValidation.valid) return planValidation;
  }

  return { valid: true };
}

export function validateTranscribeRequest(body) {
  if (!isPlainObject(body)) return invalid('request body is required');
  if (!isNonEmptyString(body.locale)) return invalid('locale is required');
  if (body.locale.length > MAX_LOCALE_LENGTH) return invalid('locale is too long');
  if (!isNonEmptyString(body.audioBase64)) return invalid('audioBase64 is required');
  if (body.audioBase64.length > MAX_AUDIO_BASE64_LENGTH) return invalid('audioBase64 is too large');
  if (!isValidBase64(body.audioBase64)) return invalid('audioBase64 must be valid base64');
  if (!isNonEmptyString(body.mimeType)) return invalid('mimeType is required');
  if (!ALLOWED_AUDIO_MIME_TYPES.has(body.mimeType)) {
    return invalid(`unsupported audio mimeType: ${body.mimeType}`);
  }
  return { valid: true };
}

export function createLocalAssistResponse(request) {
  const utterance = typeof request?.utterance === 'string' ? request.utterance.trim() : '';
  if (!utterance) return null;

  const direct = createDirectActionResponse(utterance);
  if (direct) return direct;

  const input = parseInputCommand(utterance);
  if (input !== null) return createInputResponse(request, input);

  const clickTarget = parseClickCommand(utterance);
  if (clickTarget !== null) return createClickResponse(request, clickTarget);

  return null;
}

export function createFallbackAssistResponse(request) {
  const localResponse = createLocalAssistResponse(request);
  if (localResponse) return localResponse;

  return createScreenReadingFallbackResponse(request);
}

export function createScreenReadingFallbackResponse(request, requestedMode) {
  const mode = requestedMode || detectScreenReadingMode(request?.utterance) || 'standard';
  const summary = buildScreenSummary(request?.screen);
  return {
    spoken: buildScreenSummarySpoken(summary, { mode, degraded: true }),
    requiresConfirmation: false,
    actions: []
  };
}

export function detectScreenReadingMode(utterance) {
  const normalized = normalize(utterance);

  if (isActionItemsReadingCommand(normalized)) return 'actions';
  if (isDetailedReadingCommand(normalized)) return 'detailed';
  if (isBriefReadingCommand(normalized)) return 'brief';
  if ([
    '这里有什么',
    '当前页面有什么',
    '读一下当前页面',
    '读当前页面',
    '朗读当前页面',
    '看一下当前屏幕',
    '查看当前屏幕',
    '当前屏幕有什么'
  ].includes(normalized)) return 'standard';

  return null;
}

export function validateScreenReadingProviderResponse(response, summary) {
  const protocol = validateAssistResponse(response);
  if (!protocol.valid) return protocol;
  if (response.requiresConfirmation || response.actions.length > 0 || response.plan != null) {
    return invalid('screen reading response must not contain actions, plan, or confirmation');
  }
  if (containsTechnicalIdentifier(response.spoken)) {
    return invalid('screen reading response contains technical identifiers');
  }

  const allowedLatin = collectAllowedLatinTokens(summary);
  const unknownLatin = latinTokens(response.spoken)
    .filter((token) => !allowedLatin.has(normalizeLatinToken(token)));
  if (new Set(unknownLatin.map(normalizeLatinToken)).size > 2) {
    return invalid('screen reading response contains excessive unknown english');
  }
  return { valid: true };
}

function isActionItemsReadingCommand(normalized) {
  return [
    '可操作项',
    '有哪些可操作项',
    '当前页面有哪些可操作项',
    '当前页面有什么可操作项',
    '这里有哪些可操作项',
    '当前屏幕有哪些可操作项',
    '当前页面能点什么',
    '这里能点什么',
    '当前页面可以点什么',
    '有哪些按钮',
    '只说明可操作项',
    '只说可操作项',
    '只读可操作项'
  ].includes(normalized);
}

function isDetailedReadingCommand(normalized) {
  return [
    '详细读屏',
    '详细读一下当前页面',
    '详细朗读当前页面',
    '详细说明当前页面',
    '读详细一点',
    '详细看一下当前屏幕'
  ].includes(normalized);
}

function isBriefReadingCommand(normalized) {
  return [
    '简短读屏',
    '简单读屏',
    '快速读屏',
    '简短读一下当前页面',
    '简单读一下当前页面',
    '简单说一下当前页面'
  ].includes(normalized);
}

export function sanitizeAssistResponse(response) {
  const result = validateAssistResponse(response);
  if (!result.valid) {
    throw new Error(result.reason);
  }

  const sanitized = {
    spoken: response.spoken,
    requiresConfirmation: response.requiresConfirmation,
    actions: response.actions.map(sanitizeAction)
  };
  if (response.plan != null) {
    sanitized.plan = sanitizeAgentPlan(response.plan);
  }
  return sanitized;
}

function validateAction(action) {
  if (!action || typeof action !== 'object') return invalid('action must be an object');
  if (!ALLOWED_ACTIONS.has(action.type)) return invalid(`unsupported action type: ${action.type}`);
  if (action.type === 'CLICK_NODE' && !isNonEmptyString(action.nodeId)) {
    return invalid('CLICK_NODE requires nodeId');
  }
  if (action.type === 'SET_TEXT') {
    if (!isNonEmptyString(action.nodeId)) return invalid('SET_TEXT requires nodeId');
    if (typeof action.text !== 'string') return invalid('SET_TEXT requires text');
  }
  if (action.type === 'OPEN_APP' && !isNonEmptyString(action.appPackage)) {
    return invalid('OPEN_APP requires appPackage');
  }
  return { valid: true };
}

function validateAgentPlan(plan) {
  if (!plan || typeof plan !== 'object') return invalid('plan must be an object');
  if (!isNonEmptyString(plan.goal)) return invalid('plan goal is required');
  if (!Array.isArray(plan.steps) ||
      plan.steps.length < MIN_PLAN_STEPS ||
      plan.steps.length > MAX_PLAN_STEPS) {
    return invalid('plan supports 2 to 8 steps');
  }
  if (!Number.isInteger(plan.maxDurationMillis) ||
      plan.maxDurationMillis < MIN_PLAN_DURATION_MILLIS ||
      plan.maxDurationMillis > MAX_PLAN_DURATION_MILLIS) {
    return invalid('plan maxDurationMillis must be between 1000 and 60000');
  }
  if (!Number.isInteger(plan.maxConsecutiveFailures) ||
      plan.maxConsecutiveFailures < 1 ||
      plan.maxConsecutiveFailures > MAX_PLAN_CONSECUTIVE_FAILURES) {
    return invalid('plan maxConsecutiveFailures must be between 1 and 2');
  }

  const stepIds = new Set();
  let totalStepTimeoutMillis = 0;
  for (const step of plan.steps) {
    if (!step || typeof step !== 'object') return invalid('plan step must be an object');
    if (!isNonEmptyString(step.id)) return invalid('plan step id is required');
    if (stepIds.has(step.id)) return invalid('plan step ids must be unique');
    stepIds.add(step.id);

    if (!ALLOWED_PLAN_STEP_KINDS.has(step.kind)) {
      return invalid(`unsupported plan step kind: ${step.kind}`);
    }
    if (!Number.isInteger(step.timeoutMillis) ||
        step.timeoutMillis < MIN_PLAN_STEP_TIMEOUT_MILLIS ||
        step.timeoutMillis > MAX_PLAN_STEP_TIMEOUT_MILLIS) {
      return invalid(`plan step ${step.id} timeoutMillis must be between 250 and 15000`);
    }
    if (typeof step.requiresConfirmation !== 'boolean') {
      return invalid(`plan step ${step.id} requiresConfirmation must be boolean`);
    }
    const expectationValidation = validatePageExpectation(step.precondition, step.id);
    if (!expectationValidation.valid) return expectationValidation;

    if (step.kind === 'ACTION') {
      if (step.action == null) return invalid(`ACTION step ${step.id} requires action`);
      const actionValidation = validateAction(step.action);
      if (!actionValidation.valid) return actionValidation;
    } else {
      if (step.action != null) return invalid(`${step.kind} step ${step.id} must not contain action`);
      if (step.requiresConfirmation) {
        return invalid(`${step.kind} step ${step.id} must not require confirmation`);
      }
    }

    totalStepTimeoutMillis += step.timeoutMillis;
  }

  if (totalStepTimeoutMillis > plan.maxDurationMillis) {
    return invalid('plan step timeouts must not exceed maxDurationMillis');
  }
  return { valid: true };
}

function validatePageExpectation(expectation, stepId) {
  if (!expectation || typeof expectation !== 'object') {
    return invalid(`plan step ${stepId} requires a page precondition`);
  }
  if (expectation.requiredNodeIds != null && !Array.isArray(expectation.requiredNodeIds)) {
    return invalid(`plan step ${stepId} requiredNodeIds must be an array`);
  }
  if (expectation.requiredTexts != null && !Array.isArray(expectation.requiredTexts)) {
    return invalid(`plan step ${stepId} requiredTexts must be an array`);
  }
  const hasCondition = isNonEmptyString(expectation.packageName) ||
    isNonEmptyString(expectation.activityName) ||
    expectation.requiredNodeIds?.some(isNonEmptyString) === true ||
    expectation.requiredTexts?.some(isNonEmptyString) === true;
  return hasCondition
    ? { valid: true }
    : invalid(`plan step ${stepId} requires a page precondition`);
}

function sanitizeAgentPlan(plan) {
  return {
    goal: plan.goal,
    maxDurationMillis: plan.maxDurationMillis,
    maxConsecutiveFailures: plan.maxConsecutiveFailures,
    steps: plan.steps.map((step) => ({
      id: step.id,
      kind: step.kind,
      ...(step.action == null ? {} : { action: sanitizeAction(step.action) }),
      precondition: sanitizePageExpectation(step.precondition),
      timeoutMillis: step.timeoutMillis,
      requiresConfirmation: step.requiresConfirmation
    }))
  };
}

function sanitizePageExpectation(expectation) {
  return Object.fromEntries(
    ['packageName', 'activityName', 'requiredNodeIds', 'requiredTexts']
      .filter((key) => expectation[key] !== undefined)
      .map((key) => [key, Array.isArray(expectation[key]) ? [...expectation[key]] : expectation[key]])
  );
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validateScreenNode(node) {
  if (!isPlainObject(node)) return invalid('screen node must be an object');
  for (const field of ['nodeId', 'text', 'contentDescription', 'role']) {
    if (node[field] != null && typeof node[field] !== 'string') {
      return invalid(`screen node ${field} must be a string or null`);
    }
    if (typeof node[field] === 'string' && node[field].length > MAX_NODE_TEXT_LENGTH) {
      return invalid(`screen node ${field} is too long`);
    }
  }
  for (const field of ['clickable', 'editable', 'scrollable', 'sensitive']) {
    if (node[field] != null && typeof node[field] !== 'boolean') {
      return invalid(`screen node ${field} must be boolean`);
    }
  }
  return { valid: true };
}

function isValidBase64(value) {
  return value.length > 0 &&
    value.length % 4 === 0 &&
    /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(value);
}

function sanitizeAction(action) {
  switch (action.type) {
    case 'CLICK_NODE':
      return { type: action.type, nodeId: action.nodeId };
    case 'SET_TEXT':
      return { type: action.type, nodeId: action.nodeId, text: action.text };
    case 'OPEN_APP':
      return { type: action.type, appPackage: action.appPackage };
    default:
      return { type: action.type };
  }
}

function createDirectActionResponse(utterance) {
  const normalized = normalize(utterance);

  if (['返回', '后退', '回退', '返回上一页'].includes(normalized)) {
    return commandResponse('好的，我会返回。', { type: 'GLOBAL_BACK' }, utterance);
  }
  if (['主页', '回到主页', '回主页', '返回主页', '回到桌面', '回桌面', '返回桌面'].includes(normalized)) {
    return commandResponse('好的，我会回到主页。', { type: 'GLOBAL_HOME' }, utterance);
  }
  if (['向下滚动', '往下滚动', '下滑', '往下滑', '向下滑动', '下一页'].includes(normalized)) {
    return commandResponse('好的，我会向下滚动。', { type: 'SCROLL_FORWARD' }, utterance);
  }
  if (['向上滚动', '往上滚动', '上滑', '往上滑', '向上滑动', '上一页'].includes(normalized)) {
    return commandResponse('好的，我会向上滚动。', { type: 'SCROLL_BACKWARD' }, utterance);
  }

  return null;
}

function createInputResponse(request, text) {
  if (!text.trim()) {
    return noAction('请告诉我要输入的内容。');
  }

  const editableNodes = getNodes(request).filter((node) => node.editable === true);
  if (editableNodes.length === 0) {
    return noAction('当前页面没有找到可输入的文本框。');
  }
  if (editableNodes.length > 1) {
    return noAction('页面上有多个输入框，请说明输入到哪个位置。');
  }

  const action = {
    type: 'SET_TEXT',
    nodeId: editableNodes[0].nodeId,
    text: text.trim()
  };
  return commandResponse(`我会输入：${text.trim()}。`, action, `${request?.utterance ?? ''} ${text}`);
}

function createClickResponse(request, target) {
  if (!target.trim()) {
    return noAction('请告诉我要点击哪个控件。');
  }

  const candidates = getNodes(request)
    .filter((node) => node.clickable === true)
    .filter((node) => nodeMatchesTarget(node, target));

  if (candidates.length === 0) {
    return noAction(`我没有找到“${target.trim()}”，请换个说法或先读一下当前页面。`);
  }
  if (candidates.length > 1) {
    return noAction(`我找到了多个“${target.trim()}”，请说得更具体。`);
  }

  const node = candidates[0];
  const label = nodeLabel(node) || target.trim();
  return commandResponse(
    `我会点击${label}。`,
    { type: 'CLICK_NODE', nodeId: node.nodeId },
    `${request?.utterance ?? ''} ${label}`
  );
}

function parseInputCommand(utterance) {
  const match = utterance.match(/^\s*(?:输入|写入|填写)\s*[：:]\s*(.+)$/u) ||
    utterance.match(/^\s*(?:输入|写入|填写)\s+(.+)$/u);
  return match ? match[1].trim() : null;
}

function parseClickCommand(utterance) {
  const match = utterance.match(/^\s*(?:点击|点一下|点|按下|选择|进入|打开)\s*(.+)$/u);
  return match ? stripWrappingPunctuation(match[1]) : null;
}

function commandResponse(spoken, action, riskText) {
  return {
    spoken,
    requiresConfirmation: isHighRisk(riskText),
    actions: [action]
  };
}

function noAction(spoken) {
  return {
    spoken,
    requiresConfirmation: false,
    actions: []
  };
}

function getNodes(request) {
  return Array.isArray(request?.screen?.nodes) ? request.screen.nodes : [];
}

function nodeMatchesTarget(node, target) {
  const normalizedTarget = normalize(target);
  if (!normalizedTarget) return false;

  return [node.text, node.contentDescription]
    .filter((value) => typeof value === 'string' && value.trim().length > 0)
    .map(normalize)
    .some((label) => label.includes(normalizedTarget) || normalizedTarget.includes(label));
}

function nodeLabel(node) {
  return [node.text, node.contentDescription]
    .find((value) => typeof value === 'string' && value.trim().length > 0)
    ?.trim();
}

function isHighRisk(text) {
  return HIGH_RISK_KEYWORDS.some((keyword) => text.includes(keyword));
}

function normalize(value) {
  return String(value ?? '')
    .toLowerCase()
    .replace(/[\s，。！？、,.!?:：；;“”"'（）()[\]【】]/gu, '');
}

function stripWrappingPunctuation(value) {
  return value.trim().replace(/^[“"'「『【（(]+|[”"'」』】）)]+$/gu, '').trim();
}

function containsTechnicalIdentifier(value) {
  return /(?:^(?:android|com|org|net)\.[a-z0-9_.]+|\bnode_\d+\b|[a-z][\w.]*:[a-z]+\/[\w.]+)/iu.test(value) ||
    /\b(?:Button|EditText|FrameLayout|ImageView|LinearLayout|RecyclerView|ScrollView|TextView|ViewGroup)\b/u.test(value);
}

function collectAllowedLatinTokens(summary) {
  const sourceLabels = [
    ...(summary?.mainContent || []),
    ...(summary?.actionableItems || []).map((item) => item?.label),
    'AI',
    'Wi-Fi',
    'WLAN',
    'Bluetooth'
  ];
  return new Set(
    sourceLabels
      .flatMap(latinTokens)
      .map(normalizeLatinToken)
      .filter(Boolean)
  );
}

function latinTokens(value) {
  if (typeof value !== 'string') return [];
  return value.match(/[A-Za-z]+(?:-[A-Za-z]+)*/gu) || [];
}

function normalizeLatinToken(value) {
  return value.toLowerCase().replace(/-/gu, '');
}

function invalid(reason) {
  return { valid: false, reason };
}
