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

  if (isScreenReadingCommand(utterance)) return createScreenReadingResponse(request);

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

  return createScreenReadingResponse(request);
}

function createScreenReadingResponse(request) {
  const nodes = Array.isArray(request?.screen?.nodes) ? request.screen.nodes : [];
  const labels = nodes
    .map((node) => node.text || node.contentDescription)
    .filter((value) => typeof value === 'string' && value.trim().length > 0)
    .slice(0, 8);

  const spoken = labels.length > 0
    ? `当前页面包含：${labels.join('，')}。`
    : '我暂时没有读取到当前页面的主要文字。';

  return {
    spoken,
    requiresConfirmation: false,
    actions: []
  };
}

function isScreenReadingCommand(utterance) {
  const normalized = normalize(utterance);
  return [
    '这里有什么',
    '当前页面有什么',
    '读一下当前页面',
    '读当前页面',
    '朗读当前页面',
    '看一下当前屏幕',
    '查看当前屏幕',
    '当前屏幕有什么'
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

function invalid(reason) {
  return { valid: false, reason };
}
