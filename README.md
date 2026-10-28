# tgproxy — Telegram 代理管理工具

本仓库包含 Telegram 代理管理工具的两个平台实现，以及一个独立的版本发布管理客户端：

- **桌面版**（Python 本地服务 + Web UI）
- **安卓版**（Kotlin + Jetpack Compose 原生应用）
- **版本发布管理客户端**（单文件 HTML，完整的软件发布流程）

## 仓库结构

```
├── tgproxy/                   # 桌面版：Python 标准库，零第三方依赖，python app.py 一条命令启动
├── tgproxy-android/           # 安卓版：Kotlin + Compose，Android Studio 打开即构建
├── version-release-client/    # 版本发布管理客户端：单文件 HTML，双击即用
└── docs/release-notes/        # 各版本 Release 正文归档（与线上逐字节一致）
```

## 核心功能

> 双端能力（安卓版自 v2.0.0 起对齐，连败拉黑自桌面 v1.9.0 / 安卓 v2.1.0 起对齐）：**TG 实测双闸、1000ms 延迟门槛、known_good 沉淀、连败拉黑双端一致**；**源自动体检换源与爬虫模式仍为桌面版功能**（安卓版数据源列表与桌面版一致、固定精选 5 源）。

- 定时抓取免费代理列表并自动更新（刷新间隔可配置，默认 10 分钟，范围 1~1440 分钟），每轮重新抓池子、重测、剔除失效节点
- （双端）两阶段实测：先 TCP 连通性测试（5 秒超时；桌面版 150 并发 / 安卓版 128 并发）剔除死节点；再对存活节点做 **TG 实测**——经 SOCKS5 代理 CONNECT Telegram 数据中心（149.154.167.51:443，客户端实际线路，其往返毫秒即节点延迟）+ 全链路访问 desktop.telegram.org（TLS+HTTP，验证无劫持）；安卓版另有分阶段进度展示（抓取 → TCP 预筛 → TG 实测，逐条 done/total）
- （双端）**TG 延迟门槛**：延迟 = 经代理到 Telegram 数据中心的往返；默认只保留 **<1000ms** 的节点，不达标直接剔除（桌面版界面可调 500~60000ms，安卓版固定 1000ms）
- （双端）**known_good 沉淀**：上轮验证通过的节点存下来（桌面版 data/known_good.json / 安卓版应用私有目录），下轮优先复测并保留，不再每轮从零开始；开关关闭后沉淀文件保留但不再读取/写入（桌面版设置里「自动保存可用节点」/ 安卓版设置弹层「known_good 沉淀」开关）；安卓版重启时列表先行展示上轮沉淀节点，无需等待刷新
- （双端）**连败拉黑（桌面 v1.9.0 / 安卓 v2.1.0）**：IP 池里连续 3 轮都不通的节点自动拉黑 48 小时、跳过测试，节省筛选时间（免费池 IP 会轮换，到期自动解封重测）；两关通过（含超门槛）即清零连败；拉黑表持久化（桌面版 data/known_bad.json / 安卓版应用私有目录），双端统计区均显示「连败拉黑」计数
- （桌面版）**源自动体检与换源**：逐源统计每轮 TG 验证产出（data/source_health.json）；连续 3 轮抓取失败或连续 3 轮 0 产出的源自动停用，并从内置候选池（10 个备选，含爬虫源）按顺序自动补位，变更持久化到配置，界面有提示
- （桌面版）**体检的两道保护**：①**分级比例**——≥5 个源时 0 产出占比 ≥80%、3~4 个源时至多 1 个源有产出、≤2 个源时仅全部为 0，即判为环境异常（Telegram 封锁 / 门槛调严），本轮不累计零产出、不停用、不补位。≤2 个源时无法区分「源变垃圾」与「环境异常」，故单源长期 0 产出会恒判环境异常而不换源；②**宽恕路径**——桌面界面不提供源编辑，编辑 `data/config.json` 的 `proxy_list_urls` 加回被停用的源后**重启**即视为「重新观察」，清除其停用标记与历史
- 仅保留适用于 Telegram 的代理（SOCKS5 / MTProto），排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等其他类型
- 严格 IPv4 校验（丢弃含掩码字符的非法地址）、host:port 去重、元数据提取（入库时间 / 地区 / ISP）
- 界面：可用代理列表（按 TG 实测延迟升序）、延迟三档着色、一键复制完整链接、一键唤起 Telegram Desktop 弹出「添加代理」窗口（不跳网页）、手动刷新、自动更新倒计时、错误提示；统计：可用（TG 实测通过）/ 未验证（MTProto）/ TCP 通但 TG 不合格（含超慢）/ 不通已剔除 / 已排除 / 连败拉黑（安卓版另有超门槛单列与仪表盘大卡布局）

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
- 可通过配置 / API 自定义数据源列表（桌面版 `data/config.json` 的 `proxy_list_urls`，安卓版在设置中追加自定义源）

## 快速开始

- 桌面版：见 [tgproxy/README.md](tgproxy/README.md)
- 安卓版：见 [tgproxy-android/README.md](tgproxy-android/README.md)
- 版本发布管理客户端：见 [version-release-client/README.md](version-release-client/README.md)

## 版本与发布

v2.1.0 及以后版本的 Release 正文归档在 [`docs/release-notes/`](docs/release-notes/)，与 GitHub 上的 Release 正文**逐字节一致**（从 API 取回，不是另写一份）。更早的版本仍以 GitHub Releases 上的正文为准。

| 版本 | 要点 | 归档 |
|---|---|---|
| v2.2.1 | 商店版 Telegram 唤起假阴性修复；状态文件收敛到 `data/`；限制内置窗口体积 | [v2.2.1.md](docs/release-notes/v2.2.1.md) |
| v2.2.0 | 桌面独立窗口（浏览器 `--app` 模式，零新依赖）；关窗口即退出；唤起兜底 | [v2.2.0.md](docs/release-notes/v2.2.0.md) |
| v2.1.0 | 双端数据契约对齐；性能收官（中位 127.63s → 44.76s） | [v2.1.0.md](docs/release-notes/v2.1.0.md) |

二进制产物在 [GitHub Releases](https://github.com/200111226011qw-debug/tgproxy/releases) 下载。
安卓版目前仍是 **Debug 签名包**（未建 release keystore），源码可直接构建。
