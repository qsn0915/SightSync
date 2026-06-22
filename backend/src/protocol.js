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

export function validateAssistRequest(body) {
  if (!body || typeof body !== 'object') return invalid('request body is required');
  if (!isNonEmptyString(body.sessionId)) return invalid('sessionId is required');
  if (!isNonEmptyString(body.locale)) return invalid('locale is required');
  if (!isNonEmptyString(body.utterance)) return invalid('utterance is required');
  if (!body.screen || typeof body.screen !== 'object') return invalid('screen is required');
  if (!isNonEmptyString(body.screen.packageName)) return invalid('screen.packageName is required');
  if (!Array.isArray(body.screen.nodes)) return invalid('screen.nodes must be an array');
  return { valid: true };
}

export function validateAssistResponse(response) {
  if (!response || typeof response !== 'object') return invalid('response is required');
  if (!isNonEmptyString(response.spoken)) return invalid('spoken is required');
  if (typeof response.requiresConfirmation !== 'boolean') {
    return invalid('requiresConfirmation must be boolean');
  }
  if (!Array.isArray(response.actions)) return invalid('actions must be an array');

  for (const action of response.actions) {
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
  }

  return { valid: true };
}

export function validateTranscribeRequest(body) {
  if (!body || typeof body !== 'object') return invalid('request body is required');
  if (!isNonEmptyString(body.audioBase64)) return invalid('audioBase64 is required');
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
  if (response.requiresConfirmation || response.actions.length > 0) {
    return invalid('screen reading response must not contain actions or confirmation');
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

  return {
    spoken: response.spoken,
    requiresConfirmation: response.requiresConfirmation,
    actions: response.actions
  };
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
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
