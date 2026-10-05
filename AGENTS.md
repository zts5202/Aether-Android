> **接手本项目前先读 [`HANDOFF.md`](HANDOFF.md)** —— 项目定位、已完成的工作、逐文件变更清单、已验证 vs 未验证状态、必读的设计契约（Agent Mode 坐标系）、已知问题与待优化项、环境与真机操作手册。

所有更改默认只需在 Android 生效（本项目已移除 iOS 端）。

复盘记录：

- 共享 UI 与平台原生 UI 可能同时存在并分别作为实际入口。修改 `shared/src/commonMain` 后，必须确认 Android 的 `app` 是否还有同功能实现，并分别检查、编译和安装验证，不能仅凭共享层代码推断会生效。
- Compose 中使用 `Box` 叠加阴影时，`matchParentSize()` 子项不参与父容器尺寸测量。父容器必须至少有一个正常参与测量的内容子项（例如固定高度的 `Row`），否则胶囊、按钮等控件可能宽度变为 0 而完全消失。
- 安装完成后，必须用实际连接设备核对包名和安装结果，不能只看构建成功。
- 搜索文件或代码时，默认始终排除缓存目录、第三方源码目录、构建目录及其他生成文件目录，避免被无用信息淹没；除非明确知道自己需要搜索这些目录。

真机安装流程：

- **目标设备**：小米 25102RKBEC（HyperOS / Android 16 / arm64-v8a）。serial 每次用 `adb devices -l` 现取，不要写死在仓库里。

- Android：先用 `adb devices -l` 确认目标 serial（多设备环境禁止使用不带 `-s` 的命令），再执行 `./gradlew :app:assembleDebug --no-daemon`。

- **设备连不上时的判断**：若 `adb devices -l` 显示 `unauthorized`，说明手机上的「允许 USB 调试」弹窗未确认，需要用户手动在手机上点「允许」（建议勾选「一律允许」），**不要反复重试**。若设备列表为空，依次检查：USB 调试是否开启、数据线是否支持数据传输（纯充电线不行）、是否缺 OEM USB 驱动。这类问题只能由用户处理，应向用户说明而不是反复重跑命令。

- **不要用 `adb install`**：小米 HyperOS 会拦截，报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。这是系统限制，与 APK 本身无关，不要据此判断包有问题。改走推送 + 系统安装器：

  ```powershell
  adb -s <serial> push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/aether-debug.apk
  adb -s <serial> shell pm install -r /data/local/tmp/aether-debug.apk
  adb -s <serial> shell rm -f /data/local/tmp/aether-debug.apk
  ```

- **安装后核对包名**：debug 变体在 `app/build.gradle.kts` 里带 `applicationIdSuffix = ".debug"`，实际包名是 **`com.baimoqilin.aether.debug`**，**不是** `com.baimoqilin.aether`。核对错名字会查不到，容易误判成安装失败：

  ```powershell
  adb -s <serial> shell pm path com.baimoqilin.aether.debug
  adb -s <serial> shell dumpsys package com.baimoqilin.aether.debug
  ```

  必须确认 `versionCode`、`versionName`、`primaryCpuAbi=arm64-v8a`。

- 安装后启动一次确认不崩溃，再交付结论：

  ```powershell
  adb -s <serial> logcat -c -b crash
  adb -s <serial> shell am start -W -n com.baimoqilin.aether.debug/com.zhousl.aether.MainActivity
  adb -s <serial> logcat -d -b crash
  ```

- 默认禁止构建或安装 `app-debug-androidTest.apk` 等测试包；以后不需要安装测试包，除非用户明确要求。

- 更完整的排查命令（诊断日志、容器 DNS、扩展目录、会话文件）见 [`HANDOFF.md`](HANDOFF.md) 第 7 节。

## Cursor Cloud specific instructions

云端虚拟机没有小米真机。`adb devices -l` 列出目标 serial 之后，才使用上面的推送安装流程。云端验证用这些命令：

- `pi-bridge`：`npm run check`，然后 `npm test`。`package.json` 要求 Node `>=22.19.0`。安装脚本把该版本放到 `/usr/local/lib/nodejs`，并链接到 `/usr/local/cargo/bin`，登录 shell 会优先使用这个 Node。
- `packages/extension-api`：`npm run check`
- Android：`./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`

Android SDK 在 `/opt/android-sdk`。安装脚本写入 gitignore 的 `local.properties`（`sdk.dir=/opt/android-sdk`）。镜像自带 JDK 21，可以编译 `jvmTarget` 17。`~/.gradle/gradle.properties` 把 Gradle 堆限制在 3g，因为仓库 `gradle.properties` 申请 8g，而云端机器大约 16GB 内存。debug APK 只含 `arm64-v8a`（`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.baimoqilin.aether.debug`），不能安装到这台 x86_64 虚拟机。
