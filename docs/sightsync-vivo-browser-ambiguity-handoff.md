# SightSync vivo 浏览器打开歧义问题交接

## 背景

本问题是在 2026-05-27 真机验收 `fix: prioritize exact app matches` 后发现的后续问题。QQ 与 QQ 邮箱场景已经通过真机日志验证：

- `打开QQ。` 解析并打开 `com.tencent.mobileqq`
- `打开QQ邮箱。` 解析并打开 `com.tencent.androidqqmail`

但同一轮验收中发现，`打开vivo浏览器` 没有直接打开 vivo 浏览器，而是进入多候选追问。

## 复现日志

日志文件：

- `sightsync-realdevice-acceptance-utf8-decoded-20260527-175503.txt`

关键日志：

```text
05-27 17:52:44.670 D/SightSyncSession: ASR utterance='打开vivo浏览器。'
05-27 17:52:44.671 D/SightSyncSession: Resolving local open-app command. pendingCandidates=0
05-27 17:52:44.964 D/SightSyncSession: Local open-app ambiguous. candidates=[com.quark.browser, com.vivo.browser]

05-27 17:52:59.268 D/SightSyncSession: ASR utterance='Vivo浏览器。'
05-27 17:52:59.270 D/SightSyncSession: Resolving local open-app command. pendingCandidates=2
05-27 17:52:59.599 D/SightSyncSession: Local open-app ambiguous. candidates=[com.quark.browser, com.vivo.browser]

05-27 17:53:12.407 D/SightSyncSession: ASR utterance='夸克浏览器。'
05-27 17:53:12.409 D/SightSyncSession: Resolving local open-app command. pendingCandidates=2
05-27 17:53:12.563 D/SightSyncSession: Local open-app resolved. actions=[AssistantAction(type=OPEN_APP, nodeId=null, text=null, appPackage=com.quark.browser)]
05-27 17:53:14.494 D/SightSyncAction: OPEN_APP succeeded. package=com.quark.browser
```

## 当前判断

该问题不是 ASR 没听清，也不是启动失败。ASR 已经得到 `打开vivo浏览器。` 和 `Vivo浏览器。`，但本地应用解析器仍把候选限制在：

- `com.quark.browser`
- `com.vivo.browser`

并继续返回 `Ambiguous`。

最可能原因是当前浏览器特殊逻辑在 `OpenAppCommandResolver.browserMatch()` 中过早处理 `浏览器` 类别，导致带品牌前缀的 `vivo浏览器` 仍被当成泛化的“浏览器”请求处理，而不是优先按普通精确/前缀评分命中 `com.vivo.browser`。

相关代码：

- `app/src/main/java/com/sightsync/assistant/apps/OpenAppCommandResolver.kt`
- `app/src/main/java/com/sightsync/assistant/apps/AppCatalog.kt`
- `app/src/test/java/com/sightsync/assistant/apps/OpenAppCommandResolverTest.kt`
- `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`

## 目标行为

- 说 `打开vivo浏览器` 时，如果本机存在 `vivo浏览器` 或包名/label 明确对应 `com.vivo.browser`，应直接打开 `com.vivo.browser`。
- 说 `Vivo浏览器` 作为追问裸目标时，应在上一轮候选 `[com.quark.browser, com.vivo.browser]` 中解析为 `com.vivo.browser`。
- 说泛化的 `打开浏览器` 时，仍保留当前行为：优先默认浏览器；没有默认浏览器且多个浏览器候选时追问。
- 说 `夸克浏览器` 时，仍应能解析并打开 `com.quark.browser`。

## 建议修复方向

保持“不上传应用列表、不让 AI 直接决定 package”的原则。优先做本地确定性修复：

1. 在 `OpenAppCommandResolver.resolve()` 中，先尝试普通应用名评分匹配。
2. 只有当目标是纯泛化浏览器词，例如 `浏览器`、`browser`、`网页`、`上网` 时，才进入 `browserMatch()` 默认浏览器逻辑。
3. 对带品牌词的浏览器目标，例如 `vivo浏览器`、`夸克浏览器`、`chrome浏览器`，应优先使用普通评分匹配。
4. 增加单元测试覆盖：
   - `打开vivo浏览器` 在 `夸克浏览器` + `vivo浏览器` 中解析为 `com.vivo.browser`
   - 追问裸目标 `Vivo浏览器` 在候选集合中解析为 `com.vivo.browser`
   - `打开浏览器` 仍优先默认浏览器
   - `打开浏览器` 在无默认且多个浏览器时仍追问

## 不建议的方向

不建议为这个问题引入 AI 甄别 App：

- 问题发生在本地浏览器特殊逻辑，不是自然语言理解不足。
- 让 AI 判断需要把应用候选信息发到后端，会改变当前 V2 隐私边界。
- 即使引入 AI，Android 端仍必须做本地 package 校验，复杂度增加但收益有限。

## 验收建议

真机验收前：

```powershell
D:\platform-tools\adb.exe reverse tcp:8787 tcp:8787
```

验收口令：

- `打开vivo浏览器`
- `打开浏览器`
- `夸克浏览器`

抓取日志重点：

```text
ASR utterance='打开vivo浏览器。'
Local open-app resolved ... appPackage=com.vivo.browser
OPEN_APP succeeded. package=com.vivo.browser
```
