# Aether 项目工作交接文档

> **本文档的用途**：给接手的 AI / 开发者一份"只读这一份就能干活"的上下文。
> 涵盖：项目是什么、本次会话做了什么、改了哪些文件、哪些验证过哪些没有、还欠什么。
>
> **文档撰写时间**：2026-10-04
> **对应代码状态**：`app-debug.apk` = 83,157,822 bytes，已安装到真机验证
> **行号说明**：文中行号是撰写时的快照，改动后会漂移。**请以函数名 / 常量名定位为准。**

---

## 0. 三十秒速览

**项目**：Aether（扶摇）—— 跨平台本地 AI Agent 客户端，Android / iOS / macOS，内核基于 Pi Coding Agent 框架。

**本次会话主线**：先把 iOS 端整体移除（项目转为 Android 单平台），然后修复 DeepSeek 模型适配 + 打通视觉能力，最后给 Agent Mode 加上"截图元素定位（OCR）"能力。

**⚠️ 接手前必读的三件事**：

1. **这个目录不是 git 仓库**（没有 `.git`）。没有提交历史、不能 `git diff`、**删除不可回滚**。动手前先自己备份。
2. **`targetSdk = 28` 是故意的**，不要"顺手升级"。原因见 §1.3。
3. **`AGENTS.md` 的约束是硬性的**，改共享层必须同时考虑 Android 的 `app` 与 `iosApp` —— 不过 iOS 已在本次会话中移除，现在只需管 Android。

**当前最重要的待办**：Agent Mode 的 **token 消耗**问题（历史重发导致二次增长）。分析已完成，方案已列，**尚未动手**。见 §6.1。

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
| `app/.../data/AgentModeController.kt` | **本次改动最多的文件**：`ui_changed`、`elements`、`find_text`、`tap_text`、滚动重试、坐标诊断 |
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

### 4.2 未验证 / 待确认 ⚠️

| 项 | 说明 |
| :--- | :--- |
| **图片→视觉的端到端** | 没有用真实 API Key 跑过"发图→模型描述图片"。结论来自 Pi 运行时探针 + 代码走查 |
| **`ui_changed` 阈值标定** | **阈值 1.0 是推理出来的，不是实测标定的**。未测过"静止画面两次截图的实测差值"。建议验证：静止页面连点空白处，看是否恒为 `false` |
| **`elements` 在真机的实测** | 用户跑的是 `tap_text`；`elements` 字段本身的真机样例未回传 |
| **坐标诊断日志** | 加完后未跑过，未确认日志里出现新字段 |
| **滚动重试** | 未触发过（首次就命中了），逻辑未实测 |
| **OCR 绝对零网络** | 模型确实内嵌，但 ML Kit 有遥测链路。静态分析无法断言"零网络请求"。**建议开飞行模式跑一次验证** |
| **MIUI 底部热区** | 怀疑但未证实（用户用"真实 bbox 而非目测"绕开了） |

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

- `bbox_px` 在**截图系**（588×1280）
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
| **A1** | 画面**完全没变**时不附图（用已有的指纹差，判 `差值 == 0` 而非 `ui_changed == false`） | 384 tok/次 | **无** | `PiAgentRunner.kt` 附图处 |
| **A2** | `elements` 瘦身（去掉信息重复的 `bbox_px`，只留 `bbox_norm`） | ~35% | **无** | `AgentModeController.kt` `elementsJson()` |
| **B1** | 图片默认只在 `screenshot` 附图；`tap`/`swipe`/`tap_text` 默认只回文本 + elements + ui_changed。加 additive 参数 `include_image` 按需索取 | ~80% 调用省 384 | 理论上不减少（可显式索取） | `PiAgentRunner.kt` + `AetherToolExecutor.kt` |
| **B2** | 引导模型优先用 `find_text`（本就不附图）而非反复 `screenshot` | — | 无 | 仅改 description |
| **C1** | 接上 `shouldAutoCompactContext`，阈值设低 | 把二次增长截断 | **有**：摘要化丢失细节 | `AetherViewModel.kt` → `PiKernelBridge.compactSession()` |
| **C2** | 定期重建 Pi 会话（重建时用 Aether 的 messages 作种子，而其中本来就不含 base64 → 累积图片自然清空） | 彻底 | **有**：丢内存态 | `prepareNativeAgentSession` 相关 |

**推荐**：先做 **A1 + A2**（零风险、零功能损失），观察后再考虑 B1。C 类会改变会话语义，风险最高。

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

1. **先补验证**（成本低、价值高）：
   - 标定 `ui_changed` 阈值（静止页面连点空白，应恒为 `false`）
   - 飞行模式跑一次 `tap_text`，确认 OCR 完全离线
   - 回传一条真实的 `elements` 样例 + 一条 `action_end` 诊断日志
2. **做 A1 + A2 降 token**（零风险，不影响功能）
3. **观察模型行为**后再决定是否上 B1
4. **元素定位的下一步**：视图树 / 无障碍层，用于覆盖**纯图标控件**（OCR 永远拿不到）

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
