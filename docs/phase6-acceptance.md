# Phase 6 V1 测试与验收清单

本清单用于验收 `纲领.md` 中 Phase 6 的 V1 测试范围。验收对象是现有 SightSync Android App，不新建替代 App，不在 Android 端配置云端 AI API key。

## 自动化测试

在项目根目录运行 Android 单元与本地集成测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

在后端目录运行 AI Proxy 协议测试：

```powershell
cd backend
npm test
```

通过标准：

- `ScreenContextCollector`、节点树提取、敏感字段脱敏、AI 协议、风险分类、节点匹配测试通过。
- 语音指令到屏幕采集、Mock AI 响应、动作执行的本地链路测试通过。
- 网络超时、非法 JSON、非法动作、无障碍权限关闭等失败场景有明确语音提示。

## 本机虚拟机准备

安装 debug APK：

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:installDebug
```

启动 App：

```powershell
adb shell am start -n com.sightsync.assistant/.MainActivity
```

启动后按 App 引导开启：

- 无障碍服务：SightSync。
- 麦克风权限。
- 悬浮窗权限。

后端代理使用项目现有配置。Android App 只连接代理，不保存云端 AI key。若当前后端没有 ASR provider 配置，语音识别链路不能完整人工验收，但自动化测试仍覆盖协议和错误处理。

## 人工验收场景

| 场景 | 操作 | 预期结果 | 结果 |
| --- | --- | --- | --- |
| 页面朗读 | 打开系统设置，说“这里有什么” | 助手朗读当前页面主要内容和可执行控件 | 未执行 |
| 明确点击 | 在系统设置说“点击 WLAN”或页面上的明确按钮 | 匹配唯一目标并点击；若目标不明确，应追问或拒绝猜测 | 未执行 |
| 输入文本 | 在输入框页面说“输入：测试内容” | 文本被填入目标输入框，并有语音反馈 | 未执行 |
| 页面滚动 | 在长页面说“向下滚动” | 页面向下滚动，并有语音反馈；不能滚动时朗读失败原因 | 未执行 |
| 返回 | 在任意页面说“返回” | 执行系统返回动作，并朗读反馈 | 未执行 |
| 网络断开 | 断开网络后发起请求 | 助手朗读网络失败或 AI 超时原因，不执行动作 | 未执行 |
| 高风险确认 | 在含“删除/发送/支付/提交”等按钮的页面请求点击 | 第一次只提示高风险并要求确认，第二次说“确认执行”才执行 | 未执行 |
| 权限关闭 | 关闭无障碍服务后发起请求 | 助手朗读“无障碍权限已关闭，请重新开启后再试。” | 未执行 |

## 记录格式

每次人工验收记录：

- 日期：
- 虚拟机镜像/Android 版本：
- App 版本：
- 后端配置：
- 未通过场景：
- 备注：
