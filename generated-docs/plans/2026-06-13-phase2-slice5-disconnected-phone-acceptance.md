# Phase 2 小片 5：真机断开电脑验收准备

## 范围

- 验收 Phase 2 “脱离电脑运行”：手机不连接电脑、不依赖 `adb reverse` 时，通过个人云代理完成语音转写、读屏请求和低风险动作。
- 本小片只做验收、记录和必要问题归档；不新增功能，不切换 AI provider/model，不在 App 内写入云端 AI key。
- 若验收发现 bug，先记录复现、日志和风险，再单独进入后续修复小片。

## 安全边界

- 允许：构建现有 App、`adb install -r` 更新真机、启动 App、设置临时 `adb reverse` 做连接准备、抓取聚焦 logcat。
- 禁止：卸载、清数据、改系统设置、脚本输入语音或任意点击、修改权限、上传真机原始日志。
- 高风险行为不验收：支付、转账、发送消息、提交表单、删除不可恢复内容、输入密码/验证码/银行卡/身份证。

## 当前准备步骤

- [x] 指定真机 `10AC7Z0290001EF`，用当前工作区 debug build 执行 `adb install -r` 更新并启动。
- [x] 保留用户数据，不清权限、不卸载。
- [x] 连接电脑准备阶段设置 `adb reverse tcp:8787 tcp:8787`，仅用于更新后连通性准备；断开电脑验收时不得依赖它。
- [ ] 验收前确认 App 内已配置可公网访问的代理地址和 App token，连接测试能返回成功。
- [ ] 断开电脑或至少禁用 `adb reverse` 后，执行完整语音链路验收。
- [ ] 抓取并整理验收日志到 `generated-docs/logs/` 和 `generated-docs/acceptance/`。

## 验收步骤

1. 手机连接电脑时打开 SightSync 首页，确认“AI 服务连接”中填写的是公网代理地址，不是 `127.0.0.1`、`localhost`、`10.0.2.2` 或依赖电脑的局域网临时地址。
2. 点击“测试连接”，期望状态为连接测试成功；若失败，记录失败类型：鉴权失败、代理不可达、provider 不可用或响应异常。
3. 断开电脑数据线，或在验收前执行 `adb reverse --remove tcp:8787` 后不再使用 `adb reverse`。
4. 在手机上开启 SightSync 无障碍服务、麦克风权限、通知权限、悬浮窗权限，确认连续聆听通知和悬浮窗可见。
5. 在低风险页面执行语音验收：
   - “这里有什么？”：期望完成 ASR、屏幕采集、后端 `/v1/assist` 请求和 TTS 回答。
   - “返回”：期望识别为低风险白名单动作并执行返回，或在不可返回页面给出明确反馈。
   - “停止聆听”：期望停止连续聆听，不再录音、不再请求 AI。
6. 重新连接电脑后抓取聚焦日志，检查 `SightSyncSession`、`SightSyncAction`、`SightSyncRecorder`、`AndroidRuntime`。

## 通过标准

- App 内仅保存代理地址和 App token，不保存第三方 AI provider key。
- 不连接电脑、不使用 `adb reverse` 时，至少一次语音转写成功、一次读屏请求成功、一次低风险动作或明确可解释反馈成功。
- 连续聆听保持可见通知和悬浮窗；用户可通过语音或入口停止。
- 日志中没有 `FATAL EXCEPTION`、无障碍服务重启循环、token/key/base64 截图泄漏。

## 输出物

- 验收记录：`generated-docs/acceptance/2026-06-13-phase2-disconnected-phone-acceptance.md`
- 聚焦日志：`generated-docs/logs/sightsync-acceptance-key-*.txt`
- 如有 bug：`generated-docs/handoffs/` 下新建聚焦交接文档。
