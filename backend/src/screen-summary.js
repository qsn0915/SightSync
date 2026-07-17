const MAX_MAIN_CONTENT = 12;
const MAX_ACTIONABLE_ITEMS = 10;
const RISK_KEYWORDS = ['支付', '付款', '转账', '订单', '删除', '发送', '提交', '验证码', '银行卡', '密码'];
const DEGRADED_READING_PREFIX = '智能总结暂不可用，先为你朗读当前可见内容。';
const TECHNICAL_ROLE_LABELS = new Set([
  'Button',
  'EditText',
  'FrameLayout',
  'ImageView',
  'LinearLayout',
  'RecyclerView',
  'ScrollView',
  'TextView',
  'View',
  'ViewGroup'
]);

export function buildScreenSummary(screen = {}) {
  const nodes = Array.isArray(screen.nodes) ? screen.nodes : [];
  const childrenByParent = groupChildrenByParent(nodes);
  const labeled = nodes
    .map((node) => nodeLabel(node, childrenByParent))
    .filter(Boolean);
  const mainContent = unique(labeled).slice(0, MAX_MAIN_CONTENT);
  const actionableItems = nodes
    .filter(isActionable)
    .map((node) => ({
      nodeId: node.nodeId,
      label: nodeLabel(node, childrenByParent) || '未命名控件',
      role: node.role || 'Unknown',
      type: node.actionableType || (node.editable === true ? 'input' : node.scrollable === true ? 'scroll' : 'click'),
      region: node.region || null,
      inputContext: userFacingLabel(node.inputContext),
      scrollContainerNodeId: node.scrollContainerNodeId || null
    }))
    .filter((item) => item.nodeId && item.label)
    .slice(0, MAX_ACTIONABLE_ITEMS);
  const riskHints = RISK_KEYWORDS.filter((keyword) =>
    `${screen.packageName ?? ''} ${screen.activityName ?? ''} ${labeled.join(' ')}`.includes(keyword)
  );

  return {
    protocol: 'screen_summary_v2',
    page: {
      packageName: screen.packageName || '',
      activityName: screen.activityName || null,
      title: mainContent[0] || userFacingLabel(screen.activityName),
      screenshotAttached: typeof screen.screenshotBase64 === 'string' && screen.screenshotBase64.trim().length > 0,
      screenshotPolicy: normalizeScreenshotPolicy(screen.screenshotPolicy)
    },
    nodeStats: {
      total: nodes.length,
      labeled: labeled.length,
      clickable: nodes.filter((node) => node.clickable === true).length,
      editable: nodes.filter((node) => node.editable === true).length,
      scrollable: nodes.filter((node) => node.scrollable === true).length
    },
    mainContent,
    actionableItems,
    riskHints: unique(riskHints)
  };
}

export function buildScreenSummarySpoken(summary, options = {}) {
  const mode = typeof options === 'string' ? options : options.mode;
  const degraded = typeof options === 'object' && options.degraded === true;

  if (mode === 'actions') {
    if (!summary.actionableItems?.length) {
      return withDegradedPrefix('当前页面暂时没有识别到可操作项。', degraded);
    }
    return withDegradedPrefix(
      `可操作项有：${summary.actionableItems.map(describeActionItem).join('，')}。`,
      degraded
    );
  }

  if (!summary.mainContent?.length) {
    return withDegradedPrefix('我暂时没有读取到当前页面的主要文字。', degraded);
  }

  const pageTitle = summary.page?.title || summary.mainContent[0];
  if (mode === 'brief') {
    return withDegradedPrefix(
      `简短读屏：当前页面是${pageTitle}。主要内容：${summary.mainContent.join('，')}。`,
      degraded
    );
  }

  if (mode === 'detailed') {
    const parts = [`详细读屏：页面主题：${pageTitle}。主要内容：${summary.mainContent.join('，')}。`];
    if (summary.actionableItems?.length) {
      parts.push(`可操作项：${summary.actionableItems.map(describeActionItem).join('，')}。`);
    }
    if (summary.riskHints?.length) {
      parts.push(`风险提示：页面可能涉及${summary.riskHints.join('、')}，相关操作需要谨慎确认。`);
    }
    return withDegradedPrefix(parts.join(''), degraded);
  }

  const parts = [`当前页面主题可能是：${pageTitle}。主要内容有：${summary.mainContent.join('，')}。`];
  if (summary.actionableItems?.length) {
    parts.push(`可操作项有：${summary.actionableItems.map((item) => item.label).join('，')}。`);
  }
  if (summary.riskHints?.length) {
    parts.push(`我注意到页面可能涉及${summary.riskHints.join('、')}，相关操作需要谨慎确认。`);
  }
  return withDegradedPrefix(parts.join(''), degraded);
}

function isActionable(node) {
  return node?.clickable === true || node?.editable === true || node?.scrollable === true;
}

function nodeLabel(node, childrenByParent = new Map()) {
  const candidates = [node?.text, node?.contentDescription]
    .map(userFacingLabel)
    .filter(Boolean);
  const queue = [...(childrenByParent.get(node?.nodeId) || [])];
  const visited = new Set();
  while (queue.length > 0) {
    const child = queue.shift();
    if (!child || visited.has(child.nodeId)) continue;
    visited.add(child.nodeId);
    candidates.push(
      ...[child.text, child.contentDescription]
        .map(userFacingLabel)
        .filter(Boolean)
    );
    queue.push(...(childrenByParent.get(child.nodeId) || []));
  }
  return candidates.find(containsChinese) || candidates[0] || null;
}

function groupChildrenByParent(nodes) {
  const result = new Map();
  for (const node of nodes) {
    if (!node?.parentNodeId) continue;
    const children = result.get(node.parentNodeId) || [];
    children.push(node);
    result.set(node.parentNodeId, children);
  }
  return result;
}

function userFacingLabel(value) {
  if (typeof value !== 'string') return null;
  const label = value.trim();
  if (!label || isTechnicalLabel(label)) return null;
  return label;
}

function isTechnicalLabel(label) {
  if (TECHNICAL_ROLE_LABELS.has(label)) return true;
  if (/^node_\d+$/iu.test(label)) return true;
  if (/^[a-z][\w.]*:[a-z]+\/[\w.]+$/iu.test(label)) return true;
  return /^(?:android|com|org|net)\.[a-z0-9_.]+$/iu.test(label);
}

function containsChinese(value) {
  return /\p{Script=Han}/u.test(value);
}

function normalizeScreenshotPolicy(policy) {
  if (!policy || typeof policy !== 'object') return null;
  return {
    attachScreenshot: policy.attachScreenshot === true,
    reason: typeof policy.reason === 'string' ? policy.reason : null,
    privacyBlocked: policy.privacyBlocked === true
  };
}

function describeActionItem(item) {
  const details = [
    roleLabel(item.role),
    actionTypeLabel(item.type),
    regionLabel(item.region),
    item.inputContext ? `上下文：${item.inputContext}` : null
  ].filter(Boolean);
  return details.length ? `${item.label}（${details.join('，')}）` : item.label;
}

function actionTypeLabel(type) {
  return {
    click: '可点击',
    input: '输入框',
    scroll: '可滚动'
  }[type] || null;
}

function regionLabel(region) {
  return {
    top: '上方',
    middle: '中部',
    bottom: '下方'
  }[region] || null;
}

function roleLabel(role) {
  return {
    Button: '按钮',
    EditText: '文本框',
    ScrollView: '滚动区域'
  }[role] || null;
}

function unique(values) {
  return [...new Set(values)];
}

function withDegradedPrefix(spoken, degraded) {
  return degraded ? `${DEGRADED_READING_PREFIX}${spoken}` : spoken;
}
