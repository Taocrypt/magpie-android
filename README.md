# magpie for Android

[magpie](https://github.com/yetone/magpie) 的非官方安卓外壳：在手机上跑起 magpie 本地网关，用原生界面包好它的网页管理面板，再加上悬浮窗、远程访问和应用内更新。

An unofficial Android shell for [magpie](https://github.com/yetone/magpie). It runs the magpie gateway on your phone and wraps its web UI with a floating window, remote access and in-app updates.

> 与 magpie 上游作者无隶属关系。magpie 本身的功能、文档和问题反馈请看[上游仓库](https://github.com/yetone/magpie)。

## 下载

官网：<https://taocrypt.github.io/magpie-android/>

到 [Releases](https://github.com/Taocrypt/magpie-android/releases/latest) 下载 `magpie-<版本>.apk`，可以直接覆盖安装。

- 系统要求：Android 14 及以上（minSdk 34），arm64 设备
- 包名：`com.magpie.android`
- 所有正式版都用同一把密钥签名（CN=Taocrypt）。外壳的应用内更新会核对包名和签名，不一致就不装

## 功能

**网关**
- 首次启动从官方渠道下载 `magpie-cli-android-arm64`（usemagpie.ai，失败时退到 GitHub），校验 SHA-256，不把 CLI 打进 APK
- 前台服务常驻，可开机自启；通知栏和快捷设置磁贴都能启停或重启
- 顶栏的状态胶囊可以重启或停止网关，停止后显示「已停止」，并提供「启动网关」
- 内置 Bun（`libmagpiebun.so`），magpie 插件可以直接用

**界面**
- 原生外壳加载 magpie 网页面板，并注入移动端样式和脚本（`assets/`）
- 网页的 alert / confirm / prompt 和所有原生提示统一为底部弹窗
- 支持网页内上传和下载文件；渲染进程崩溃会自动恢复

**悬浮窗**
- 可调透明度、尺寸、悬浮球大小，支持自动贴边、自动隐藏和点击穿透
- 点桌面图标或快捷设置磁贴，会以悬浮球打开并回到桌面

**远程访问与网络**
- Cloudflare 临时隧道、Tailscale（用户态网络）、局域网（0.0.0.0）和 mDNS
- cloudflared 和 Tailscale 只在第一次开启时下载，之后有更新也要你确认才装
- 网络兼容模式：本地 DNS 代理固定在 3435 端口，可以再串一个上游代理（http / socks5，可带账号）

**维护**
- 检查更新分为「内核」（magpie CLI，支持回滚）和「外壳」（本仓库的 Release）。启动时会自动检查外壳，有新版就弹出更新日志，可以选择浏览器下载或直接下载。下载镜像对两者都生效
- 空间管理：按大小列出各分类占用，可以单独清理，也可以一键清理
- 查看日志、导出崩溃日志与诊断信息

## 从源码构建

这个项目没有用 Gradle，`build.sh` 依次调用 aapt2 → javac → d8 → zip → zipalign，输出未签名的 `build/aligned.apk`。

需要：
- JDK 17 或更高
- Android SDK 的 `android.jar`（API 34）、`aapt2`、`zipalign`、`d8.jar`
- 一个安卓 arm64 版的 Bun 可执行文件（用 NDK 构建，链接器为 `/system/bin/linker64`）。它约 85 MB，没有放进仓库。放到 `lib/arm64-v8a/libmagpiebun.so`，或者用 `BUN_SO` 指定路径

```bash
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export AAPT2=$ANDROID_HOME/build-tools/34.0.0/aapt2
export ZIPALIGN=$ANDROID_HOME/build-tools/34.0.0/zipalign
export BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0   # 需要 lib/d8.jar
export JDK_BIN=$JAVA_HOME/bin
export BUN_SO=/path/to/bun-android-arm64
bash build.sh
```

然后用你自己的密钥签名（要开 V1/V2/V3）：

```bash
apksigner sign --ks your.p12 --ks-type PKCS12 --min-sdk-version 1 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out magpie.apk build/aligned.apk
```

自己签名的包和 Release 里的签名不同，不能覆盖安装官方版本，外壳的应用内更新也会拒绝安装。

版本号要同时改 `AndroidManifest.xml` 和 `build.sh` 里的 `--version-code` / `--version-name`。

## 目录

```
AndroidManifest.xml
build.sh                    构建脚本
src/com/magpie/android/     Java 源码（MainActivity、MagpieGateway、FloatService、Tunnels、DnsProxy、Updater、ShellUpdater …）
res/                        布局、图标、主题
assets/                     注入网页的 magpie-mobile.js / .css、magpie-float.css
```

## 发布约定

Release 的 tag 用 `v<版本>`（如 `v1.4.7`），附件命名为 `magpie-<版本>.apk`，说明里写更新日志。外壳靠这个约定检查和下载更新。

## 协议

[MIT](LICENSE)，与上游 magpie 一致。第三方组件见 [NOTICE.md](NOTICE.md)。
