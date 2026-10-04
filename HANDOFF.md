# Aether 项目工作交接文档

> **本文档的用途**：给接手的 AI / 开发者一份"只读这一份就能干活"的上下文。
> 涵盖：项目是什么、本次会话做了什么、改了哪些文件、哪些验证过哪些没有、还欠什么。
>
> **文档撰写时间**：2026-10-04
> **对应代码状态**：`app-debug.apk` = 83,157,822 bytes，已安装到真机验证（**此为 2026-10-03 的状态**）
> **2026-10-04 增补**：git 仓库已建立；新增 A1/A2 token 瘦身（§2.8）与验证步骤（§4.4）。这些增补已构建并装机（debug APK 83,159,382 字节），**但 Agent Mode 里的实际行为尚未验证**，见 §4.4。
> **行号说明**：文中行号是撰写时的快照，改动后会漂移。**请以函数名 / 常量名定位为准。**

---

## 0. 三十秒速览

**项目**：Aether（扶摇）—— 跨平台本地 AI Agent 客户端，Android / iOS / macOS，内核基于 Pi Coding Agent 框架。

**本次会话主线**：先把 iOS 端整体移除（项目转为 Android 单平台），然后修复 DeepSeek 模型适配 + 打通视觉能力，最后给 Agent Mode 加上"截图元素定位（OCR）"能力。

**⚠️ 接手前必读的三件事**：

1. **这个目录现在是 git 仓库**（2026-10-04 更新）。最初的 3 个提交：`1db6344` 导入本地工作副本、`d8fe8ab` CI 自动编译 APK 并发布到 GitHub Releases、`369366c` 忽略 `/signing/`。可以 `git diff` / `git revert` 回滚。
   - 早期会话里"没有 `.git`"的结论已经过期；§3.3 中的 iOS 备份 zip 仍然有效，但不再是唯一的回滚手段。
   - `signing/` 目录是本地签名材料，已 gitignore，**永远不要提交**。
   - CI 现为三个 workflow：`pr-check.yml`、`build-nightly-apk.yml`、`release-apk.yml`。
2. **`targetSdk = 28` 是故意的**，不要"顺手升级"。原因见 §1.3。
3. **`AGENTS.md` 的约束是硬性的**，改共享层必须同时考虑 Android 的 `app` 与 `iosApp` —— 不过 iOS 已在本次会话中移除，现在只需管 Android。

**当前最重要的待办**：Agent Mode 的 **token 消耗**问题（历史重发导致二次增长）。
- **已实施**（2026-10-04，见 §2.8）：A1（画面完全没变不附图）+ A2（`elements` 去掉 `bbox_px`）。**已构建并装机（冷启动无崩溃），但 A1/A2 的实际行为尚未在 Agent Mode 里跑过，也未量化收益。**
- **已实施**（2026-10-05，见 §2.9）：H1（请求里旧截图/旧 elements 换占位）+ H2（模型可见字段精简）。已装机，真机效果待测。
- **尚未动手**：B1、C1、C2。是否做取决于 A1+A2 上线后观察到的 token 曲线。见 §6.1。
- **并行的验证欠账**：`ui_changed` 阈值标定、OCR 飞行模式测试。操作步骤见 §4.4。

---

## 1. 项目是什么

### 1.1 定位

Aether 是一个**高颜值、高扩展性、本地化的通用 AI Agent** 移动/桌面客户端。作者是 15 岁学生 Shilin "BaimoQilin" Zhou，GPL-3.0 开源。

它解决的问题：在手机上提供"类 Claude Code / Codex CLI"的完整 Agent 体验 —— 能聊天、能调工具、能跑命令行、能装扩展，模型供应商可自由更换。

### 1.2 技术架构

| 模块 | 角色 |
| :--- | :--- |
| `shared/` | Kotlin Multiplatform + Compose Multiplatform 共享层。聊天 UI、设置、Room 数据库、Pi 客户端、Skill / 扩展状态。**iOS 源集已移除**，现只剩 `commonMain` + `androidMain` + `commonTest` |
| `app/` | Android 端。Compose UI + Alpine proot 运行时 + Shizuku/Termux + SAF 文件管理器 + 定时任务服务 |
| `pi-bridge/` | TypeScript 编写的 Node 桥接层，打包成 `bridge.mjs` 塞进 App assets。**Agent 内核与移动端 UI 之间的协议层** |
| `extensions/` | 三个官方预装扩展：Pi Web Access / Pi MCP Adapter / Pi Subagents |
| `packages/extension-api/` | 发布到 npm 的 `@baimoqilin/aether-extension-api`，只含类型声明 |
| `third_party/termux/` | 内嵌的 Termux terminal-emulator / terminal-view |
| ~~`iosApp/`~~ | **本次会话已删除** |

### 1.3 关键约束

- **`targetSdk = 28`**（`app/build.gradle.kts`）：因为 Alpine/Termux 运行时需要在 app 私有目录执行 ELF 文件，而 Android 从 API 29 起禁止 `execve()`。**改高会导致 Alpine 终端完全不可用。**
- **`applicationId = "com.baimoqilin.aether"`**，但 debug 变体带 `applicationIdSuffix = ".debug"` → debug 包实际是 **`com.baimoqilin.aether.debug`**，与正式包**并存**，不互相覆盖。
- **abiFilters 只有 `arm64-v8a`**。
- 三层扩展体系（见 `docs/AETHER_EXTENSIONS.md`）：`pi.extensions` / `aether.extensions`（热重载 TS-JS）/ `aether.native`（需重启的 Kotlin-DEX）。**官方明确说明没有权限沙箱**。

---

## 2. 本次会话完成的工作

按时间顺序，共 7 项。

### 2.1 移除 iOS 端（项目转为 Android 单平台）

采用**保守方案**：删 iOS 目标与产物，但**保留 KMP 结构**（`shared` 仍是 `commonMain` + `androidMain` 分离），日后想加回 iOS 或加桌面端都容易。

**删除**：
- `iosApp/`（39 文件）、`shared/src/iosMain/`（35）、`shared/src/iosTest/`（14）、`third_party/ish-arm64/`（空目录）
- 脚本：`bootstrap-ios.sh`、`build-ios-ish-runtime.sh`、`prepare-ios-runtime.sh`
- `.gitmodules`（ish-arm64 是唯一子模块）
- 死代码：`SharedAppUpdateService.kt` + 其测试（纯 App Store lookup 逻辑）、`public/badge-appstore.svg`

**修改**：`shared/build.gradle.kts`、`gradle/libs.versions.toml`、`.github/workflows/pr-check.yml`、两个 Issue 模板、`AGENTS.md`、`README.md`、`README_zh.md`、`THIRD_PARTY_NOTICES.md`

### 2.2 修复 DeepSeek V4.1 Flash 模型 ID（重要）

**问题**：`PiProviderCatalog.kt` 里 DeepSeek 的默认模型 ID 是 `deepseek-v4-flash` —— 这是 **DeepSeek 已退役的 legacy 名**，不在 Pi 静态目录里。后果：

| | 旧 ID `deepseek-v4-flash` | 新 ID `deepseek-flash` |
| :--- | :--- | :--- |
| 命中 Pi 目录 | ❌ | ✅ |
| `input` 能力 | 靠内核兜底强制 | 目录原生声明 `["text","image"]` |
| contextWindow | **128,000** | **1,000,000** |
| maxTokens | **16,384** | **384,000** |
| 成本信息 | 全 0 | 真实价格 |

即：**上下文被砍到 1/8、输出被砍到 1/24**。

同样的过期 ID 还出现在 `fireworks` 和 `opencode-go`（后者的旧 ID 命中的是**纯文本旧模型**，视觉直接不通）。

**修法**：改 3 个默认 ID + 加旧 ID 规范化映射（按 provider 作用域隔离，避免误改自定义端点），并在**配置加载时**和**模型配置构建时**两处应用，让已保存的旧配置也能自动迁移。

### 2.3 打通图片输入能力（两个缺口）

**缺口 A**：`listProviders()` 在两处都定义了，但**全项目从未被调用**。bridge 辛苦下发的 `input` 字段整条通路是死的，`ProviderModelOption` 没有能力字段。

**缺口 B**：`ModelCapabilities` 没有 vision 字段。选纯文本模型传图不会提示，图片被**静默忽略**。

**修法**：
- 新增 `ProviderModelCapabilities`（解析 `list_providers` 的 `input`）
- `ProviderModelOption.supportsImageInput` + `ModelCapabilities.supportsImageInput`
- 启动时拉取并缓存能力（3 次重试，间隔 5s）
- UI 层：纯文本模型点图片按钮 → 不打开图库，弹提示
- 数据层兜底：即使绕过 UI，也不再把图发给纯文本模型，而是给模型一条明确文字说明，让它告知用户

**关键设计**：**未上报的模型一律默认"支持"**。因为内核本身对不在静态目录里的模型（自定义端点、刚发布的 ID）是强制开启图片输入的，报"不支持"会把能用的模型误判成不能用。

**决策依据**：Aether 暴露的 34 个 provider 共 1382 个模型，其中 **434 个是纯文本（31.4%）** —— 硬编码表不可行，必须接真实数据。

### 2.4 修复 Alpine 容器 DNS（真实 bug）

**症状**：扩展（如 `@tintinweb/pi-subagents`）加载失败，红框报 `npm error code ECONNABORTED` / `EAI_AGAIN`。

**根因**：`AlpineRuntime.ensureGuestNetworkConfig()` 把容器 DNS **硬编码**成 `1.1.1.1` / `8.8.8.8`。这两个在国内蜂窝网下不可达 → 容器内所有域名解析失败 → `npm install` / `apk add` 全挂。

proot 容器没有自己的 DHCP，宿主用的是运营商 DNS（实测 `58.240.57.33` / `221.6.4.66`），但容器却去问一个连不上的服务器。

**修法**：改为**把宿主当前真实使用的 DNS（含 VPN）镜像进容器**：
- 新增 `hostDnsServers()` 从 `ConnectivityManager.getLinkProperties()` 读取
- 补 `ACCESS_NETWORK_STATE` 权限（原先没声明，读不到会直接抛异常）
- **自动迁移旧的硬编码文件**，但保留真正自定义的配置（带 `search` 域或额外 options 的）

**A/B 实测验证**：手工把 `1.1.1.1`/`8.8.8.8` 写回容器 → 重启 App → 自动改写为宿主 DNS `172.19.0.2`（当时手机挂着 Clash VPN）。

### 2.5 Agent Mode 新增 `ui_changed` 字段

给 `tap` / `swipe` 返回值增加"这一次手势有没有让画面变化"。

- 动作前截一帧 → 执行 → 等 400ms → 再截一帧
- 两帧缩成 **16×16 灰度网格**，算平均绝对差
- 超过阈值判 `true`

### 2.6 Agent Mode OCR 元素定位（本次会话的核心新功能）

**目标**：让 Agent 能"看见"屏幕上的文字并精确点击，而不依赖 AccessibilityService。

**实现**：
1. 集成 ML Kit 中文离线 OCR（`com.google.mlkit:text-recognition-chinese:16.0.1`，**bundled 版，模型内嵌 APK**）
2. `screenshot` / `tap` / `swipe` 返回值新增 **`elements`** 字段（文本 + 两种坐标系的 bbox）
3. 新增两个 action：
   - `find_text`：入参 `query`，返回匹配元素（**不改变界面、不附图**）
   - `tap_text`：入参 `query`，找到元素中心并注入点击；**找不到返回明确错误，绝不瞎点**

**已通过真机实测**：在 B 站「我的」页面 `tap_text("设置")` 一次命中，`cursor_y=2350` 与 `matched_bbox_norm` 换算完全吻合，`ui_changed=true`，成功进入 `BiliPreferencesActivity`。

### 2.7 坐标诊断日志 + 滚动重试

- **① 诊断日志**：`action_end` 事件追加坐标字段（注入的显示像素 / 归一化值 / 截图尺寸 / 命中文本 / 元素数 / `ui_changed` / 滚动次数）
- **② 滚动重试**：`tap_text` 找不到元素时，自动上滑半屏重新 OCR，最多 2 次；**命中后立即点击、不再滚动**（从结构上杜绝重复点击）

### 2.8 Token 瘦身 A1 + A2（2026-10-04，后续会话）

**A1 — 画面完全没变时不附图**（`AgentModeController.kt`）

- `tap` / `swipe` / `tap_text` 在手势前已经截了一帧 16×16 灰度指纹（`beforeFrame`）。`captureAfterDelay` 新增参数 `unchangedFrom`，把**最终那张截图**再算一次指纹，与 `beforeFrame` 做**逐格严格相等**（`contentEquals`）比较。
- 严格相等则：不写 `screenshot_base64` / `screenshot_mime_type`，改写 `screenshot_omitted: "unchanged"`，`stdout` 改为说明文字。`PiAgentRunner` 本来就是"有 `screenshot_base64` 才附图"，所以**无需改它**。
- **为什么是严格相等而不是 `ui_changed == false`**：`ui_changed` 用 >1.0 的阈值，会把小控件变化（复选框等）平滑掉；用它判"不附图"会让模型看不到变化。严格相等只在像素级完全一致时才省图，**不会漏掉任何视觉变化**。代价是命中率较低（状态栏时钟、动画页会让它失效），但命中时零风险。
- **`screenshot` 动作永远附图**；`start` / `launch` / `key` / `text` 不受影响（它们不传 `unchangedFrom`）。
- 截图文件仍照常写入工作区，`preview_path` 也照常更新；省的只是**送给模型的那一份**。
- `elements`（OCR 文字）**照常返回**，所以即使省了图，模型仍能拿到当前屏幕文字。

**A2 — `elements` 瘦身**（`AgentModeController.kt` `elementsJson()`）

- 去掉 `bbox_px`，只保留 `bbox_norm`（0..1000）。两者信息等价，`bbox_px` 是纯重复。
- 同步更新了 `AetherToolExecutor.kt` 里 `agent_display` 的 description（**这是模型可见契约**），并说明 `screenshot_omitted`。
- 内部 `tap_text` 走的是 `AgentModeTextElement.boundingBox`（OCR 原始像素），**不依赖 JSON 里的 `bbox_px`**，所以点击路径不受影响。

**新增诊断**：`withUiChanged` 现在会往 `events.jsonl` 写一条 `agent_mode / ui_diff_sample`，含 `mean_abs_diff`（原始差值）、`threshold`、`identical`。这是 §4.4 标定阈值的数据源。`action_end` 事件也会带上 `screenshot_omitted`。

**单测**（`AetherToolExecutorTest`）：新增 2 个——清洗后保留 `screenshot_omitted` 且不误标 `screenshot_injected_into_next_model_request`；description 不含 `bbox_px` 但含 `bbox_norm` / `screenshot_omitted`。**控制器本身依赖 Android `Bitmap`，没有 JVM 单测，需要真机验证。**

**预期收益（推算，未实测）**：A2 约省 `elements` 的 30–35%；A1 每次命中省 ≤384 tok 图片。因为历史会被反复重发，累计收益按 N² 放大。**命中率未知**，要看真实会话里 `screenshot_omitted` 出现的比例。

### 2.13 画面指纹改为区域平均（2026-10-05 01:08 装机）——修正 §2.12 的误诊

- **真正根因**：`downscaleToGrayGrid` 用 `Bitmap.createScaledBitmap(588×1280 → 16×16, filter=true)`。大比例缩小时每个输出像素只采样少数源像素，**不是区域平均**，小控件（开关）变化可能一个采样点都碰不到。于是 A1 判"逐像素相同"而省图、`ui_changed=false`。§2.12 当成"服务器慢"是误诊：01:02 那次实跑中，点击后 750ms 的截图里开关**已经**变灰，A1 仍判相同。
- **修复**：新文件 `AgentModeFrameDiff.kt`（纯函数，JVM 可测）：`areaAveragedGrayGrid()` 逐像素累加到 32×64 网格（每格约 18×20 px）；`ui_changed` 改为"任一格灰度变化 > 4"（`AgentModeUiChangeCellTolerance`），不再用全屏平均差（小控件会被稀释）。A1 仍要求网格完全相等。`ui_diff_sample` 日志改记 `changed_cells` / `max_cell_diff`。
- **用真机截图验证**（PowerShell + System.Drawing 跑同一算法）：开关开→关 `changed_cells=41, max_cell_diff=69`；静止画面 `0 / 0`。单测 `AgentModeFrameDiffTest` 3 个。
- **影响**：早先"A1 命中 10/12"的统计（§4.5）是旧采样下得出的，可能含漏检；§2.12 的 1 秒复查保留为保险。
- 01:02 那次（修复前）同一 B 站任务：9 次请求、27 秒、约 ¥0.030，开关确实关闭了，但模型因 A1 误判多截了 1 次图。

### 2.14 界面汉化 + Agent 操作中间说明跟用户语言（2026-10-05）

- **点击时上方英文**：不是工具标题（`正在点击 Agent 模式屏幕` 早已有中文），而是模型在工具调用之间写的短说明。`PiAgentPrompt.kt` 已要求「用户可见文字跟用户最新消息的语言，包括工具调用之间的短说明」。
- **插件 UI 改为仅中文**（品牌名 MCP / OAuth / API Key / GitHub 等保留）：`extensions/pi-mcp-adapter/aether.ts`（含「添加 MCP 服务器」）、`extensions/pi-web-access/aether.ts`、`extensions/pi-subagents/src/aether.ts`。三个预装插件的 `package.json` description 也改成中文。
- **App**：插件列表里残留的 Extension/package 英文词改成「插件/软件包」；通知渠道、文件管理器无障碍文案、Alpine 终端返回键接入字符串资源。
- **装机注意**：Alpine 预装扩展目录若已存在，`installPreinstalledExtensions` **不会覆盖**。装完 APK 后必须把更新过的 `aether.ts` / `package.json` 推到 `files/runtimes/alpine/rootfs/root/.aether/extensions/`，否则设置页仍是旧英文。

### 2.12 撤回 B1 + "画面没变"延迟复查（2026-10-05 01:01 装机）

- **B1 实测失败，已整体撤回**（参数 `include_image`、`not_requested`、提示词改动全部移除）。同一 B 站任务：请求 12→24 次、截图 10→10 张（一张没省）、用时 40→83 秒、费用 ¥0.040→¥0.096。原因：模型看不到图就主动补 `screenshot`（5 次），每次多一轮请求；而且在"我的"页面瞎猜齿轮图标位置，绕了 13 步。**结论：在 DeepSeek 这类模型上，不要让模型"先盲点后补图"。**
- **A1 误判修复**：B 站推送开关要等服务器返回才变色，点击后约 750ms 时画面仍逐像素相同，A1 告诉模型"没点中"（截图核实：开关其实已打开）。现在判定"完全没变"后再等 `AgentModeUnchangedRecheckMillis = 1000` 重拍一次；仍相同才省图，变了则附新图并标 `ui_changed = true`、`ui_changed_delayed = true`（诊断日志同步记录）。代价：真正没变的点击多等 1 秒。

### 2.11 B1：手势默认不附图（2026-10-05 00:51 装机，**已于 01:01 撤回，见 §2.12**）

- **动机（实测）**：B 站任务 12 步 $0.0057；带新截图的步骤新增 1100–1450 未命中缓存 token，不带图约 600–700。钱主要花在新截图上。
- `tap` / `swipe` / `tap_text` 默认 `attachImage = false`：仍然截图（UI 预览、回放、OCR 照常），但不放 `screenshot_base64`，结果带 `screenshot_omitted: "not_requested"`。新增参数 `include_image`（兼容 `includeImage`）按需要图。
- **保险**：OCR 一个元素都没识别到（纯图标界面或识别失败）时照样附图，模型不会完全看不到画面。
- `launch` / `key` / `text` / `screenshot` 不变，继续附图。系统提示词去掉"打字前在截图里确认聚焦"，改为用 `text` 自带的截图核对（`focused_window` 只说明窗口有焦点，不能证明输入框被选中，不能拿来当确认依据）。
- 诊断日志 `action_end` 的 `image_omitted` 现在记原因：`no` / `unchanged` / `not_requested`。
- **风险待测**：开关状态、纯图标按钮等只能看图判断的场景，模型可能多走一步 `screenshot`，或判断错。用同一任务对比费用、步数与成功率。

### 2.10 回复下方显示本轮 token 与人民币花费（2026-10-05）

- **修了一个统计口径 bug**：以前 `run_turn` 结果的 `usage` 只取**最后一次**模型请求，一个 Agent 任务十几次请求时，"本轮 token"严重偏低。现在 `bridge.ts` `turnUsagePayload()` 把本轮（`timestamp >= 开始时间`）所有 assistant 消息的 usage 与 Pi 算好的 `cost.total`（美元）累加成 `turn_usage`；Kotlin `toPiCompletionResult()` 优先用它，`PiAgentRunner` 对 follow-up 再累加。
- `LlmTokenUsage` / `ChatUsageStatistics` 新增 `costUsd`（持久化键 `costUsd`，旧消息没有该字段即不显示费用）。导出归档（`shared` 的 `PersistedChatUsage`）**未加**该字段，导出再导入会丢费用。
- UI（`ConversationMessages.kt` `AssistantMessageActions`）：操作按钮下方一行"本轮消耗 X token · N 次请求 · 约 ¥Y"；统计弹窗新增"约花费（人民币）"。只对 `tokenUsageSource == "api"` 显示；模型无定价（`cost_usd` 为 0）时只显示 token。汇率固定 `ApproximateCnyPerUsd = 7.1`，价格来自 Pi 内置价目表，**不是账单**。
- 副作用：设置页的累计 token 统计从此按整轮计，数字会比以前大（以前是少算）。

### 2.9 历史观测裁剪 H1 + 字段精简 H2（2026-10-05）

**H1 — 请求里只保留最近的截图和 `elements`**（`pi-bridge/src/agent-display-context.ts`，新文件）
- 挂在 Pi 扩展的 `context` 事件上（`bridge.ts` 的 `extensionFactories`，仅 Android）。Pi 在每次请求模型前调用它，且事先做了 `structuredClone`，所以**只改发出去的请求，会话文件和 UI 保留原样**。
- `agent_display` / `browser` 的旧截图换成一行占位文字；`agent_display` 旧结果里的 `elements` / `matches` 删掉，改为 `elements_omitted: N`。
- 默认保留最近 2 张截图、2 组 `elements`，**按 6 个一批裁剪**：被裁集合只在每多出 6 个时才增长，期间请求前缀逐字节不变，DeepSeek 前缀缓存仍能命中。截图和 `elements` 分开计数，所以 A1 省图（画面没变）的结果不会把"仍是当前画面"的那张图挤掉。
- 单测：`pi-bridge/tests/agent-display-context.test.mjs`（6 个，Node 直接导入 `.ts`，需 Node ≥22.18）。

**H2 — 给模型看的文字去掉 UI 专用字段**（`AetherToolExecutor.modelVisibleToolOutput()`，`PiAgentRunner.hostToolPayload()` 的 `content.text` 调用）
- 去掉 `preview_path`、`cursor_x/cursor_y`、`screenshot_mime_type`、`screenshot_injected_into_next_model_request`；结果里有 `image_width` 时再去掉显示器像素 `width/height`。`status` 等不带 `image_width` 的结果保留 `width/height`。
- **`output_json` 不变**：UI 回放（`ConversationMessages.kt` `buildAgentModeReplayFrames`）读的就是它里面的 `preview_path` / `width` / `cursor_x`。
- 保留 `image_width/image_height`：系统提示词让模型用它们把像素换算成 0..1000。
- description 同步：不再提 `cursor_x/cursor_y` 与显示器像素，并说明旧截图/elements 会被占位替换。

**⚠️ H1 实测后改为"上下文超过 6 万 token 才触发"（2026-10-05 00:39 装机）**：B 站任务（19 次请求）实测 DeepSeek 计价为未命中 $0.30/M、命中缓存 $0.006/M（差 50 倍）。H1 在第 12、14 步各触发一次裁剪（截图和 elements 分开计数，各到一次批次边界），两次缓存失效让这两步费用涨到 3–4 倍，**整轮反而多花约 20%**；省下的 4 万 token 全是缓存命中，只值 $0.0002。结论：有便宜缓存的模型上，历史重发几乎不花钱，**钱花在每步新增内容（截图 + elements）和缓存失效上**。现 `minContextTokens = 60_000`，用 Pi 的 `estimateTokens` 对**未裁剪**历史求和（只增不减，触发后不会反复开关）；普通任务不触发。

**诊断日志**：`coordinateDiagnostics` 的 `screenshot_omitted` 改记为布尔 `image_omitted`（键名含 `screenshot` 会被日志脱敏成 `[OMITTED ...]`）。

**验证**：`AetherToolExecutorTest` 9/9 通过；pi-bridge 全量 53 个测试中 8 个失败，与改动前基线（47 个中 8 个失败，均为 Windows 下测试扩展找不到 `typebox` / `babel.cjs`）一致；已装机冷启动无崩溃。**H1/H2 的真机效果（模型是否仍点得准、token 实际降幅）尚未实测。**

---

## 3. 变更清单

### 3.1 新增文件

| 文件 | 作用 |
| :--- | :--- |
| `shared/src/commonMain/.../data/ProviderModelCapabilities.kt` | 解析 bridge 的 `list_providers` 回传的 `input`，提供 `supportsImageInput()` |
| `shared/src/commonTest/.../data/ProviderModelCapabilitiesTest.kt` | 7 个测试 |
| `shared/src/commonTest/.../data/PiProviderCatalogModelIdTest.kt` | 8 个测试 |
| `app/src/main/java/.../data/AgentModeTextRecognizer.kt` | ML Kit 中文 OCR 封装（`AgentModeTextElement` + `recognize()`） |
| `app/src/test/java/.../runtime/AlpineGuestResolverTest.kt` | 5 个测试 |
| `local.properties` | Android SDK 路径（**已 gitignore，不进版本库**） |

### 3.2 修改文件

| 文件 | 改了什么 |
| :--- | :--- |
| `shared/.../data/PiProviderCatalog.kt` | 3 个模型 ID 改现行名；新增 `canonicalBuiltinModelIds` 映射 + `canonicalBuiltinModelId()` + `PiProviderDefinition.canonicalModelId()` |
| `shared/.../data/AppSettings.kt` | `ProviderModelOption.supportsImageInput`；`availableModelOptions(capabilities=)`；加载配置时规范化模型 ID |
| `shared/.../composeResources/values{,-zh-rCN,-fa}/strings.xml` | 新增 `chat_model_no_image_input`（三语） |
| `app/.../data/pi/PiProviderMapper.kt` | `toPiModelConfig()` 应用 ID 规范化（安全网） |
| `app/.../data/ModelCapabilities.kt` | 加 `supportsImageInput`；`resolve(settings, capabilities)` |
| `app/.../data/SessionExecutionManager.kt` | 缓存能力；直连图片路径对纯文本模型不再注入 `LlmImagePart`；`shouldInlineWorkspaceImageAttachment` 接入 |
| `app/.../ui/AetherViewModel.kt` | `refreshProviderModelCapabilities()`（3 次重试）；`notifySelectedModelRejectsImages()` |
| `app/.../ui/AetherUiState.kt` | `providerModelCapabilities` 字段 |
| `app/.../ui/AetherApp.kt` | 组合栏图片入口按能力门控 |
| `app/.../data/AgentModeController.kt` | **本次改动最多的文件**：`ui_changed`、`elements`、`find_text`、`tap_text`、滚动重试、坐标诊断；2026-10-04 追加 A1（`unchangedFrom` 省图）、A2（去 `bbox_px`）、`ui_diff_sample` 日志 |
| `app/.../data/AetherToolExecutor.kt` | `agent_display` schema 扩展（2 个新 action、`query` 描述、`elements` 说明） |
| `app/.../runtime/AlpineRuntime.kt` | `syncGuestResolver()` / `hostDnsServers()`，替换硬编码 DNS |
| `app/src/main/AndroidManifest.xml` | 加 `ACCESS_NETWORK_STATE` |
| `app/build.gradle.kts` | 加 ML Kit 依赖 |
| `gradle/libs.versions.toml` | 加 ML Kit 版本 + 库；移除 `ktor-client-darwin` |
| `shared/build.gradle.kts` | 移除 iOS 目标、Kotlin/Native 配置、`iosMain` 依赖、`kspIos*`、`generateIosPostHogConfig` |
| `.github/workflows/pr-check.yml` | 删除整个 `ios` job 及路径过滤 |
| `.github/ISSUE_TEMPLATE/{bug-report,feature-request}.yml` | 平台下拉改为仅 Android |
| `AGENTS.md` | 改为 Android 单平台，删除 4 条 iOS 真机流程 |
| `README.md` / `README_zh.md` | 平台描述改 Android，移除 App Store 徽章 |
| `THIRD_PARTY_NOTICES.md` | 删除 ish-arm64（iOS 运行时）声明 |

### 3.3 备份位置（**重要**）

因为不是 git 仓库，iOS 相关内容删前已打包：

- `E:\Aether-ios-backup-20261003-012849.zip` —— **115 条目**：`iosApp/`、`iosMain`、`iosTest`、iOS 脚本、CI、以及所有被修改配置文件的**原始版本**
- `E:\Aether-ios-backup-extra-20261003-021518.zip` —— 3 文件：`SharedAppUpdateService.kt`、其测试、`badge-appstore.svg`

**需要回退 iOS 就解压这两个包。**

---

## 4. 验证状态

### 4.1 已实测验证 ✅

| 项 | 结果 |
| :--- | :--- |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:shared:testDebugUnitTest` | **159 个测试，0 失败** |
| `:app:testDebugUnitTest` | 254 个测试，**6 个失败**（见 4.3，环境问题非代码问题） |
| 真机安装 | `Success`，`pm path` / `dumpsys package` 核对通过 |
| 冷启动 | `Status: ok`，无崩溃 |
| `.gitmodules` / `iosApp` 已移除 | 确认不存在 |
| ML Kit 模型内嵌 | APK 内含 `assets/mlkit-google-ocr-models/`（25 文件 2.4 MB）+ `libmlkit_google_ocr_pipeline.so`（10.8 MB） |
| 容器 DNS 自动纠正 | **A/B 实测**通过 |
| `tap_text("设置")` 真机命中 | **实测通过**（B 站，进入 `BiliPreferencesActivity`） |
| 坐标诊断日志（2026-10-04 复核） | **实测通过**。真机 `events.jsonl` 里 `agent_mode/action_end` 带 `injected_x/y`、`injected_norm_x/y`、`display_width/height`、`image_width/height`、`ui_changed`、`element_count`。样本中每次截图识别 27–68 个元素（这是 `elements` token 成本的真实量级） |
| A1/A2 构建与装机（2026-10-04） | `:app:assembleDebug` 成功，APK 83,159,382 字节；`pm install -r` Success；`pm path` / `dumpsys` 核对：`com.baimoqilin.aether.debug`、versionCode 11、versionName 2.1.6、`primaryCpuAbi=arm64-v8a`；冷启动 `Status: ok`，崩溃缓冲区为空 |

### 4.2 未验证 / 待确认 ⚠️

| 项 | 说明 |
| :--- | :--- |
| **图片→视觉的端到端** | 没有用真实 API Key 跑过"发图→模型描述图片"。结论来自 Pi 运行时探针 + 代码走查 |
| **`ui_changed` 阈值标定** | **阈值 1.0 是推理出来的，不是实测标定的**。未测过"静止画面两次截图的实测差值"。现已加 `ui_diff_sample` 日志，按 §4.4 采样即可 |
| **A1 / A2 真机行为** | 2026-10-04 新增。已装机但未在 Agent Mode 里实跑：A1 的 `screenshot_omitted` 命中率、A2 后模型是否仍能正确点击，均待验证。装机时 `ui_diff_sample` 日志条数为 0（预期，旧版本没有这个事件） |
| **`elements` 在真机的实测** | 用户跑的是 `tap_text`；`elements` 字段本身的真机样例未回传 |
| **滚动重试** | 未触发过（首次就命中了），逻辑未实测 |
| **OCR 绝对零网络** | 模型确实内嵌，但 ML Kit 有遥测链路。静态分析无法断言"零网络请求"。**建议开飞行模式跑一次验证** |
| **MIUI 底部热区** | 怀疑但未证实（用户用"真实 bbox 而非目测"绕开了） |

### 4.4 验证欠账的操作步骤（2026-10-04 整理）

前提：已按 AGENTS.md 流程安装最新 debug 包（`com.baimoqilin.aether.debug`），Shizuku 授权完成，Agent Mode 已开启。下面命令里的 `$serial` 用 `adb devices -l` 现取。

**① 标定 `ui_changed` 阈值**

1. 在 Agent Mode 里打开一个**完全静止**的页面（设置页、空白备忘录，别用有视频/轮播/时钟的页面）。
2. 让 Agent 对**空白处**连续 `tap` 约 10 次。
3. 取日志：
   ```powershell
   adb -s $serial shell run-as com.baimoqilin.aether.debug cat files/diagnostics/events.jsonl | Select-String ui_diff_sample
   ```
4. 看 `mean_abs_diff`：
   - 静止页应该全部是 `0` 或接近 `0`（JPEG 重编码同一画面是确定性的，预期**恰好为 0**，`identical=true`）。
   - 再对一个**会变化的操作**（如打开一个开关）重复，记录差值。
   - 阈值应落在"静止最大值"与"小控件变化最小值"之间。若小控件变化的差值 < 1.0，说明 1.0 太高，会产生假阴性。
5. 同时统计 `identical=true` 的比例，这就是 A1 的理论命中率。

**② OCR 飞行模式测试**

1. 开启飞行模式（同时关 Wi-Fi，二者都要关）。
2. 在 Agent Mode 里执行一次 `find_text` 和一次 `tap_text`。
3. 预期：仍能返回 `elements` 并点中。若 OCR 返回空或报错，说明 ML Kit 在尝试联网（例如下载模型）。
4. 补充：ML Kit bundled 版模型在 APK 里，但有遥测链路，**无法用静态分析断言零网络**，只有这个实测能证明。

**③ A1/A2 真机冒烟**

1. 对一个会变化的目标 `tap_text` → 结果应带 `screenshot_base64`（附图）。
2. 对静止空白处 `tap` → 结果应带 `screenshot_omitted: "unchanged"`，且**没有**图片。
3. 检查 `elements[*]` 只有 `text` 与 `bbox_norm`，没有 `bbox_px`。
4. 直接调用 `screenshot` → 必须带图。

**④ 观察 token 曲线**

- 同一个任务（比如"进入 B 站设置页再返回"）在改动前后各跑一遍，比较供应商后台的 input tokens。
- 会话 JSONL 位置见 §7.4；比较单个会话的大小与含 `image` 的记录条数。

### 4.5 2026-10-04 真机实测结果（提示词 A / B）

数据来源：Pi 会话 JSONL（`agent-sessions/2026-10-04T15-25-09-623Z_session-1791127509471.jsonl`），逐条工具调用与返回。**不要用 `events.jsonl` 做这类统计**——见下方"诊断日志坑"。

**A1 / A2 冒烟：通过 ✅**

- 12 次手势（10 次 `tap` + 1 次 `swipe` + 1 次 `tap_text`）中，**10 次**返回 `screenshot_omitted: "unchanged"` 且不带图；只有第 1、2 次 `tap`（页面真的跳转了，`ui_changed=true`）带图。**图片只在画面变了时才出现**。
- 这是静止页面的**最好情形**，命中率 10/12 不能外推到真实任务。真实任务里页面基本每步都在变，命中率会低得多。
- 模型正确理解了 `screenshot_omitted`：每一步都如实报告"有 screenshot_omitted"，没有因为缺图而困惑或重试。
- `elements` 每项字段为 `[text, bbox_norm]`，无 `bbox_px` ✅。`find_text` 返回的首个元素：`{"text":"字体样式和大小","bbox_norm":[70,77,563,107]}`。
- `key` 动作仍然带图（它不传 `unchangedFrom`），符合设计。

**`ui_changed` 阈值标定：部分完成 ⚠️**

- 静止页面 10 次手势，`ui_changed` 全为 `false`，**无假阳性**；`ui_diff_sample` 唯一幸存的一条是 `mean_abs_diff=0, identical=true`，印证"同一画面 JPEG 重编码是确定性的，差值恰好为 0"。
- **小控件变化（复选框等）的差值仍未采到**，阈值 1.0 是否会漏判小变化依然未知。需要专门测一个小控件。
- 提示词设计缺陷：模型挑的"空白处" (500, 985) 其实可点击，前两次点击跳进了子页面。不是 bug，下次提示词应指定"先 find_text 确认该处无文字且不可点"。

**诊断日志坑（重要）**

- `events.jsonl` 是滚动缓冲：超过 768 KB 就只保留最后 512 KB（`AetherDiagnosticLogger.kt` 的 `DiagnosticLogMaxBytes` / `DiagnosticLogTrimBytes`）。回合结束时 `pi_bridge` 会在 1 秒内刷出 400+ 条帧事件，**把 Agent Mode 的事件挤掉了**：本次 A 轮次 17 次调用，日志里只剩最后 3 条，`ui_diff_sample` 只剩 1 条（12 条里）。
- 另外 `screenshot_omitted` 的值被 `DiagnosticRedactor` 当成"大内容"脱敏成 `[OMITTED content_chars=9]`（key 里含 `screenshot`）。要在日志里看到它，需要改成布尔值或换个 key 名。
- **待办**：给 `agent_mode` 单独的日志文件，或降低 `pi_bridge/frame_received` 的记录量；`screenshot_omitted` 以布尔 `screenshot_was_omitted` 写日志。

**提示词 B（飞行模式 OCR）：无效，不能证明离线 ❌**

- 飞行模式 23:28:32 打开，但 **Wi-Fi 一直连着**（`logcat` 里 23:28:46 `wlan0` 仍 active；模型在 2 秒内回复了工具调用；OCR 之后 2 秒的最终模型请求也成功了）。所以整个窗口内网络都是通的。
- 唯一得到的结论：**一条回复里并行发出 4 个工具调用 + 依次执行，这个手法可行**（`bash sleep 25` 之后 `find_text`×2 + `screenshot` 全部执行，OCR 耗时约 0.17 s/次）。
- 重做时必须：飞行模式开 **并且** Wi-Fi 关；`sleep` 要足够长（建议 45 s）；最终那次模型请求应当**失败**（`turn_model_failed`），这才是网络断了的铁证。

**OCR 离线测试（重做，adb 控制断网）：通过 ✅**

- **手法**：用户发出提示词后，本机脚本监听 `events.jsonl`，在 `turn_start` 之后第一条 Termux `dispatch start timeout_ms=-1`（即第一个工具开始执行，此时首次模型请求已成功）出现时，执行 `adb shell cmd wifi set-wifi-enabled disabled` 断开 Wi-Fi；回合结束或 90 s 后 `enabled` 恢复。本机移动数据本来就是关的（`mobile_data=0`），所以 Wi-Fi 断开即完全无网。注意：此机 `svc wifi` 不存在，要用 `cmd wifi`。
- **网络确实断了的证据**：Wi-Fi 15:46:21 断开、15:47:54 恢复；最终那次模型请求在 15:48:19 才成功（断网期间一直卡住，恢复后才返回），回合历时 2 分钟。
- **OCR 离线结果**：断网窗口内 15:47:02 执行 `launch` 设置页 + 两次 `find_text`，三次均 `ok=true`，各识别出 **18 个元素**。**结论：ML Kit 中文 OCR 在完全无网下可用，不依赖在线模型下载。**
- **仍不能断言**：ML Kit 有遥测链路，"零网络请求"无法由此证明；本测试只证明"OCR 功能不需要网络"。
- **第一次失败的教训**：首次尝试 `find_text` 全部 `ok=false`，原因是 `Timed out while capturing display 13`——虚拟屏上没有任何活动（空的虚拟屏不产生画面帧，ImageReader 一直等不到帧），**与断网无关**。凡是要在虚拟屏上截图/OCR 的测试，提示词里都先 `launch` 一个应用保证屏幕有内容。

### 4.3 那 6 个失败测试（**不是代码问题**）

全部在 `AlpineDocumentStoreTest`，错误是 `java.nio.file.FileSystemException` + 中文提示"客户端没有所需的特权"。

**根因**：Windows 非管理员模式**无法创建符号链接**（`SeCreateSymbolicLinkPrivilege`）。该测试调用了 `Files.createSymbolicLink()`。

**已实测确认**：本机 `New-Item -ItemType SymbolicLink` 直接失败并报 "Administrator privilege required"。CI 跑在 ubuntu-latest 上不受影响。

**接手者注意：这 6 个失败是既有的环境限制，不要试图"修复"它们。**

---

## 5. 关键设计决策与契约

改代码前务必理解这几条，否则容易破坏既有设计。

### 5.1 Agent Mode 坐标系契约（最容易被误解）

**两套坐标系并存，中间靠 0..1000 归一化空间中转。**

| 空间 | 尺寸 | 用途 |
| :--- | :--- | :--- |
| 虚拟屏原始像素 | **1200×2608**（= 设备物理分辨率） | `injectInputEvent` 注入 |
| 截图像素 | **588×1280** | 模型看到的图、OCR bbox |
| 归一化 | **0..1000** | `tap`/`swipe` 的入参、`bbox_norm` |

**换算链路**：
```
虚拟屏 1200×2608
  ↓ scaleBitmapIfNeeded(maxEdge=1280)      [AetherAgentModeShizukuService.kt]
截图 588×1280
  ↓ normalizeAgentModePixel(px, 588或1280)  [AgentModeCoordinates.kt]
归一化 0..1000
  ↓ resolveAgentModeCoordinate(norm, 1200或2608)
显示像素 → injectInputEvent
```

- OCR 原始 bbox 在**截图系**（588×1280），仅在内部使用；**2026-10-04 起 JSON 里不再输出 `bbox_px`**（见 §2.8），模型只看 `bbox_norm`
- `bbox_norm` 分母是**截图宽高**（不是显示宽高），但因为是等比缩放，结果等价
- `tap_text` 用 `screenshotPixelToDisplay()` 串联上述两步，**与手动 tap 走完全相同的路径**

**总换算误差 < 约 3 个显示像素**（已实测证实）。

**唯一硬编码尺寸常量**：`AgentModeCaptureMaxEdge = 1280`（长边上限）。588 是算出来的：`floor(1200 × 1280/2608) = 588`。

### 5.2 其它重要取舍

| 决策 | 理由 |
| :--- | :--- |
| **`elements` 用「行级」而非 ML Kit 词级 `Element`** | 中文按钮标签（"立即购买"）本身就是一整行；词级会把短语切碎导致匹配失败。行级 bbox 也是更合理的点击目标 |
| **OCR 失败时 `elements` 字段直接省略，不返回空数组** | 避免模型把"没识别出来"误读成"屏幕上没文字"。`ui_changed` 同样处理 |
| **能力未上报时默认"支持图片"** | 与内核行为一致（内核对外目录外的模型强制开启图片输入）。宁可少提示，也不要把能看图的模型误禁 |
| **旧模型 ID 映射按 provider 作用域隔离** | 自定义端点可能合法地服务同名 ID，不能无差别改写 |
| **DNS 迁移只接管"我们生成的" resolv.conf** | 带 `search` 域或额外 options 的自定义配置保留不动 |
| **滚动重试只在「未找到」时触发** | 命中后立即点击、不再滚动，从结构上杜绝"把刚点到的目标滚走并重复点击" |

### 5.3 `agent_display` action 一览（改 schema 前必读）

| action | 说明 | 返回图？ | 返回 elements？ |
| :--- | :--- | :---: | :---: |
| `start` / `launch` / `key` / `text` | 原有 | ✅ | ❌ |
| `status` / `list_apps` / `stop` | 原有 | ❌ | ❌ |
| `tap` / `swipe` | 原有 | ✅ | ✅ |
| `screenshot` | 原有 | ✅ | ✅ |
| **`find_text`** | 新增，纯查询 | ❌ | ✅ |
| **`tap_text`** | 新增，按文字点击 | ✅ | ✅ |

**向后兼容承诺**：所有旧字段、旧 action 全部保留，新增均为**追加**。

---

## 6. 已知问题与待优化

### 6.1 ⭐ Token 消耗（最重要，方案已定但未动手）

**结论：会消耗非常多 token，而且真正的开销来自"历史重发"导致的二次增长，不是单次操作。**

**实测数据**（用户真实运行）：

| 项 | 实测值 |
| :--- | :--- |
| `agent-mode` 截图文件 | **148 个**（13 MB） |
| 会话 JSONL | **31 MB**，394 条记录 |
| 其中含 image 的记录 | **142 条** ← 截图被完整持久化 |
| 该会话 compaction 记录 | **0 条** |

**成本来源拆解**（单张截图）：

| 组成 | 成本 |
| :--- | :--- |
| 截图图片本身 | ≤ **384 tok**（DeepSeek 官方：一张图最多 384 tokens） |
| **`elements` 文本** | **~500–1000 tok** ← 本该被审视，比图还贵 |
| 其它 JSON | ~150 tok |
| **合计** | **~1230 tok/张** |

**放大器**：图片通过 `host_tool_result` 进入 Pi 的**内存会话**，该会话在 `prepareNativeAgentSession` 里被 `reused` 时**完全忽略 Aether 下发的 history**；且 `deepseek.json` 里 `"supportsStore": false` → **服务端不存会话状态，每次请求都带完整历史**。

→ 累计 ≈ `1230 × N(N+1)/2`。N=142 时约 **1120 万 input tokens**。

**为什么压缩没救它**：Pi 自动压缩确实开着（`bridge.ts` 里 `compaction: { enabled: true }`），但阈值是 `contextWindow - 16384`。`deepseek-flash` 的 contextWindow = **1,000,000** → 要 **~983,616 tok** 才触发。而该会话只到 **~156K**（16%），**一次都没压缩**。

另外：Aether 自己的 `shouldAutoCompactContext()`（`AetherViewModel.kt`）**没有任何生产调用点**，只有手动 `/compact` 会压缩。

**缓解因素**：DeepSeek 有 prompt caching，历史是 append-only 前缀，缓存命中单价 $0.006/M vs 未命中 $0.3/M（**50 倍**）。所以**账单降幅会小于 token 计数降幅**。

**⚠️ 一个需要知道的副作用**：修 `deepseek-v4-flash` → `deepseek-flash` 时，effective contextWindow 从 128,000 提到了 1,000,000，**压缩阈值随之从 ~111,616 提到 ~983,616（晚触发 8.8 倍）**，客观上放大了长会话的累计消耗。修复本身是对的（1M 才是真实能力），但这个连带影响当时没预判到。

**已设计好的方案**（按性价比排序，**均未实施**）：

| 编号 | 方案 | 省 | 影响功能 | 动哪里 |
| :--- | :--- | :--- | :--- | :--- |
| **A1** ✅已实施 | 画面**完全没变**时不附图（严格逐格相等，而非 `ui_changed == false`） | 384 tok/次 | **无** | 实际落在 `AgentModeController.kt` `captureAfterDelay()`，**不需要改 `PiAgentRunner`**（见 §2.8） |
| **A2** ✅已实施 | `elements` 瘦身（去掉信息重复的 `bbox_px`，只留 `bbox_norm`） | ~35% | **无** | `AgentModeController.kt` `elementsJson()` |
| **B1** ❌实测更贵已撤回（§2.12） | 图片默认只在 `screenshot` 附图；`tap`/`swipe`/`tap_text` 默认只回文本 + elements + ui_changed。加 additive 参数 `include_image` 按需索取 | ~80% 调用省 384 | 理论上不减少（可显式索取） | `PiAgentRunner.kt` + `AetherToolExecutor.kt` |
| **B2** | 引导模型优先用 `find_text`（本就不附图）而非反复 `screenshot` | — | 无 | 仅改 description |
| **H1** ✅已实施 | 请求里旧截图 / 旧 `elements` 换占位，只留最近 2 个，按 6 个一批裁（保缓存） | 历史重发从 N² 压到接近线性 | 当前画面不受影响；模型无法回看更早的截图 | `pi-bridge/src/agent-display-context.ts`（见 §2.9） |
| **H2** ✅已实施 | 给模型的文字去掉 UI 专用字段（`output_json` 不变） | ~100 tok/次 | 无 | `AetherToolExecutor.modelVisibleToolOutput()` |
| **C1** | 接上 `shouldAutoCompactContext`，阈值设低 | 把二次增长截断 | **有**：摘要化丢失细节 | `AetherViewModel.kt` → `PiKernelBridge.compactSession()` |
| **C2** | 定期重建 Pi 会话（重建时用 Aether 的 messages 作种子，而其中本来就不含 base64 → 累积图片自然清空） | 彻底 | **有**：丢内存态 | `prepareNativeAgentSession` 相关 |

**推荐**：A1 + A2 已做（2026-10-04）。**下一步是先按 §4.4 ④ 观察 token 曲线，再决定 B1**。C 类会改变会话语义，风险最高；除非 A+B 之后曲线仍然陡，否则不做。

**决策门槛（建议）**：
- 若同类任务 input tokens 下降不明显、且 `screenshot_omitted` 命中率很低（<20%）→ 考虑 B1（默认只有 `screenshot` 附图）。
- 若单会话仍会涨到 >300K tokens → 才考虑 C1（接上自动压缩，阈值设低）。C2（重建会话）最后考虑。

### 6.2 Agent Mode 定位准确性限制

换算链路是准的（<3 px），瓶颈在别处：

| 误差源 | 说明 |
| :--- | :--- |
| **OCR bbox = 文字外接框 ≠ 可点区域** | 文字旁边是开关/复选框而只有开关可点 → 点文字会**点空** |
| **匹配歧义** | `matchElements` 是精确 → 子串 → 去空白子串三级**静默降级**，`tap_text` 取 `firstOrNull()`（最上面那个）。`"确定"`/`"返回"`/`"更多"` 这类通用词风险高。**`matched_text` 是唯一能揭露静默降级的字段，务必核对** |
| **快照延迟** | 截屏 → OCR(200–500ms) → 注入，期间画面变化就点空。这是 OCR 相对无障碍的**结构性劣势** |
| 行级粒度 | 一行多个可点项 → 点中心落到两者之间；按钮文字换行 → 只命中半截 |
| 纯图标控件 | OCR 拿不到，**必须靠下一步视图树/无障碍** |
| 滚动重试单向 | 只向上滑（露出下方内容）；目标在上方时无效 |

### 6.3 `ui_changed` 可信度

判据是 16×16 灰度网格平均差 > 1.0。

- **假阴性风险**：16×16 网格每格覆盖截图 **36.75×80 px**（显示系 75×163 px）。一个小复选框（显示约 50px）不到一格 1/3，会被平滑掉
- **假阳性风险**：页面自身动画（spinner / 视频 / 轮播 / 状态栏时间）必然推高差值
- **结论**：`ui_changed=true` 只能说明"画面变了"，**不能证明"我的点击生效了"**；在有动画的页面上该字段**几乎无信息量**
- **阈值未经实测标定**（见 §4.2）

### 6.4 其它待办

- **`PlatformCapabilities.Ios` 预设值仍留在 `commonMain`**（现只剩 `PlatformCapabilitiesTest` 引用）。无害的未使用数据，按"保守方案"刻意保留
- **APK 体积 78.56 MB**（ML Kit 贡献约 13.2 MB：模型权重 2.4 MB + 推理引擎 10.8 MB）
- **会话文件膨胀**：单个会话 JSONL 达 **31 MB**。裁剪图片可一并解决存储与性能问题
- **`find_text` / `tap_text` 的 `query` 描述**已写进 schema，但模型是否真的优先用 `find_text` 侦察，未实测

---

## 7. 环境与操作手册

### 7.1 本机环境

| 项 | 值 |
| :--- | :--- |
| 工作目录 | `E:\Aether-main` |
| Android SDK | `C:\Users\36225\AppData\Local\Android\Sdk`（`local.properties` 已配好） |
| JDK | 21 |
| Node | v24.19.0 |
| 平台 | Windows（**无法编译 iOS**） |

### 7.2 构建

```powershell
# 完整构建
.\gradlew.bat :app:assembleDebug --no-daemon

# 测试（注意 app 有 6 个已知环境失败）
.\gradlew.bat :shared:testDebugUnitTest --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

**首次构建**会自动下载 NDK、Build-Tools、`pi-bridge` 的 npm 依赖，耗时较长（约 40 分钟）。之后增量构建约 1 分钟。

### 7.3 装到真机（**MIUI 有坑**）

**设备**：serial `95ac5d10`，Xiaomi 25102RKBEC（myron），Android 16 / HyperOS V816 OS3.0，arm64-v8a

**⚠️ `adb install` 会被 MIUI 拦下**，报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。这是系统限制，与 APK 无关。**绕过方式**：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "95ac5d10"
$apk = (Resolve-Path "app\build\outputs\apk\debug\app-debug.apk").Path

& $adb -s $serial push $apk /data/local/tmp/aether-debug.apk
& $adb -s $serial shell pm install -r /data/local/tmp/aether-debug.apk
& $adb -s $serial shell rm -f /data/local/tmp/aether-debug.apk
```

**安装后必须核对**：
```powershell
& $adb -s $serial shell pm path com.baimoqilin.aether.debug
& $adb -s $serial shell dumpsys package com.baimoqilin.aether.debug | Select-String 'versionCode|versionName|primaryCpuAbi'
```

**注意**：`AGENTS.md` 里写的是核对 `com.baimoqilin.aether`，但 debug 变体实际包名是 **`com.baimoqilin.aether.debug`**（有 `applicationIdSuffix`）。两者是不同 App，会并存。

**⚠️ 重装后 Agent Mode 入口消失（2026-10-04 实测踩坑）**

- **现象**：用 `pm install -r` 覆盖安装后，聊天输入栏的「Agent 模式」选项消失；设置里 Shizuku 授权显示 `Ready`，没有崩溃。
- **根因**：HyperOS 在重装后会重置该包的「自启动」权限。Aether 探测 Termux 是否就绪（`printf __aether_termux_ready__`）要启动 `com.termux/.app.RunCommandService`，被系统拦截：`logcat` 里是
  `MIUILOG-AutoStart, Service/Provider/Broadcast Reject ... caller= com.baimoqilin.aether.debug callee= com.termux classname=com.termux.app.RunCommandService`；
  `events.jsonl` 里是 `termux/trace "dispatch rejected"`。Termux 探测失败 → `agentModeReady`（见 `AetherApp.kt` 的 `agentModeReady`，同时要求 Termux 就绪）为 false → 入口隐藏。
- **判断方法**：重装前同一探测是 `dispatch result ... exit_code=0`，重装后全部 `dispatch rejected`。
- **修复（用户在手机上操作）**：系统「设置 → 应用设置 → 应用管理 → Aether（debug）→ 自启动」打开；再把电池策略设为「无限制」。然后回到 App，Agent 模式入口会恢复。
- **不是代码问题**。每次重装后都要复查，装机流程里应加一步：重装后立刻读 logcat 里的 `MIUILOG-AutoStart` 与 `events.jsonl` 里的 `dispatch rejected`。
- 同一轮日志里还有一次 `402 Insufficient Balance`：这是 DeepSeek 账户余额不足（请求已携带有效 Key 到达供应商），与应用无关。

### 7.4 看诊断日志

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.baimoqilin.aether.debug"

# Agent Mode 事件（含坐标诊断）
& $adb -s $serial shell run-as $pkg cat files/diagnostics/events.jsonl | Select-String agent_mode

# Agent Mode 截图 / 会话文件
& $adb -s $serial shell run-as $pkg ls -la files/runtimes/alpine/workspace/agent-mode/
& $adb -s $serial shell run-as $pkg ls -la files/runtimes/alpine/rootfs/root/.aether/agent-sessions/

# 扩展目录与 npm 日志
& $adb -s $serial shell run-as $pkg ls -la files/runtimes/alpine/rootfs/root/.aether/extensions/
& $adb -s $serial shell run-as $pkg ls -la files/runtimes/alpine/rootfs/root/.npm/_logs/

# 容器 DNS（验证修复）
& $adb -s $serial shell run-as $pkg cat files/runtimes/alpine/rootfs/etc/resolv.conf
```

### 7.5 改 Agent Mode 代码时的注意事项

- **`AgentModeController.kt` 是热点文件**（1500+ 行），本次会话改动最多
- **屏幕相关常量集中在文件头**（`AgentModeCaptureMaxEdge`、`AgentModeUiChange*`、`AgentModeTextSearch*`）
- **坐标换算是共享逻辑**，在 `AgentModeCoordinates.kt`，`tap_text` 与手动 `tap` **必须走同一条路径**，不要另起一套
- 改动 `elements` 结构 = 改动模型可见契约，需同步更新 `AetherToolExecutor.kt` 的 description

### 7.6 搜索代码时

`AGENTS.md` 规定：**默认排除缓存、第三方源码、构建目录**。`pi-bridge/node_modules` 里有完整的 Pi 内核源码（需要查内核行为时是有用的，但平时搜索要排除）。

---

## 8. 建议的下一步

按优先级：

1. ~~做 A1 + A2 降 token~~ ✅ 已实施（§2.8），**待装机**。
2. **装机并按 §4.4 补验证**（成本低、价值高）：
   - 标定 `ui_changed` 阈值（读 `ui_diff_sample` 日志）
   - 飞行模式跑一次 `find_text` / `tap_text`，确认 OCR 完全离线
   - A1/A2 冒烟：`screenshot_omitted` 命中、`elements` 无 `bbox_px`
   - 回传一条真实的 `elements` 样例 + 一条 `action_end` 诊断日志
3. **观察 token 曲线**（§4.4 ④），按 §6.1 的"决策门槛"决定是否上 B1 / C1 / C2。H1/H2（§2.9）已装机，先用同一任务对比成功率、步数、input tokens，确认点击精度没退化
4. **元素定位的下一步**：视图树 / 无障碍层，用于覆盖**纯图标控件**（OCR 永远拿不到）
5. **长期重构（独立提交，不与功能改动混合）**：
   - 拆大文件：`SettingsScreen.kt`（~6.8k 行）、`AetherViewModel.kt`（~6.1k）、`bridge.ts`（~3.4k）、`SessionExecutionManager.kt`（~2.9k）、`AetherApp.kt`（~2.1k）、`AgentModeController.kt`（~1.8k）
   - 收敛 `shared/commonMain/ui` 与 `app/ui` 的双份 UI；清理已无目标的 `PlatformCapabilities.Ios`
   - **约束**：每一项重构单独一个（或一组）提交，**提交内只做移动/拆分，不改行为**；功能提交与重构提交永不混在一起，这样 `git revert` 和 `git bisect` 才有意义
   - 拆分前先确认 `./gradlew :app:testDebugUnitTest` 的基线（Windows 非管理员下 6 个 `AlpineDocumentStoreTest` 失败是既有项）

---

## 9. 术语速查

| 术语 | 含义 |
| :--- | :--- |
| **Agent Mode** | Aether 的手机操作模式：创建一个隔离的 Android 虚拟屏，注入触摸事件操作它 |
| **虚拟屏** | `VirtualDisplay`，尺寸 = 设备物理分辨率（本机 1200×2608），显示名 `aether-agent-mode` |
| **`agent_display`** | 操作虚拟屏的工具名，有 12 个 action（见 §5.3） |
| **Shizuku** | 提权框架。Agent Mode 靠它拿到 shell 权限，再反射调用隐藏 API `InputManager.injectInputEvent` |
| **pi-bridge** | Aether 与 Pi 内核之间的 Node 桥接进程 |
| **元素 / `elements`** | OCR 识别出的屏幕文字行（文本 + 两种坐标系的 bbox） |
| **归一化坐标** | 0..1000 的空间，`tap`/`swipe` 的入参单位，与分辨率无关 |

---

*本文档由 AI 助手在会话结束时生成。所有"已实测"标记项均有对应命令输出；"未验证"标记项请勿当作事实使用。*
