import { buildScreenSummary } from './screen-summary.js';
import { detectScreenReadingMode } from './protocol.js';

export const DEFAULT_QWEN_ENDPOINT = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions';
export const DEFAULT_QWEN_MODEL = 'qwen3.7-plus';

const SYSTEM_PROMPT = `你是 SightSync Android 盲人 AI 无障碍助手 V1 的规划器。
你必须只返回一个 JSON object，不要返回 Markdown、解释文字、脚本或自由执行命令。
JSON 结构必须是：
{
  "spoken": "要朗读给用户的中文",
  "requiresConfirmation": false,
  "actions": []
}

允许的 actions.type 只有：SPEAK、CLICK_NODE、SET_TEXT、SCROLL_FORWARD、SCROLL_BACKWARD、GLOBAL_BACK、GLOBAL_HOME、OPEN_APP。
CLICK_NODE 必须使用当前 screen.nodes 中存在且明确匹配的 nodeId。
SET_TEXT 必须包含 nodeId 和 text。
OPEN_APP 必须包含 appPackage。

规则：
- 用户问“这里有什么”“读一下当前页面”等理解类问题时，只朗读 spoken，actions 必须为空；spoken 必须按“页面主题、主要内容、可操作项、风险提示”组织，无法确定时要说明不确定，不要编造。
- 用户要求“简短读屏”时，spoken 应用一到两句覆盖页面所有主要类别，可以归类合并但不得只截取前几项或逐项展开；用户要求“详细读屏”时，spoken 应包含页面主题、主要内容、可操作项和风险提示；用户要求“只说明可操作项”或“有哪些可操作项”时，spoken 只说明可操作控件，actions 必须为空。
- 读屏 spoken 必须使用自然中文；不要朗读包名、activity、nodeId、role、资源 ID、Android 类名或 JSON 字段名。只保留页面真实出现的品牌名以及 AI、Wi-Fi、WLAN、Bluetooth 等常见名称。
- 截图只作为 Android 端按隐私策略附加的辅助材料；当 screen_summary_v2.page.screenshotPolicy.privacyBlocked 为 true 或截图不存在时，不要要求截图，不要编造视觉内容。
- 用户要求点击、输入、滚动、返回、主页、打开应用时，只有目标明确才返回动作。
- 如果无法确定目标、存在多个候选或页面信息不足，spoken 必须追问用户，actions 必须为空，不要猜测执行。
- 不要返回白名单之外的动作，不要编造 nodeId。
- 涉及支付、删除、发送消息、提交表单等高风险操作时，requiresConfirmation 必须为 true。`;

export function createQwenConfig(env = process.env) {
  return {
    baseUrl: env.QWEN_BASE_URL || env.AI_BASE_URL || DEFAULT_QWEN_ENDPOINT,
    apiKey: env.QWEN_API_KEY || env.DASHSCOPE_API_KEY || env.AI_API_KEY,
    model: env.QWEN_MODEL || env.AI_MODEL || DEFAULT_QWEN_MODEL,
    asrModel: env.QWEN_ASR_MODEL || 'qwen3-asr-flash'
  };
}

export function isQwenConfigured(config) {
  return Boolean(config?.baseUrl && config?.apiKey);
}

export function buildQwenChatCompletionRequest(request, model = DEFAULT_QWEN_MODEL) {
  const screenSummary = buildScreenSummary(request?.screen);
  const screenReadingMode = detectScreenReadingMode(request?.utterance);
  const providerSummary = screenReadingMode
    ? buildScreenReadingProviderSummary(screenSummary)
    : screenSummary;
  const userTextParts = [
    '请根据下面的用户语音和当前屏幕节点，生成 SightSync V1 动作协议 JSON。',
    `用户语音：${request?.utterance ?? ''}`,
    `语言：${request?.locale ?? 'zh-CN'}`,
    `屏幕摘要 JSON：${JSON.stringify(providerSummary)}`,
    request?.screen?.screenshotBase64 ? '截图：已作为 image_url 附加。' : '截图：无。',
    screenReadingMode
      ? '这是只读屏请求：只返回自然中文 spoken，requiresConfirmation 必须为 false，actions 必须为空。'
      : '再次强调：目标明确时直接返回白名单动作；目标不明确时 actions 为空并追问用户。'
  ];
  if (!screenReadingMode) {
    userTextParts.splice(3, 0,
      `当前包名：${request?.screen?.packageName ?? ''}`,
      `当前页面：${request?.screen?.activityName ?? ''}`,
      `屏幕节点 JSON：${JSON.stringify(request?.screen?.nodes ?? [])}`
    );
  }
  const userText = userTextParts.join('\n');

  return {
    model,
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: buildUserContent(userText, request?.screen?.screenshotBase64) }
    ],
    response_format: { type: 'json_object' },
    enable_thinking: false,
    temperature: 0.1
  };
}

function buildScreenReadingProviderSummary(summary) {
  return {
    protocol: summary.protocol,
    page: {
      title: summary.page?.title || null,
      screenshotAttached: summary.page?.screenshotAttached === true,
      screenshotPolicy: summary.page?.screenshotPolicy
        ? {
          privacyBlocked: summary.page.screenshotPolicy.privacyBlocked === true,
          reason: screenshotReasonLabel(summary.page.screenshotPolicy.reason)
        }
        : null
    },
    nodeStats: summary.nodeStats,
    mainContent: summary.mainContent,
    actionableItems: (summary.actionableItems || []).map((item) => ({
      label: item.label,
      kind: actionTypeLabel(item.type),
      region: regionLabel(item.region),
      inputContext: item.inputContext || null
    })),
    riskHints: summary.riskHints
  };
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

function screenshotReasonLabel(reason) {
  return {
    node_tree_sufficient: '节点信息充足',
    sparse_node_tree: '节点信息不足，已按策略附加截图',
    privacy_sensitive_content: '包含敏感信息，禁止截图'
  }[reason] || null;
}

export function parseQwenChatCompletion(payload) {
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content !== 'string') {
    throw new Error('provider response did not include message content');
  }
  return JSON.parse(content);
}

function buildUserContent(userText, screenshotBase64) {
  if (typeof screenshotBase64 !== 'string' || screenshotBase64.trim().length === 0) {
    return userText;
  }

  return [
    { type: 'text', text: userText },
    {
      type: 'image_url',
      image_url: {
        url: `data:image/jpeg;base64,${screenshotBase64}`
      }
    }
  ];
}
