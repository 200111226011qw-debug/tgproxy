# tgproxy — 本地 Telegram 代理管理工具（桌面版）

自动抓取免费代理列表，本地测试连通性与延迟，仅筛选适用于 Telegram 的代理，并通过简洁的本地 Web 界面展示。**纯 Python 标准库实现，零第三方依赖。**

## 功能

- **多源定时抓取与自动更新**：并发抓取 5 个经统一体测精选的免费源（hideip.me / proxyscrape / monosans / TheSpeedX / watchttvv，低产池已剔除），按 host:port 去重合并；raw.githubusercontent.com 直连失败自动回退 jsDelivr 镜像（cdn / gcore / fastly）；默认每 10 分钟一轮，间隔 1~1440 分钟可配置，保存即生效并持久化；单源 >1000 条、合并 >3000 条时随机抽样
- **爬虫模式**：需要爬网页的源也能用——按行解析几乎无产出时自动走通用抽取（HTML / JSON / 任意文本中的 ip:port 对，支持 geonode 等 JSON API），误报由 TG 实测过滤
- **两阶段实测与自动剔除**：第一阶段 TCP 连通性测试（5 秒超时、150 并发）剔除死节点；第二阶段 **TG 实测（两关并行）**——经 SOCKS5 代理 CONNECT Telegram 数据中心（149.154.167.51:443，客户端实际收发消息的线路）+ 全链路访问 `desktop.telegram.org`（TLS 证书校验 + HTTP，验证无劫持），两关都通过才保留
- **延迟门槛**：TG 延迟 = 经代理到 Telegram 数据中心的往返（与客户端实际线路一致）；默认只保留 **<1000ms** 的节点（界面可调 500~60000ms），不达标直接剔除——列表里全是快且能用的节点
- **按实测延迟排序**：列表按 TG 线路往返延迟升序；分档着色 <1000ms 绿 / 1000~3000ms 橙 / 更高红
- **一键唤起 Telegram Desktop**：列表内点「添加到 Telegram」直接唤起本机 Telegram Desktop 弹出添加代理窗口，全程不跳网页。深链格式：SOCKS5 用 `tg://socks?server=&port=`（`tg://proxy` 缺 secret 会被 Telegram 忽略），MTProto 统一收敛为 `tg://` 前缀；接口为 `POST /api/open_telegram`，带同源校验，仅允许唤起当前可用列表中的代理
- **Telegram 适配筛选**：仅保留 SOCKS5 与 MTProto（`t.me/proxy`、`tg://proxy`）链接；排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等 Telegram 不可用类型（计入「已排除」统计）
- **爬虫模式**：需要爬网页的源也能用——按行解析几乎无产出时，自动走通用抽取（HTML / JSON / 任意文本中的 ip:port 对，支持 geonode 等 JSON API 与「ip:port|国家」格式），误报由 TG 实测过滤
- **known_good 沉淀**：本轮验证通过的节点写入 `known_good.json`，下轮优先复测并保留，避免随机抽样把稳定节点丢掉
- **健壮解析**：支持 `scheme://host:port 元数据` 与裸 `host:port` 两种行格式（后者按 SOCKS5 处理）；严格 IPv4 校验（4 段纯数字 0~255，含 `X` 掩码字符的地址直接丢弃）、host:port 去重、注释/空行跳过；提取入库时间、地区、ISP 等元数据
- **界面**：四项统计（可用 / TCP 通但 TG 不合格 / 不通已剔除 / 已排除）、代理列表按 TG 延迟升序、延迟三档着色（<1s 绿 / <3s 橙 / 更高红）、一键复制完整代理链接、TG 延迟上限设置、手动刷新按钮、下次自动更新倒计时、数据更新时间、抓取失败错误提示条（失败时保留旧列表）
- **配置持久化**：刷新间隔、数据源地址等保存于 `config.json`（首次运行自动生成，已加入 .gitignore）

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

## 安卓版

本仓库 `tgproxy-android/` 目录为本项目的安卓原生版本（Kotlin + Jetpack Compose），核心功能与本版本一致，详见该目录下的 README。
