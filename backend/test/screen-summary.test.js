import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildScreenSummary,
  buildScreenSummarySpoken
} from '../src/screen-summary.js';

test('buildScreenSummary groups page content, actionable items, and risk hints', () => {
  const summary = buildScreenSummary({
    packageName: 'com.android.settings',
    activityName: 'Settings',
    nodes: [
      { nodeId: 'title', text: '设置', role: 'TextView', clickable: false },
      { nodeId: 'wlan', text: 'WLAN', role: 'Button', clickable: true },
      { nodeId: 'pay', text: '付款设置', role: 'Button', clickable: true },
      {
        nodeId: 'search',
        text: '',
        contentDescription: '搜索设置',
        role: 'EditText',
        editable: true,
        actionableType: 'input',
        region: 'top',
        inputContext: '设置搜索框',
        scrollContainerNodeId: 'settings_list'
      }
    ],
    screenshotBase64: 'raw-image-data',
    screenshotPolicy: {
      attachScreenshot: true,
      reason: 'sparse_node_tree',
      privacyBlocked: false
    }
  });

  assert.equal(summary.protocol, 'screen_summary_v2');
  assert.equal(summary.page.packageName, 'com.android.settings');
  assert.equal(summary.page.title, '设置');
  assert.equal(summary.page.screenshotAttached, true);
  assert.deepEqual(summary.page.screenshotPolicy, {
    attachScreenshot: true,
    reason: 'sparse_node_tree',
    privacyBlocked: false
  });
  assert.deepEqual(summary.mainContent, ['设置', 'WLAN', '付款设置', '搜索设置']);
  assert.deepEqual(summary.actionableItems.map((item) => item.nodeId), ['wlan', 'pay', 'search']);
  assert.deepEqual(summary.riskHints, ['付款']);
  const searchItem = summary.actionableItems.find((item) => item.nodeId === 'search');
  assert.equal(searchItem.type, 'input');
  assert.equal(searchItem.region, 'top');
  assert.equal(searchItem.inputContext, '设置搜索框');
  assert.equal(searchItem.scrollContainerNodeId, 'settings_list');
});

test('buildScreenSummarySpoken describes content before actions without executing anything', () => {
  const spoken = buildScreenSummarySpoken({
    protocol: 'screen_summary_v2',
    page: { packageName: 'com.android.settings', activityName: 'Settings', screenshotAttached: false },
    nodeStats: { total: 3, labeled: 3, clickable: 2, editable: 0, scrollable: 0 },
    mainContent: ['设置', 'WLAN', '蓝牙'],
    actionableItems: [
      { nodeId: 'wlan', label: 'WLAN', role: 'Button', type: 'click' },
      { nodeId: 'bt', label: '蓝牙', role: 'Button', type: 'click' }
    ],
    riskHints: []
  });

  assert.match(spoken, /主要内容/);
  assert.match(spoken, /页面主题/);
  assert.match(spoken, /可操作项/);
  assert.match(spoken, /WLAN/);
  assert.doesNotMatch(spoken, /我会点击/);
});

test('buildScreenSummarySpoken supports brief detailed and actionable-only modes', () => {
  const summary = {
    protocol: 'screen_summary_v2',
    page: { title: '设置', packageName: 'com.android.settings', activityName: 'Settings' },
    nodeStats: { total: 4, labeled: 4, clickable: 2, editable: 1, scrollable: 0 },
    mainContent: ['设置', 'WLAN', '蓝牙', '搜索设置'],
    actionableItems: [
      { nodeId: 'wlan', label: 'WLAN', role: 'Button', type: 'click', region: 'top' },
      {
        nodeId: 'search',
        label: '搜索设置',
        role: 'EditText',
        type: 'input',
        region: 'top',
        inputContext: '设置搜索框'
      }
    ],
    riskHints: ['付款']
  };

  const brief = buildScreenSummarySpoken(summary, { mode: 'brief' });
  const detailed = buildScreenSummarySpoken(summary, { mode: 'detailed' });
  const actionsOnly = buildScreenSummarySpoken(summary, { mode: 'actions' });

  assert.match(brief, /简短读屏/);
  assert.match(brief, /设置/);
  assert.doesNotMatch(brief, /可操作项/);

  assert.match(detailed, /详细读屏/);
  assert.match(detailed, /搜索设置/);
  assert.match(detailed, /设置搜索框/);
  assert.match(detailed, /付款/);

  assert.match(actionsOnly, /可操作项/);
  assert.match(actionsOnly, /WLAN/);
  assert.match(actionsOnly, /搜索设置/);
  assert.doesNotMatch(actionsOnly, /主要内容/);
});

test('buildScreenSummary uses descendant labels for technical actionable parents', () => {
  const summary = buildScreenSummary({
    packageName: 'com.android.settings',
    activityName: 'Settings',
    nodes: [
      {
        nodeId: 'battery_row',
        contentDescription: 'android.widget.FrameLayout',
        role: 'FrameLayout',
        clickable: true
      },
      {
        nodeId: 'battery_label',
        parentNodeId: 'battery_row',
        text: '电池',
        role: 'TextView'
      },
      {
        nodeId: 'system_row',
        contentDescription: 'com.android.settings:id/system_update',
        role: 'Button',
        clickable: true
      },
      {
        nodeId: 'system_label',
        parentNodeId: 'system_row',
        text: '系统管理与升级',
        role: 'TextView'
      }
    ]
  });

  assert.deepEqual(summary.mainContent, ['电池', '系统管理与升级']);
  assert.deepEqual(
    summary.actionableItems.map((item) => item.label),
    ['电池', '系统管理与升级']
  );
});

test('buildScreenSummary prefers chinese descendants over english parent descriptions', () => {
  const summary = buildScreenSummary({
    nodes: [
      {
        nodeId: 'battery_row',
        contentDescription: 'Battery settings row',
        role: 'FrameLayout',
        clickable: true
      },
      {
        nodeId: 'battery_label',
        parentNodeId: 'battery_row',
        text: '电池',
        role: 'TextView'
      }
    ]
  });

  assert.equal(summary.actionableItems[0].label, '电池');
  assert.deepEqual(summary.mainContent, ['电池']);
});

test('buildScreenSummary filters technical labels and preserves user-facing latin names', () => {
  const summary = buildScreenSummary({
    nodes: [
      { nodeId: 'class', text: 'android.widget.TextView', role: 'TextView' },
      { nodeId: 'resource', text: 'com.example:id/search_box', role: 'TextView' },
      { nodeId: 'generated', text: 'node_12', role: 'TextView' },
      { nodeId: 'role', text: 'Button', role: 'TextView' },
      { nodeId: 'ai', text: '蓝心 AI', role: 'TextView' },
      { nodeId: 'wifi', text: 'Wi-Fi', role: 'Button', clickable: true },
      { nodeId: 'wlan', text: 'WLAN', role: 'Button', clickable: true },
      { nodeId: 'bluetooth', text: 'Bluetooth', role: 'Button', clickable: true }
    ]
  });

  assert.deepEqual(summary.mainContent, ['蓝心 AI', 'Wi-Fi', 'WLAN', 'Bluetooth']);
  assert.deepEqual(
    summary.actionableItems.map((item) => item.label),
    ['Wi-Fi', 'WLAN', 'Bluetooth']
  );
});

test('buildScreenSummarySpoken discloses degraded local reading', () => {
  const spoken = buildScreenSummarySpoken({
    protocol: 'screen_summary_v2',
    page: { title: '设置' },
    mainContent: ['设置', '电池'],
    actionableItems: [],
    riskHints: []
  }, { mode: 'brief', degraded: true });

  assert.match(spoken, /^智能总结暂不可用，先为你朗读当前可见内容。/);
  assert.match(spoken, /设置/);
});

test('degraded brief reading includes every summarized main item', () => {
  const spoken = buildScreenSummarySpoken({
    protocol: 'screen_summary_v2',
    page: { title: '设置' },
    mainContent: ['设置', '电池', '蓝心智能', '游戏魔盒', '钱包与支付', '系统管理与升级'],
    actionableItems: [],
    riskHints: []
  }, { mode: 'brief', degraded: true });

  assert.match(spoken, /电池/);
  assert.match(spoken, /系统管理与升级/);
});
