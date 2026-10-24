# tgproxy — 本地 Telegram 代理管理工具（桌面版）

自动抓取免费代理列表，本地测试连通性与延迟，仅筛选适用于 Telegram 的代理，并通过简洁的本地 Web 界面展示。**纯 Python 标准库实现，零第三方依赖。**

## 功能

- **多源定时抓取与自动更新**：并发抓取 6 个内置免费源（monosans / TheSpeedX / hookzof / zevtyardt / proxifly / watchttvv），按 host:port 去重合并；raw.githubusercontent.com 直连失败自动回退 jsDelivr 镜像（cdn / gcore / fastly）；默认每 10 分钟一轮，间隔 1~1440 分钟可配置，保存即生效并持久化；单源 >1000 条、合并 >3000 条时随机抽样
- **连通性与延迟测试**：对每个保留代理做 TCP 连接测试（5 秒超时、20 并发），成功记录延迟毫秒数，超时/拒绝判定不可用并过滤
- **Telegram 适配筛选**：仅保留 SOCKS5 与 MTProto（`t.me/proxy`、`tg://proxy`）链接；排除 HTTP、HTTPS、SS、VMess、VLESS、Trojan 等 Telegram 不可用类型（计入「已排除」统计）
- **健壮解析**：支持 `scheme://host:port 元数据` 与裸 `host:port` 两种行格式（后者按 SOCKS5 处理）；严格 IPv4 校验（4 段纯数字 0~255，含 `X` 掩码字符的地址直接丢弃）、host:port 去重、注释/空行跳过；提取入库时间、地区、ISP 等元数据
- **界面**：四项统计（已保留/可用/不可用/已排除）、代理列表按延迟升序、延迟三档着色（<200ms 绿 / <600ms 橙 / 更慢或失败 红灰）、一键复制完整代理链接、手动刷新按钮、下次自动更新倒计时、数据更新时间、抓取失败错误提示条（失败时保留旧列表）
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
