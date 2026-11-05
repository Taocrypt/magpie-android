<div align="center">

<img src="docs/icon.png" alt="magpie" width="96">

# magpie · Android

**把 magpie 本地网关装进口袋：在手机上直接运行，无需服务器，无需 root。**

[![Release](https://img.shields.io/github/v/release/Taocrypt/magpie-android?style=flat-square&label=Release)](https://github.com/Taocrypt/magpie-android/releases/latest)
[![Android 14+ arm64](https://img.shields.io/badge/Android-14%2B%20arm64-c4512a?style=flat-square)](#系统要求)
[![magpie](https://img.shields.io/badge/upstream-yetone%2Fmagpie-211d19?style=flat-square)](https://github.com/yetone/magpie)
[![License: MIT](https://img.shields.io/badge/License-MIT-64748B?style=flat-square)](LICENSE)

**简体中文** · [English](#english)

[下载 APK](https://github.com/Taocrypt/magpie-android/releases/latest) · [项目主页](https://taocrypt.github.io/magpie-android/) · [功能](#功能) · [工作原理](#工作原理) · [从源码构建](#从源码构建) · [常见问题](#常见问题) · [更新日志](CHANGELOG.md)

</div>

> [!NOTE]
> 这是一个**社区项目**，与 [yetone/magpie](https://github.com/yetone/magpie) 的作者**没有任何从属或合作关系**。外壳相关的问题请在本仓库反馈；magpie 本身的功能与问题请到上游仓库。

## 这是什么

**magpie for Android**（包名 `com.magpie.android`）是一个 Android 外壳应用：

- 在手机上以子进程方式运行 **[magpie](https://github.com/yetone/magpie)** 本地网关。magpie 把各家模型统一到一处管理，例如让 Codex 走 DeepSeek、让 Claude Code 走 Kimi；
- 用全屏 WebView 打开 magpie 的网页管理面板，并注入移动端适配的样式和脚本；
- 补上 Android 原生能力，包括常驻服务、悬浮窗、快捷设置磁贴、局域网和远程访问、空间管理、日志诊断，以及内核和外壳的应用内更新。

magpie CLI **不打包在 APK 里**：首次启动时从官方渠道下载 `magpie-cli-android-arm64` 并校验 SHA-256。APK 只内置供 magpie 插件使用的 Bun 运行时。

## 功能

| | |
| --- | --- |
| **首次启动自动部署** | 先从 usemagpie.ai 获取最新版 `magpie-cli-android-arm64`，失败时改用 GitHub `yetone/magpie-releases`，校验 SHA-256 后安装。 |
| **下载镜像** | 可设置 GitHub 下载镜像前缀，对内核与外壳的下载都生效，国内网络也能完成部署和更新。 |
| **前台服务常驻** | 网关由前台服务托管，可开机自启、申请忽略电池优化；通知栏与快捷设置磁贴可启停或重启网关。 |
| **状态胶囊** | 顶栏状态胶囊实时显示运行状态，点按可重启或停止网关；停止后可一键重新启动。 |
| **内置 Bun** | 随 APK 分发 Android arm64 版 Bun（`libmagpiebun.so`），magpie 插件开箱即用。 |
| **移动端适配** | 向网页面板注入移动端样式与脚本；网页的 alert / confirm / prompt 与原生提示统一为底部弹窗；支持网页内上传和下载文件，渲染进程崩溃后自动恢复。 |
| **悬浮窗** | 面板可以小窗悬浮在其他应用之上，可调透明度、尺寸和悬浮球大小，支持自动贴边、自动隐藏与点击穿透；桌面图标与磁贴以悬浮球打开并回到桌面。 |
| **网络兼容模式** | 内置 DNS 代理（固定端口 3435），解决 Go 静态程序在 Android 上无法解析域名的问题；可串联上游代理（HTTP / SOCKS5，支持认证），外壳自身的下载同样经过它。 |
| **局域网访问** | 一键监听 `0.0.0.0`，同一 Wi-Fi 下的电脑可直接使用手机上的网关；可选 mDNS 广播。端口可自定义。 |
| **远程访问** | 通过 Cloudflare Quick Tunnel 生成临时公网地址，或用 Tailscale（用户态网络栈）加入你的 tailnet。组件只在首次开启时下载，后续更新需你确认。 |
| **空间管理** | 以环形图和分类卡片展示二进制、缓存、日志、隧道组件等的占用，按大小排序，可分类清理或一键清理。 |
| **内核更新** | 检查 magpie CLI 新版本（可选稳定通道或全部版本），校验后安装，保留上一版本可一键回滚。 |
| **外壳自更新** | 启动时检查本仓库 Releases 的新版 APK 并显示更新日志，可用浏览器下载或直接下载；校验包名、签名证书与 SHA-256 后调起系统安装，进度显示在通知栏。 |
| **日志与诊断** | 查看网关日志，导出崩溃日志与诊断信息。 |

## 工作原理

```
┌──────────────────────── Android 手机 ────────────────────────┐
│  magpie (com.magpie.android)                                  │
│   ├─ 前台服务 ── 进程守护 / DNS 代理 / 隧道                    │
│   │    └─ magpie 网关    127.0.0.1:3425   ← 你的 AI 客户端     │
│   │       └─ 网页面板    127.0.0.1:3430                        │
│   └─ 全屏 WebView ──→ 网页面板（注入移动端样式与脚本）         │
└───────────────────────────────────────────────────────────────┘
        局域网 0.0.0.0 / Cloudflare Tunnel / Tailscale（可选）
```

- 网关默认监听 `127.0.0.1:3425`，网页面板默认 `127.0.0.1:3430`，均可在「应用设置」中修改；网络兼容模式的 DNS 代理固定为 `3435`。
- 二进制保存在应用私有目录。应用的 `targetSdk` 为 28，这是 Android 允许执行应用自行下载的可执行文件的前提（targetSdk ≥ 29 会被系统禁止）。
- 默认只监听本机；开启局域网或远程访问后，网关才对外可达。

## 系统要求

- Android **14（API 34）或更高**，**arm64-v8a**（64 位 ARM）设备。
- 首次启动需要能访问 usemagpie.ai 或 GitHub（也可设置镜像），以下载 magpie CLI。
- 远程访问会额外下载 cloudflared 或 Tailscale。

## 安装

1. 打开 [Releases](https://github.com/Taocrypt/magpie-android/releases/latest)，下载最新的 `magpie-<版本>.apk`。之后的版本可以在应用内「检查更新」直接升级。
2. 在手机上安装（需要允许「安装未知来源应用」）。
3. 首次打开时等待 magpie CLI 下载并校验完成；网络不畅时可在「检查更新 → 下载设置」中配置镜像。
4. 允许通知权限（前台服务需要），建议在「应用设置」中开启「忽略电池优化」。
5. 进入网页面板，按 magpie 的方式添加模型与密钥；客户端把地址指向 `http://127.0.0.1:3425`（局域网则用手机 IP）。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 网络、网络状态 | 运行本地网关、下载组件、远程访问 |
| 前台服务、通知 | 让网关在后台常驻，显示状态、操作按钮与更新进度 |
| 忽略电池优化 | 防止熄屏后系统冻结服务（可选） |
| 开机启动 | 「开机自启」开关（可选） |
| 悬浮窗 | 悬浮窗与悬浮球（仅在你打开悬浮窗时申请） |
| 本地网络 | 局域网访问与 mDNS |
| 安装未知应用 | 外壳自更新时调起系统安装（首次直接下载时申请） |

应用不包含任何统计或追踪 SDK。

## 从源码构建

项目不依赖 Gradle：`build.sh` 直接调用 Android SDK 工具链 `aapt2 → javac → d8 → zip → zipalign`，输出未签名的 `build/aligned.apk`，再用 `apksigner` 签名。

需要：

- JDK 17 或更高
- Android SDK：`build-tools`（含 `aapt2`、`zipalign`、`lib/d8.jar`、`lib/apksigner.jar`）与 `platforms/android-34/android.jar`
- Android arm64 版 Bun 可执行文件（以 NDK 构建，链接器为 `/system/bin/linker64`）。约 85 MB，不在仓库中；放到 `lib/arm64-v8a/libmagpiebun.so`，或用 `BUN_SO` 指定路径

```sh
export BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export AAPT2=$BUILD_TOOLS/aapt2
export ZIPALIGN=$BUILD_TOOLS/zipalign
export JDK_BIN=$JAVA_HOME/bin
export BUN_SO=/path/to/bun-android-arm64
bash build.sh                                   # 输出 build/aligned.apk
```

**签名密钥不包含在仓库中**，请使用你自己的 keystore，并开启 V1 / V2 / V3：

```sh
# 生成一个自己的签名密钥（只需一次）
keytool -genkeypair -keystore my-release.p12 -storetype PKCS12 \
  -alias magpie -keyalg RSA -keysize 4096 -validity 10000

java -jar $BUILD_TOOLS/lib/apksigner.jar sign \
  --ks my-release.p12 --ks-type PKCS12 --ks-key-alias magpie --min-sdk-version 1 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out magpie.apk build/aligned.apk
```

| 变量 | 说明 |
| --- | --- |
| `BUILD_TOOLS` | build-tools 目录（需含 `lib/d8.jar`） |
| `ANDROID_JAR` | 编译用 `android.jar`（API 34） |
| `AAPT2` / `ZIPALIGN` | 对应工具路径 |
| `JDK_BIN` | JDK 的 `bin` 目录 |
| `BUN_SO` | Android arm64 版 Bun 可执行文件 |
| `ANDROID_ROOT` | 以上工具的公共根目录（可选） |

版本号需同时修改 `AndroidManifest.xml` 与 `build.sh` 中的 `--version-code` / `--version-name`。

> 发布新版本时，Release 的 tag 用 `v<版本>`，APK 附件命名为 `magpie-<版本>.apk`，说明中写更新日志。应用内的外壳自更新按这个约定查找安装包，并校验包名与签名证书。

> 用自己的密钥签名的 APK 与 Releases 中的官方包签名不同，不能覆盖安装，外壳自更新也会拒绝安装，需要先卸载（会清除应用数据）。

## 项目结构

```
AndroidManifest.xml
build.sh                    构建脚本
src/com/magpie/android/     Java 源码（MainActivity、MagpieGateway、MagpieService、FloatService、
                            Tunnels、DnsProxy、Updater、ShellUpdater、UpdateNotifier、Storage …）
res/                        布局、图标、主题
assets/                     注入网页的 magpie-mobile.js / .css、magpie-float.css
docs/                       项目主页（GitHub Pages）
```

## 常见问题

**为什么首次启动要下载东西？**
magpie CLI 直接来自上游官方渠道并校验 SHA-256，APK 只保留外壳。这样体积更小，上游更新也不必等外壳重新发版。下载失败时可以配置镜像后重试。

**后台被杀 / 熄屏后网关不通？**
在「应用设置」中开启「忽略电池优化」；部分国产系统还需要在系统设置中允许「自启动」「后台运行」，并在多任务界面锁定应用。

**我的数据存在哪里？安全吗？**
配置与密钥保存在应用私有目录，外壳不会上传到任何地方。开启局域网或远程访问时，请只在可信网络中使用，并妥善设置网关密钥。

**内核更新后出了问题？**
在「检查更新」的内核卡片中可回滚到上一版本。

**这是官方客户端吗？**
不是。本项目是社区作品，与 magpie 的作者无关。上游项目本身的问题请到 [yetone/magpie](https://github.com/yetone/magpie) 反馈。

## 许可证与致谢

本项目以 [MIT](LICENSE) 许可证发布，与上游 magpie 相同。运行时下载或随 APK 分发的第三方组件及其许可证见 [NOTICE.md](NOTICE.md)：

- [magpie](https://github.com/yetone/magpie) — MIT（「magpie」名称与图标归上游项目所有）
- [Bun](https://github.com/oven-sh/bun)（随 APK 分发，`libmagpiebun.so`）— MIT
- [cloudflared](https://github.com/cloudflare/cloudflared) — Apache-2.0
- [Tailscale](https://github.com/tailscale/tailscale) — BSD-3-Clause

---

## English

**magpie for Android** (`com.magpie.android`) runs the [magpie](https://github.com/yetone/magpie) local gateway **on your phone** as a child process and shows its web console in a fullscreen WebView with mobile-friendly styling. No server, no root.

- **First launch** downloads the official `magpie-cli-android-arm64` from usemagpie.ai (falling back to GitHub) with SHA-256 verification; an optional download mirror helps on slow networks. A Bun runtime for magpie plugins is bundled.
- **Always on**: foreground service, boot start, battery-optimisation exemption, a status chip to restart or stop the gateway, notification and Quick Settings tile controls.
- **Native extras**: floating window and bubble, bottom-sheet dialogs for web alerts, file upload and download, LAN access with mDNS, remote access via Cloudflare quick tunnel or Tailscale, and a DNS proxy (port 3435) with optional upstream HTTP / SOCKS5 proxy.
- **Maintenance**: storage manager, magpie CLI updates with rollback, in-app shell updates from this repo's Releases with changelog and notification progress, logs and crash export.

Defaults: gateway on `127.0.0.1:3425`, web console on `127.0.0.1:3430`. Requires Android 14+ (API 34) on arm64. Build from source with `build.sh` (Android SDK build-tools + JDK 17 + an Android arm64 Bun binary; sign with your own keystore).

This is a **community project, not affiliated with** the magpie authors. MIT licensed; third-party notices in [NOTICE.md](NOTICE.md).
