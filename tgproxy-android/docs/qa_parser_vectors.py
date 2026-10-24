# -*- coding: utf-8 -*-
"""QA 对拍向量表：Python 桌面版 parse_proxy_text 期望输出。
用于与 Kotlin ProxyParser 逐行代码追踪结果比对（三元组 + 每 entry 字段）。
"""
import json
import os
import sys

# 以本文件为基准定位仓库内的 tgproxy/（桌面版源码目录），换机器/换目录均可运行
sys.path.insert(
    0,
    os.path.abspath(
        os.path.join(os.path.dirname(__file__), os.pardir, os.pardir, "tgproxy")
    ),
)
from proxy_manager import parse_proxy_text  # noqa: E402

VECTORS = {}

# V1 真实样本（模拟数据源格式：scheme://host:port + 元数据）
VECTORS["V1_realistic_sample"] = (
    "socks5://77.51.100.200:1080 入库时间：10-21 22:58 [热门] 荷兰 北荷兰省 阿姆斯特丹 — DigitalOcean\n"
    "socks5://77.51.101.201:1080 入库时间:10-22 09:05 日本 东京 — IIJ\n"
    "socks5://103.102.44.55:4145\n"
    "# 注释行\n"
    "\n"
    "   \n"
)

# V2 掩码 IP（77.51.X.128 与段内 X）
VECTORS["V2_masked_ip"] = (
    "socks5://77.51.X.128:1080\n"
    "socks5://77.51.100.X:1080\n"
    "socks5://X.2.3.4:1080\n"
)

# V3 无端口行（socks5 与非 socks5 各一）
VECTORS["V3_no_port"] = (
    "socks5://1.2.3.4\n"
    "http://1.2.3.4\n"
    "socks5://1.2.3.4:  \n"
)

# V4 非法端口 0 / 65536 / abc / 123456（6 位）/ 负数
VECTORS["V4_bad_ports"] = (
    "socks5://1.2.3.4:0\n"
    "socks5://1.2.3.4:65536\n"
    "socks5://1.2.3.4:abc\n"
    "socks5://1.2.3.4:123456\n"
    "socks5://1.2.3.4:-1\n"
    "socks5://1.2.3.4:65535\n"
)

# V5 前导零 IP（1.2.3.04）——解析器应保留（段值 <=255）
VECTORS["V5_leading_zero_ip"] = (
    "socks5://1.2.3.04:1080\n"
    "socks5://01.2.3.4:1080\n"
    "socks5://1.2.3.256:1080\n"
    "socks5://1.2.3.999:1080\n"
)

# V6 注释 / 空行 / 无 scheme 行
VECTORS["V6_comments_no_scheme"] = (
    "# full line comment\n"
    "1.2.3.4:1080\n"
    "socks5:1.2.3.4:1080\n"
    "://1.2.3.4:1080\n"
    "socks5://\n"
    "\t\n"
)

# V7 重复 host:port：同类型 + 跨类型（socks5 与 mtproto 共享 seen）
VECTORS["V7_dup_hostport"] = (
    "socks5://1.2.3.4:1080\n"
    "socks5://1.2.3.4:1080\n"
    "tg://proxy?server=1.2.3.4&port=1080&secret=abc\n"
    "tg://proxy?server=9.9.9.9&port=1080&secret=abc\n"
    "socks5://9.9.9.9:1080\n"
    "http://1.2.3.4:1080\n"
)

# V8 MTProto 四种合法形态 + 伪造主机
VECTORS["V8_mtproto_forms"] = (
    "tg://proxy?server=10.0.0.1&port=443&secret=aa\n"
    "https://t.me/proxy?server=10.0.0.2&port=443&secret=bb\n"
    "http://t.me/proxy?server=10.0.0.3&port=443&secret=cc\n"
    "https://www.t.me/proxy?server=10.0.0.4&port=443&secret=dd\n"
    "https://example.com/t.me/proxy?server=10.0.0.5&port=443&secret=ee\n"
    "https://t.me.evil.com/proxy?server=10.0.0.6&port=443&secret=ff\n"
    "https://t.me/joinchat/abc\n"
    "https://t.me/proxy\n"
    "http://not.t.me/proxy?server=10.0.0.7&port=443&secret=gg\n"
)

# V9 大小写混合
VECTORS["V9_case_mixed"] = (
    "HTTPS://T.ME/PROXY?server=10.0.0.1&port=443&secret=aa\n"
    "TG://PROXY?server=10.0.0.2&port=443&secret=bb\n"
    "SOCKS5://10.0.0.3:1080\n"
)

# V10 元数据五种 em-dash 分隔形态 + 全角冒号 + [标签]
VECTORS["V10_meta_separators"] = (
    "socks5://1.1.1.1:1080 地区A — ISP-A\n"
    "socks5://2.2.2.2:1080 地区B —ISP-B\n"
    "socks5://3.3.3.3:1080 地区C— ISP-C\n"
    "socks5://4.4.4.4:1080 地区D—ISP-D\n"
    "socks5://5.5.5.5:1080 地区E - ISP-E\n"
    "socks5://6.6.6.6:1080 入库时间：01-02 03:04 [标签一] [标签二] 地区F — ISP-F 额外尾巴\n"
    "socks5://7.7.7.7:1080 只有地区没有ISP\n"
)

# V11 空文本与 null 文本
VECTORS["V11_empty_and_null"] = ""
VECTORS["V11b_null"] = None

# V12 非 Telegram 类型 excluded 去重 + 非法行 dropped
VECTORS["V12_excluded_dedup"] = (
    "http://1.1.1.1:8080\n"
    "https://1.1.1.1:8080\n"
    "ss://2.2.2.2:8388\n"
    "vmess://3.3.3.3:10086\n"
    "vless://4.4.4.4:443\n"
    "trojan://5.5.5.5:443\n"
    "http://example.com:8080\n"
    "https://1.2.3.4\n"
    "ss://\n"
)

# V13 混合大样例（对齐工程师 ProxyParserTest 的向量，独立构造）
VECTORS["V13_mixed_big"] = (
    "# 代理列表\n"
    "socks5://10.0.0.1:1080 入库时间：10-21 22:58 [新] 香港 — HGC\n"
    "socks5://10.0.0.2:1080 日本 — IIJ\n"
    "socks5://10.0.0.3:1080\n"
    "tg://proxy?server=10.0.0.4&port=443&secret=dd1234567890\n"
    "https://t.me/proxy?server=10.0.0.5&port=443&secret=ee1234567890\n"
    "http://11.0.0.1:8080\n"
    "https://11.0.0.1:8080\n"
    "ss://11.0.0.2:8388\n"
    "vmess://11.0.0.3:10086\n"
    "vless://11.0.0.4:443\n"
    "trojan://11.0.0.5:443\n"
    "socks5://12.0.0.X:1080\n"
    "socks5://12.0.0.9:0\n"
    "socks5://12.0.0.10:abc\n"
    "tg://proxy?server=12.0.0.11&port=443\n"
    "tg://proxy?server=12.0.0.12&port=443&secret=\n"
    "tg://proxy?server=13.0.0.1&port=443&secret=ff\n"
    "1.2.3.4:1080\n"
    "http://t.me/notproxy\n"
    "\n"
    "socks5://10.0.0.1:1080\n"
)

# V14 query 细节：URL 编码、+ 号、重复键、空值、无 = 、# fragment
VECTORS["V14_query_details"] = (
    "tg://proxy?server=10.0.0.1&port=443&secret=a%20b\n"
    "tg://proxy?server=10.0.0.2&port=443&secret=hello+world\n"
    "tg://proxy?server=10.0.0.3&server=10.9.9.9&port=443&secret=x\n"
    "tg://proxy?server=10.0.0.4&port=443&secret=x#frag\n"
    "tg://proxy?server&port=443&secret=x\n"
    "tg://proxy?port=443&secret=x\n"
)

# V15 行首尾空白 + 行内多空白 + tab 分隔
VECTORS["V15_whitespace"] = (
    "   socks5://1.2.3.4:1080   \n"
    "\tsocks5://2.3.4.5:1080\t地区\t—\tISP\n"
    "socks5://3.4.5.6:1080    入库时间：  10-21   22:58   地区 — ISP\n"
)

results = {}
for name, text in VECTORS.items():
    entries, excluded, dropped = parse_proxy_text(text)
    results[name] = {
        "entries": [
            {
                "link": e["link"],
                "host": e["host"],
                "port": e["port"],
                "type": e["type"],
                "location": e["location"],
                "isp": e["isp"],
                "added_at": e["added_at"],
            }
            for e in entries
        ],
        "excluded": excluded,
        "dropped": dropped,
    }

print(json.dumps(results, ensure_ascii=False, indent=1))
