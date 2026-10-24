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

- 定时抓取免费代理列表并自动更新（刷新间隔可配置，默认 10 分钟，范围 1~1440 分钟），每轮重新抓池子、重测、剔除失效节点
- 本地对代理做连通性与延迟测试（TCP 连接，5 秒超时，150 并发），测试不通的代理直接从列表剔除，列表只保留可用节点
- 仅保留适用于 Telegram 的代理（SOCKS5 / MTProto），排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等其他类型
- 严格 IPv4 校验（丢弃含掩码字符的非法地址）、host:port 去重、元数据提取（入库时间 / 地区 / ISP）
- 界面：可用代理列表（按延迟升序）、延迟三档着色、一键复制完整链接、一键唤起 Telegram Desktop 弹出「添加代理」窗口（不跳网页）、手动刷新、自动更新倒计时、错误提示

## 数据源（多源并发抓取，失败自动回退镜像）

内置 6 个免费代理源（均为 SOCKS5，raw.githubusercontent.com 直连失败时自动依次回退 cdn / gcore / fastly 三个 jsDelivr 镜像）：

| 源 | 特点 |
|---|---|
| watchttvv/free-proxy-list | 原始源，含入库时间 / 地区 / ISP 元数据，含 MTProto |
| monosans/proxy-list | 每 30 分钟自动验证更新，质量较高 |
| TheSpeedX/PROXY-List | 每 6 小时更新，数量适中 |
| hookzof/socks5_list | 多源聚合大列表 |
| zevtyardt/proxy-list | 大列表 |
| proxifly/free-proxy-list | 大列表（socks5:// 前缀格式） |

- 支持两种行格式：`scheme://host:port 元数据` 与裸 `host:port`（无 scheme 的主流列表格式，按 SOCKS5 处理）
- 各源解析结果按 host:port 全局去重合并；单源超过 1000 条随机抽样，合并超过 3000 条随机抽样，保证单轮刷新控制在分钟级
- 可通过配置 / API 自定义数据源列表（桌面版 `config.json` 的 `proxy_list_urls`，安卓版在设置中追加自定义源）

## 快速开始

- 桌面版：见 [tgproxy/README.md](tgproxy/README.md)
- 安卓版：见 [tgproxy-android/README.md](tgproxy-android/README.md)
- 版本发布管理客户端：见 [version-release-client/README.md](version-release-client/README.md)
