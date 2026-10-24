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

## 核心功能

> 双端能力差异：**TG 实测、延迟门槛、爬虫模式为桌面版功能**；安卓版为多源抓取 + TCP 级连通性测试（数据源列表与桌面版一致）。

- 定时抓取免费代理列表并自动更新（刷新间隔可配置，默认 10 分钟，范围 1~1440 分钟），每轮重新抓池子、重测、剔除失效节点
- （桌面版）两阶段实测：先 TCP 连通性测试（5 秒超时，150 并发）剔除死节点；再对存活节点做 **TG 实测**——经 SOCKS5 代理 CONNECT Telegram 数据中心（149.154.167.51:443，客户端实际线路）+ 全链路访问 desktop.telegram.org（TLS+HTTP，验证无劫持）
- （桌面版）**TG 延迟门槛**：延迟 = 经代理到 Telegram 数据中心的往返；默认只保留 **<1000ms** 的节点（界面可调 500~60000ms），不达标直接剔除
- （桌面版）**known_good 沉淀**：上轮验证通过的节点存入 known_good.json，下轮优先复测并保留，不再每轮从零开始
- 仅保留适用于 Telegram 的代理（SOCKS5 / MTProto），排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等其他类型
- 严格 IPv4 校验（丢弃含掩码字符的非法地址）、host:port 去重、元数据提取（入库时间 / 地区 / ISP）
- 界面：可用代理列表（按 TG 实测延迟升序）、延迟三档着色、一键复制完整链接、一键唤起 Telegram Desktop 弹出「添加代理」窗口（不跳网页）、手动刷新、自动更新倒计时、错误提示；统计卡为 可用（TG 实测通过）/ TCP 通但 TG 不通 / 不通已剔除 / 已排除

## 数据源（2026-10-25 统一体测精选，多源并发抓取，失败自动回退镜像）

内置 5 个免费代理源，均经「抓取 → TCP → TG 两关实测」统一体检打分后入选；
raw.githubusercontent.com 直连失败时自动依次回退 cdn / gcore / fastly 三个 jsDelivr 镜像：

| 源 | TG 通过率（存活节点） | 特点 |
|---|---|---|
| zloi-user/hideip.me | ~45% | 人工筛选池，质量最高，带国家元数据 |
| proxyscrape API | ~24% | 实时验证接口，不走 GitHub |
| monosans/proxy-list | ~25% | 每 30 分钟自动验证 |
| TheSpeedX/PROXY-List | ~7% | 每 6 小时更新，补充数量多样性 |
| watchttvv/free-proxy-list | 小池 | 原始源，含地区 / ISP 元数据，含 MTProto |

已剔除的低产池（体测 TG 通过率 ≈ 0）：hookzof、proxifly、zevtyardt、jetkai、mmpx12。

- 支持 `scheme://host:port 元数据`、裸 `host:port`、`host:port|国家` 三种行格式
- **爬虫模式**：需要爬网页的源也能用——按行解析几乎无产出时，自动走通用抽取
  （HTML / JSON / 任意文本中的 ip:port 对，含 geonode 等 JSON API 的 ip/port 字段），
  误报由 TG 实测过滤
- 各源解析结果按 host:port 全局去重合并；单源超过 1000 条随机抽样，合并超过 3000 条随机抽样
- 可通过配置 / API 自定义数据源列表（桌面版 `config.json` 的 `proxy_list_urls`，安卓版在设置中追加自定义源）

## 快速开始

- 桌面版：见 [tgproxy/README.md](tgproxy/README.md)
- 安卓版：见 [tgproxy-android/README.md](tgproxy-android/README.md)
- 版本发布管理客户端：见 [version-release-client/README.md](version-release-client/README.md)
