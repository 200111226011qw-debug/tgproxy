# tgproxy — 本地 Telegram 代理管理工具（桌面版）

自动抓取免费代理列表，本地测试连通性与延迟，仅筛选适用于 Telegram 的代理，并通过简洁的本地 Web 界面展示。**纯 Python 标准库实现，零第三方依赖。**

## 功能

- **多源定时抓取与自动更新**：并发抓取 5 个经统一体测精选的免费源（hideip.me / proxyscrape / monosans / TheSpeedX / watchttvv，低产池已剔除），按 host:port 去重合并；raw.githubusercontent.com 直连失败自动回退 jsDelivr 镜像（cdn / gcore / fastly）；默认每 10 分钟一轮，间隔 1~1440 分钟可配置，保存即生效并持久化；单源 >1000 条、合并 >3000 条时随机抽样

- **两阶段实测与自动剔除**：第一阶段 TCP 连通性测试（2 秒超时、300 并发）剔除死节点；第二阶段 **TG 实测（两关串行）**——经 SOCKS5 代理 CONNECT Telegram 数据中心（149.154.167.51:443，客户端实际收发消息的线路）+ 全链路访问 `desktop.telegram.org`（TLS 证书校验 + HTTP，验证无劫持），两关都通过才保留
- **延迟门槛**：TG 延迟 = 经代理到 Telegram 数据中心的往返（与客户端实际线路一致）；默认只保留 **<1000ms** 的节点（界面可调 500~60000ms），不达标直接剔除——列表里全是快且能用的节点
- **按实测延迟排序**：列表按 TG 线路往返延迟升序；分档着色 <1000ms 绿 / 1000~3000ms 橙 / 更高红
- **一键唤起 Telegram Desktop**：列表内点「添加到 Telegram」直接唤起本机 Telegram Desktop 弹出添加代理窗口，全程不跳网页。深链格式：SOCKS5 用 `tg://socks?server=&port=`（`tg://proxy` 缺 secret 会被 Telegram 忽略），MTProto 统一收敛为 `tg://` 前缀；接口为 `POST /api/open_telegram`，带同源校验，仅允许唤起当前可用列表中的代理
- **Telegram 适配筛选**：仅保留 SOCKS5 与 MTProto（`t.me/proxy`、`tg://proxy`）链接；排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等 Telegram 不可用类型（计入「已排除」统计）
- **爬虫模式**：需要爬网页的源也能用——按行解析几乎无产出时，自动走通用抽取（HTML / JSON / 任意文本中的 ip:port 对，支持 geonode 等 JSON API 与「ip:port|国家」格式），误报由 TG 实测过滤
- **源自动体检与换源**：逐源统计每轮 TG 验证产出（`data/source_health.json`）；连续 3 轮抓取失败、或连续 3 轮 0 个 TG 可用节点的源自动停用，并从内置候选池按顺序自动补位，变更写回 `data/config.json`，界面有提示
- **体检的两道保护**：①**分级比例**——≥5 个源时 0 产出占比 ≥80%、3~4 个源时至多 1 个源有产出、≤2 个源时仅全部为 0，即判为环境异常，本轮不累计零产出、不停用、不补位（≤2 个源时无法区分「源变垃圾」与「环境异常」，单源长期 0 产出会恒判环境异常而不换源）；②**宽恕路径**——界面不提供源编辑，编辑 `data/config.json` 的 `proxy_list_urls` 加回被停用的源后**重启**即视为「重新观察」，清除其停用标记与历史
- **known_good 沉淀**：本轮验证通过的节点写入 `data/known_good.json`，下轮优先复测并保留，避免随机抽样把稳定节点丢掉；可用设置区「自动保存可用节点（known_good）」开关关闭（关闭后文件保留但不再读取/写入，重新开启即恢复使用）
- **连败拉黑（v1.9.0，与安卓 v2.1.0 对齐）**：TCP 预筛不通或 TG 两关未过的节点连败 +1（两关通过即清零，含超门槛——通只是慢）；连败 3 轮自动拉黑 48 小时，期间直接跳过测试（IP 池里几次都不通的节点不再浪费时间）；48 小时后自动解封重测（免费池 IP 会轮换/复活）；拉黑表持久化 `data/known_bad.json`，超 10000 条按最久未失败淘汰，界面统计区显示「连败拉黑（跳过测试）」
- **健壮解析**：支持 `scheme://host:port 元数据` 与裸 `host:port` 两种行格式（后者按 SOCKS5 处理）；严格 IPv4 校验（4 段纯数字 0~255，含 `X` 掩码字符的地址直接丢弃）、host:port 去重、注释/空行跳过；提取入库时间、地区、ISP 等元数据
- **轮次记录（2026-10-28，与安卓端同一份契约）**：每轮刷新落一行 JSON 到 `data/rounds.jsonl`（时间、成败、耗时、八项统计、两关漏斗、五段耗时），最多保留 50 条；失败轮也记，坏行只丢一行不影响其余；右栏「运行日志」显示最近 20 轮。注意日志里的 `tg_failed` 是**两关未过、不含超门槛**，与 `/api/proxies` 的同名字段口径不同（后者含超门槛，历史口径未改；两者的差就是 `over_threshold`）
- **界面**：六项统计（可用 / 未验证（MTProto）/ TCP 通但 TG 不合格（含超慢）/ 不通已剔除 / 已排除 / 连败拉黑（v1.9.0）；除「已排除」「连败拉黑」外四项之和 = 本轮测试总数）、代理列表按 TG 延迟升序、延迟三档着色（<1s 绿 / <3s 橙 / 更高红）、一键复制完整代理链接、TG 延迟上限设置、手动刷新按钮、下次自动更新倒计时、数据更新时间、抓取失败错误提示条（失败时保留旧列表）
- **配置持久化**：刷新间隔、数据源地址等保存于 `data/config.json`（首次运行自动生成，已加入 .gitignore）

## 运行

```bash
python app.py
```

启动后自动打开 `http://127.0.0.1:8765/`（端口被占用时自动顺延至 8766、8767…），终端打印实际地址。按 `Ctrl+C` 退出。

## 文件说明

| 文件 | 职责 |
|---|---|
| `app.py` | 入口：本地 HTTP 服务、JSON API（/api/proxies、/api/refresh、/api/settings）、后台定时调度、配置读写、端口探测 |
| `proxy_manager.py` | 抓取、解析、Telegram 筛选、并发 TCP 测试、排序、状态管理 |
| `static/index.html` | Web 界面（原生 HTML/CSS/JS 单页，零外链 CDN，离线可用） |

运行时生成的文件全部在**程序目录下的 `data/`**（v2.2.1 起；旧版本平铺在程序目录里，
首次启动会自动搬进去）：

| 文件 | 内容 |
|---|---|
| `data/config.json` | 刷新间隔、数据源地址、门槛等配置 |
| `data/known_good.json` | 上轮验证通过的节点（known_good 沉淀） |
| `data/known_bad.json` | 连败拉黑表 |
| `data/source_health.json` | 逐源体检记录 |
| `data/rounds.jsonl` | 每轮一行 JSON 的轮次记录（最多 50 条） |
| `data/tgproxy.log` | 运行日志（windowed 打包后这是唯一的诊断入口） |

想搬走或备份，**整个程序目录拷走即可** —— 状态全在 `data/` 里，没有外部依赖。
（浏览器窗口的 profile 例外，它在 `%LOCALAPPDATA%\tgproxy`，删掉会自动重建。）

## 安卓版

本仓库 `tgproxy-android/` 目录为本项目的安卓原生版本（Kotlin + Jetpack Compose），核心功能与本版本一致，详见该目录下的 README。
