# 妙享快连过滤 · MiConnectFilter

[中文](#中文) | [English](#english)

## 中文

LSPosed（Zygote）模块，过滤小米耳机「妙享快连」发现弹窗：只放行白名单里的耳机，其它小米耳机的弹窗一律拦截。在 KernelSU + LSPosed 环境下、澎湃 OS 3 / Android 16 实测可用。

### 功能

- **严格执行白名单（默认且写死，无开关）**：弹窗场景下，只要是小米系耳机、不在白名单，不管配没配对都拦截；白名单内照常弹窗。
- **临时放行**：设置页一键暂停拦截 1 / 3 / 5 分钟（最长 5 分钟），到期自动恢复，方便配对新设备。
- **名称同步**：读取系统蓝牙别名，在系统里给耳机改过名后，白名单里的名称会自动更新。
- **诊断日志**：可开关；关着时模块零日志输出。开着时记录每个弹窗事件的进程、入口、设备 MAC、判定与原因，可在设置页查看/清除/导出。
- **不禁用 MiLink、不停服务、不做全局通知拦截**：只在目标进程内逐条判定带设备信息的弹窗与通知。

### 环境要求

- Android 16 / 澎湃 OS 3（其它版本未测）
- KernelSU（或其它 Root 方案）+ LSPosed 2.x（libxposed API 101+）

### 使用

1. 安装 APK，在 LSPosed 中启用模块，作用域用「推荐」全选（6 个：蓝牙、设备互联、蓝牙扩展等）。
2. 点设置页「重启作用域应用」，或直接重启手机。
3. 在设置页把**自己的耳机先加进白名单**（可从已配对列表添加），否则严格模式会连自家耳机的开盖弹窗一起拦。
4. 需要配对新耳机时，用「临时放行」暂停拦截几分钟。

### 配置是怎么送达系统进程的

蓝牙域系统进程被 SELinux 挡住、读不到 `/data/local/tmp` 的配置文件，因此配置走三路冗余：文件 + 广播直发（`ConfigBus`）+ 进程启动时主动拉取，并落盘到各目标应用的私有目录缓存；进程每 60 秒自动重拉一次，改设置不必重启进程。

### 构建

手动工具链（kotlinc + aapt2 + d8 + apksigner），见 `build.sh`。核心判定逻辑有 JVM 单测：`test/FilterCoreTest.kt`。

### 下载

到 [Releases](https://github.com/Wang2035/MiConnectFilter/releases) 下载最新 APK。

---

## English

An LSPosed (Zygote) module that filters Xiaomi earbuds' "Mi Connect" fast-pair popups: only whitelisted earbuds may pop up; every other Xiaomi earbud's popup is blocked. Tested on HyperOS 3 / Android 16 with KernelSU + LSPosed.

### Features

- **Strict whitelist, always on (hard-coded, no toggle)**: in a popup context, any Xiaomi earbud that is not whitelisted gets blocked — paired or not. Whitelisted devices pop up as usual.
- **Temporary bypass**: pause blocking for 1 / 3 / 5 minutes (5 max) from the settings page to pair a new device; it resumes automatically.
- **Name sync**: reads the system Bluetooth alias, so renamed earbuds get their new name in the whitelist automatically.
- **Diagnostic logging**: optional; when off, the module emits no logs at all. When on, every popup event is recorded with process, entry point, device MAC, decision and reason, viewable / clearable / exportable in the settings page.
- **No MiLink disabling, no service killing, no global notification blocking**: decisions are made per popup/notification carrying device info, inside the target processes only.

### Requirements

- Android 16 / HyperOS 3 (other versions untested)
- KernelSU (or another root solution) + LSPosed 2.x (libxposed API 101+)

### Usage

1. Install the APK, enable the module in LSPosed, and select all recommended scope apps (6: Bluetooth, MiLink, Bluetooth extensions, etc.).
2. Tap "Restart scope apps" in settings, or reboot the phone.
3. **Add your own earbuds to the whitelist first** (you can pick from paired devices) — strict mode otherwise blocks their lid-open popup too.
4. Use the temporary bypass when pairing a new device.

### How config reaches system processes

Bluetooth-domain system processes cannot read the config file in `/data/local/tmp` (SELinux), so config is delivered redundantly: file + direct broadcast (`ConfigBus`) + an active pull at process start, cached in each target app's private directory; processes re-pull every 60 seconds, so settings take effect without restarts.

### Build

Manual toolchain (kotlinc + aapt2 + d8 + apksigner), see `build.sh`. The core decision logic has JVM unit tests: `test/FilterCoreTest.kt`.

### Download

Get the latest APK from [Releases](https://github.com/Wang2035/MiConnectFilter/releases).
