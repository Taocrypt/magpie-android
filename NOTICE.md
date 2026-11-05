# 第三方说明 / Third-party notices

本项目是 [magpie](https://github.com/yetone/magpie) 的非官方安卓外壳，与上游作者无隶属关系。上游采用 MIT 协议，本仓库沿用 MIT。

| 组件 | 来源 | 协议 | 如何使用 |
|---|---|---|---|
| magpie CLI 与网页界面 | [yetone/magpie](https://github.com/yetone/magpie)（Copyright (c) 2026 yetone），发布于 [usemagpie.ai](https://usemagpie.ai) / [yetone/magpie-releases](https://github.com/yetone/magpie-releases) | MIT | 首次启动时下载官方 `magpie-cli-android-arm64` 并校验 SHA-256，不随 APK 分发 |
| Bun | [oven-sh/bun](https://github.com/oven-sh/bun) | MIT（内含组件各有其协议，见 Bun 仓库） | 安卓 arm64 构建，以 `libmagpiebun.so` 打包进 APK，供 magpie 插件使用；不在本仓库中 |
| cloudflared | [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared) | Apache-2.0 | 用户首次开启 Cloudflare 隧道时下载官方二进制 |
| Tailscale | [tailscale/tailscale](https://github.com/tailscale/tailscale) | BSD-3-Clause | 用户首次开启 Tailscale 时下载官方静态包并校验 |

“magpie” 名称与图标归上游项目所有。
