# tgproxy — Telegram 代理管理工具

本仓库包含 Telegram 代理管理工具的两个平台实现，以及一个独立的版本发布管理客户端：

- **桌面版**（Python 本地服务 + Web UI）
- **安卓版**（Kotlin + Jetpack Compose 原生应用）
- **版本发布管理客户端**（单文件 HTML，完整的软件发布流程）

## 仓库结构

```
├── tgproxy/                   # 桌面版：Python 标准库，零第三方依赖，python app.py 一条命令启动
├── tgproxy-android/           # 安卓版：Kotlin + Compose，Android Studio 打开即构建
└── version-release-client/    # 版本发布管理客户端：单文件 HTML，双击即用
```

## 核心功能（两版本一致）

- 定时抓取免费代理列表并自动更新（刷新间隔可配置，默认 10 分钟，范围 1~1440 分钟）
- 本地对代理做连通性与延迟测试（TCP 连接，5 秒超时，20 并发），自动过滤不可用节点
- 仅保留适用于 Telegram 的代理（SOCKS5 / MTProto），排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等其他类型
- 严格 IPv4 校验（丢弃含掩码字符的非法地址）、host:port 去重、元数据提取（入库时间 / 地区 / ISP）
- 界面：可用代理列表（按延迟升序）、延迟三档着色、一键复制完整链接、手动刷新、自动更新倒计时、错误提示

## 数据源

https://raw.githubusercontent.com/watchttvv/free-proxy-list/refs/heads/main/proxy.txt

## 快速开始

- 桌面版：见 [tgproxy/README.md](tgproxy/README.md)
- 安卓版：见 [tgproxy-android/README.md](tgproxy-android/README.md)
- 版本发布管理客户端：见 [version-release-client/README.md](version-release-client/README.md)
