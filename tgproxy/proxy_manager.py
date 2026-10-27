"""代理列表抓取、解析、Telegram 适配筛选与连通性测试（仅 Python 标准库）。"""

import json
import logging
import os
import random
import re
import socket
import ssl
import struct
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from urllib.parse import parse_qs, urlparse

logger = logging.getLogger("tgproxy.proxy_manager")


def _atomic_write_json(path, payload, **dump_kwargs):
    """先写同目录临时文件再 os.replace，避免中断留下截断的半个 JSON。

    known_good / known_bad / source_health 是「取消」或「强制退出」时唯一
    可能被截断的落盘点；一旦截断，对应的 _load_* 会静默返回空表，沉淀与
    拉黑表无声清零，而界面上看不出任何异常。
    """
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, **dump_kwargs)
        os.replace(tmp, path)
    except OSError:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass

# 默认数据源（2026-10-25 统一标准体检：抓取 -> TCP -> TG 两关实测，按产出质量精选；
# raw.githubusercontent.com 失败时自动回退 jsDelivr 镜像）
DEFAULT_PROXY_LIST_URLS = [
    # hideip.me 人工筛选池：TG 通过率最高（存活节点约 45% 可用）
    "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt",
    # proxyscrape 实时验证接口：通过率约 24%，且不走 GitHub（多一重可用性保障）
    "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000&country=all",
    # monosans 每 30 分钟验证：通过率约 25%
    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
    # TheSpeedX 每 6 小时更新：通过率约 7%，用于补充数量多样性
    "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
    # 原始源：SOCKS5 + MTProto，带入库时间/地区/ISP 元数据
    "https://raw.githubusercontent.com/watchttvv/free-proxy-list/refs/heads/main/proxy.txt",
]
USER_AGENT = "tgproxy/1.0 (local Telegram proxy manager)"


def _env_float(name, default):
    """读取数值型环境变量做实验性覆盖；非法值回落默认并告警。

    P1-A 的超时 A/B 需要在不重建 exe 的前提下改数值。默认值仍是模块常量，
    环境变量只是「实验覆盖」，不影响正常启动路径。
    """
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    try:
        value = float(raw)
    except (TypeError, ValueError):
        logger.warning("环境变量 %s=%r 不是数字，忽略，用默认 %s", name, raw, default)
        return default
    if value <= 0:
        logger.warning("环境变量 %s=%r 必须为正数，忽略，用默认 %s", name, raw, default)
        return default
    return value


# 超时值支持环境变量覆盖（实验用，见 _env_float）：
#   TGPROXY_FETCH_TIMEOUT / TGPROXY_TCP_TIMEOUT / TGPROXY_TG_TIMEOUT / TGPROXY_TG_DEADLINE
FETCH_TIMEOUT_SEC = _env_float("TGPROXY_FETCH_TIMEOUT", 15)
FETCH_MAX_SOURCES = 20  # 与 app.py 的 MAX_PROXY_LIST_URLS 对齐，用户配的源全部参与抓取与体检
TEST_TIMEOUT_SEC = _env_float("TGPROXY_TCP_TIMEOUT", 2.0)
# TCP 预筛超时的派生规则（见 tcp_timeout_for）：门槛 + 余量，且不低于 TEST_TIMEOUT_SEC。
# TEST_TIMEOUT_SEC 是**下限**，调低它不会突破「预筛 ≥ 门槛」的约束——这正是耦合要保证的。
TCP_TIMEOUT_MARGIN_SEC = 1.0
# 派生结果超过这个值就告警：说明用户把门槛调得很高，本轮耗时会明显变长
TCP_TIMEOUT_WARN_SEC = 5.0
# 免费列表动辄数万条，全量 TCP 测试耗时过久；抽样上限让单轮刷新控制在分钟级
MAX_PER_SOURCE = 1000
MAX_TOTAL = 3000
# 实时进度：每完成这么多个节点上报一次，降低锁开销
PROGRESS_STEP = 20

# TCP 预筛并发。提高并发能直接压缩 TCP 阶段，但受本地端口/句柄上限约束，
# 且对免费代理站点是成倍的瞬时压力 —— 用环境变量做 A/B，不要直接改死。
# 150 → 300 实测：TCP 每节点 10.0 → 5.2 ms，dropped 占比反而从 62% 降到 56%，
# 本地 1200 并发 socket 零失败。若日后发现被代理站点限流（dropped 暴增），
# 用 TGPROXY_TCP_WORKERS 回退，不必重新打包。
TEST_MAX_WORKERS = int(_env_float("TGPROXY_TCP_WORKERS", 300))
# TG 实测：通过 SOCKS5 代理真实访问 Telegram，验证节点能否连通 Telegram
# 1) Telegram 数据中心（客户端收发消息实际走的线路）  2) Telegram 站点全链路（TLS+HTTP）
TELEGRAM_DC_HOST = "149.154.167.51"
TELEGRAM_DC_PORT = 443
TELEGRAM_TEST_HOST = "desktop.telegram.org"
TELEGRAM_TEST_PORT = 443
# 第二关（TLS + HTTP 访问 desktop.telegram.org）的单节点预算。
# 与延迟门槛无关：实测 web_p50 约 2~3s、p90 到 8s，成本由 TLS/HTTP 决定。
TG_TEST_TIMEOUT_SEC = _env_float("TGPROXY_TG_TIMEOUT", 8.0)
# 第一关（CONNECT Telegram DC）预算从延迟门槛派生，见 tg_dc_timeout_for
TG_DC_TIMEOUT_BASE_SEC = _env_float("TGPROXY_TG_DC_TIMEOUT", 2.0)
TG_DC_TIMEOUT_MARGIN_SEC = 1.0
TG_TEST_MAX_WORKERS = 100
# 单节点 TG 实测总预算（秒）的**下限**；实际值按两关预算动态扩展。
# 固定 20s 在高门槛下会与 dc 预算冲突（门槛 60s → dc 61s，还没测完就被砍）。
TG_TEST_DEADLINE_SEC = _env_float("TGPROXY_TG_DEADLINE", 20.0)
# 候选源池：活跃源「变垃圾」被停用后按顺序自动补位（含爬虫源，由 crawl_extract 处理）
SOURCE_CANDIDATES = [
    "https://proxyspace.pro/socks5.txt",
    "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
    "https://proxylist.geonode.com/api/proxy-list?protocols=socks5&limit=500&page=1&sort_by=latency&sort_type=asc",
    "https://www.socks-proxy.net/",
    "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt",
    "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
    "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
    "https://raw.githubusercontent.com/zevtyardt/proxy-list/main/socks5.txt",
    "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
    "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt",
]
# 源体检：连续 N 轮抓取失败，或最近 N 个有产出的轮次全部 0 个 TG 可用节点 -> 停用并补位
SOURCE_PARK_FAILS = 3
SOURCE_PARK_ZERO_ROUNDS = 3
SOURCE_HIST_KEEP = 5
# 环境异常判定：成功抓取的源中 0 产出占比 >= 80% 时，判为 Telegram 封锁/门槛调严
# （而非「源变垃圾」），本轮不累计零产出、不停用、不补位
SOURCE_GLOBAL_ZERO_RATIO = 0.8
# 全局抽样时每个源的保底条数：防小源被随机抽样整源抽空，被体检误读为「0 产出」
SOURCE_SAMPLE_FLOOR = 10
# TG 实测延迟门槛：超过该值视为「太慢不可用」剔除（默认 1s；界面可调 500~60000）
TG_LATENCY_LIMIT_MS = 1000
# 连续失败拉黑（v1.9.0，与安卓 v2.1.0 对齐）：连败达到阈值的节点后续轮次跳过测试，
# 超过保质期自动解封重测（免费池 IP 会轮换/复活）；条目超量按最久未失败淘汰
KNOWN_BAD_STREAK_LIMIT = 3
KNOWN_BAD_EXPIRY_SEC = 48 * 3600
KNOWN_BAD_MAX_ENTRIES = 10000
# raw.githubusercontent.com 直连失败时依次尝试的镜像（按国内可达性排序）
MIRROR_HOSTS = ("cdn.jsdelivr.net", "gcore.jsdelivr.net", "fastly.jsdelivr.net")
_RAW_URL_RE = re.compile(
    r"^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$"
)

# 数据行结构：scheme://host:port <空白> 元数据（入库时间、标签、地区、ISP）
_LINE_RE = re.compile(
    r"^(?P<scheme>[A-Za-z][A-Za-z0-9+.\-]*)://(?P<rest>\S+)\s*(?P<meta>.*)$"
)
# 裸地址行：host:port <空白> 元数据（TheSpeedX / monosans 等列表无 scheme，视为 SOCKS5）
_BARE_RE = re.compile(r"^(?P<host>[0-9.]+):(?P<port>[0-9]{1,5})\s*(?P<meta>.*)$")
# 爬虫抽取：任意 HTML / JSON / 文本页面中的 ip:port 对（ip 与端口间允许标签、引号、空白等）
_CRAWL_PAIR_RE = re.compile(
    r"(?P<ip>(?:[0-9]{1,3}\.){3}[0-9]{1,3})[^0-9]{1,20}?(?P<port>[0-9]{2,5})"
)
_ADDED_AT_RE = re.compile(r"入库时间[：:]\s*(\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2})")
_TAG_RE = re.compile(r"\[[^\]]*\]")
_IPV4_SEGMENT_RE = re.compile(r"^[0-9]{1,3}$")
_PORT_RE = re.compile(r"^[0-9]{1,5}$")


def parse_ipv4(text):
    """严格校验 IPv4（4 段、纯数字、每段 0-255），不合法返回 None。"""
    if not isinstance(text, str):
        return None
    parts = text.split(".")
    if len(parts) != 4:
        return None
    for part in parts:
        if not _IPV4_SEGMENT_RE.match(part) or int(part) > 255:
            return None
    return text


def parse_port(text):
    """校验端口号（1~65535），不合法返回 None。"""
    if not isinstance(text, str) or not _PORT_RE.match(text):
        return None
    port = int(text)
    return port if 1 <= port <= 65535 else None


def _split_host_port(authority):
    """从 authority 段切出 host 与端口文本，失败返回 (None, None)。"""
    host, sep, port_text = authority.rpartition(":")
    if not sep:
        return None, None
    return host.strip(), port_text.strip()


def _parse_meta(meta_text):
    """解析行尾元数据：入库时间 / 地区 / ISP（剔除 [标签]）。"""
    meta = meta_text or ""
    added_at = ""
    match = _ADDED_AT_RE.search(meta)
    if match:
        added_at = " ".join(match.group(1).split())
        meta = meta[: match.start()] + " " + meta[match.end():]
    meta = _TAG_RE.sub(" ", meta)
    # hideip.me 等列表的「|国家」后缀：把国家作为地区元数据
    if "|" in meta:
        return {
            "added_at": added_at,
            "location": " ".join(meta.replace("|", " ").split()),
            "isp": "",
        }
    location, isp = meta, ""
    for sep in (" — ", " —", "— ", "—", " - "):
        if sep in meta:
            location, isp = meta.split(sep, 1)
            break
    return {
        "added_at": added_at,
        "location": " ".join(location.split()),
        "isp": " ".join(isp.split()),
    }


def _is_mtproto_url(scheme, rest):
    """识别 MTProto 分享链接：tg://proxy?... 或主机为 t.me 的 http(s)://t.me/proxy?...。"""
    lowered = rest.lower()
    if scheme == "tg":
        return lowered.startswith("proxy")
    if scheme in ("http", "https"):
        authority = rest.split("/", 1)[0].lower()
        path = lowered.split("/", 1)[1] if "/" in rest else ""
        return authority in ("t.me", "www.t.me") and path.startswith("proxy")
    return False


def _parse_mtproto(scheme, rest):
    """从 MTProto 链接提取 server/port/secret，缺失或非法返回 None。"""
    try:
        parsed = urlparse(scheme + "://" + rest)
        params = parse_qs(parsed.query)
    except ValueError:
        return None
    server = (params.get("server") or [""])[0].strip()
    port_text = (params.get("port") or [""])[0].strip()
    secret = (params.get("secret") or [""])[0].strip()
    host = parse_ipv4(server)
    port = parse_port(port_text)
    if not host or not port or not secret:
        return None
    return {"link": "%s://%s" % (scheme, rest), "host": host, "port": port, "type": "mtproto"}


def _crawl_from_json(node, entries, seen):
    """递归遍历 JSON 结构，收集带 ip/host + port 字段的对象（geonode 等 API）。"""
    if isinstance(node, list):
        for item in node:
            _crawl_from_json(item, entries, seen)
    elif isinstance(node, dict):
        host = node.get("ip") or node.get("host")
        port = node.get("port")
        host = parse_ipv4(str(host)) if host else None
        try:
            port = int(str(port))
        except (TypeError, ValueError):
            port = None
        if host and port and 1 <= port <= 65535:
            key = (host, port)
            if key not in seen:
                seen.add(key)
                entries.append({
                    "link": "socks5://%s:%d" % (host, port),
                    "host": host,
                    "port": port,
                    "type": "socks5",
                    "latency_ms": None,
                    "status": "unavailable",
                    "location": str(node.get("country") or ""),
                    "isp": str(node.get("isp") or ""),
                    "added_at": "",
                })
        for value in node.values():
            if isinstance(value, (list, dict)):
                _crawl_from_json(value, entries, seen)


def crawl_extract(text):
    """爬虫抽取：从任意 HTML / JSON / 文本中提取 ip:port 对，视为 SOCKS5。

    供需要爬网页面的数据源使用（如 socks-proxy.net 等表格站、geonode 等 JSON API）；
    抽取结果经严格 IPv4/端口校验并去重，配合 TG 实测过滤误报。
    """
    entries = []
    seen = set()
    stripped = (text or "").lstrip()
    if stripped[:1] in ("{", "["):
        try:
            _crawl_from_json(json.loads(stripped), entries, seen)
        except (ValueError, RecursionError):  # 非法 JSON 或深层嵌套递归爆栈
            pass
        if entries:
            return entries
    for match in _CRAWL_PAIR_RE.finditer(text or ""):
        host = parse_ipv4(match.group("ip"))
        port = parse_port(match.group("port"))
        if not host or not port:
            continue
        key = (host, port)
        if key in seen:
            continue
        seen.add(key)
        entries.append({
            "link": "socks5://%s:%d" % (host, port),
            "host": host,
            "port": port,
            "type": "socks5",
            "latency_ms": None,
            "status": "unavailable",
            "location": "",
            "isp": "",
            "added_at": "",
        })
    return entries


def parse_proxy_text(text):
    """解析代理列表文本。

    返回 (entries, excluded_count, dropped_count)：
    - entries: Telegram 可用代理（SOCKS5 / MTProto），按 host:port 去重；
    - excluded_count: 非 Telegram 类型且地址合法的去重条目数；
    - dropped_count: 地址非法（如含掩码 X）或无法解析的行数。
    """
    entries = []
    seen_kept = set()
    excluded_seen = set()
    dropped_count = 0
    for raw_line in (text or "").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = _LINE_RE.match(line)
        if match:
            scheme = match.group("scheme").lower()
            rest = match.group("rest")
            meta = _parse_meta(match.group("meta"))
        else:
            # 无 scheme 的裸 host:port 行（主流免费列表格式）按 SOCKS5 处理
            bare = _BARE_RE.match(line)
            if not bare:
                dropped_count += 1
                continue
            scheme = "socks5"
            rest = "%s:%s" % (bare.group("host"), bare.group("port"))
            meta = _parse_meta(bare.group("meta"))

        if _is_mtproto_url(scheme, rest):
            parsed = _parse_mtproto(scheme, rest)
            if parsed is None:
                dropped_count += 1
                continue
            key = (parsed["host"], parsed["port"])
            if key in seen_kept:
                continue
            seen_kept.add(key)
            parsed.update({
                "latency_ms": None,
                "status": "unavailable",
                "location": meta["location"],
                "isp": meta["isp"],
                "added_at": meta["added_at"],
            })
            entries.append(parsed)
            continue

        authority = rest.split("/", 1)[0]
        host, port_text = _split_host_port(authority)
        host_ok = parse_ipv4(host) if host else None
        port = parse_port(port_text) if port_text else None

        if scheme != "socks5":
            # Telegram 不可用类型（http/https/ss/vmess/vless/trojan 等）计入已排除
            if host_ok and port:
                key = (host_ok, port)
                if key not in excluded_seen:
                    excluded_seen.add(key)
            else:
                dropped_count += 1
            continue

        if not host_ok or not port:
            # 含掩码字符 X 等非法 IPv4，直接丢弃
            dropped_count += 1
            continue
        key = (host_ok, port)
        if key in seen_kept:
            continue
        seen_kept.add(key)
        entries.append({
            "link": "socks5://%s:%d" % (host_ok, port),
            "host": host_ok,
            "port": port,
            "type": "socks5",
            "latency_ms": None,
            "status": "unavailable",
            "location": meta["location"],
            "isp": meta["isp"],
            "added_at": meta["added_at"],
        })
    return entries, len(excluded_seen), dropped_count


def mirror_urls(url):
    """为 raw.githubusercontent.com 地址生成 jsDelivr 镜像候选（按可达性排序）。"""
    match = _RAW_URL_RE.match(url)
    if not match:
        return []
    user, repo, branch, path = match.groups()
    return [
        "https://%s/gh/%s/%s@%s/%s" % (host, user, repo, branch, path)
        for host in MIRROR_HOSTS
    ]


def _http_get(url, timeout):
    """单次抓取（严格证书校验，不做免验证降级，防中间人投毒代理列表）。"""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def fetch_proxy_text(url, timeout=None):
    """抓取代理列表文本；直连失败时自动回退 jsDelivr 镜像。

    返回 (text, via_mirror)。
    """
    if timeout is None:
        timeout = FETCH_TIMEOUT_SEC
    last_error = None
    for candidate in [url] + mirror_urls(url):
        try:
            return _http_get(candidate, timeout), candidate != url
        except Exception as exc:
            last_error = exc
    raise last_error


def tcp_timeout_for(max_tg_latency_ms):
    """TCP 预筛超时从延迟门槛派生，保证「能过门槛的节点不会在预筛被冤杀」。

    依据：`test_socks5_telegram_dc` 里 `started` 在 `create_connection` 之前，
    所以 `tg_latency` **包含到代理的握手时间**。于是握手时间超过门槛的节点
    必然过不了门槛；预筛超时必须 ≥ 门槛 + 余量，否则「握手落在
    [门槛, 预筛超时) 之间」的节点会在预筛阶段被提前淘汰，门槛变得不可达。

    历史问题：预筛超时曾是独立常量（5.0s），而门槛上限是 60s——门槛设到
    6s 以上的用户一直在被静默误杀，只是没人量过。

    `TEST_TIMEOUT_SEC` 是下限（默认 2.0s，可用 TGPROXY_TCP_TIMEOUT 覆盖），
    调低它不会突破上述约束。
    """
    try:
        limit = float(max_tg_latency_ms)
    except (TypeError, ValueError):
        limit = float(TG_LATENCY_LIMIT_MS)
    if limit <= 0:
        limit = float(TG_LATENCY_LIMIT_MS)
    derived = limit / 1000.0 + TCP_TIMEOUT_MARGIN_SEC
    return max(TEST_TIMEOUT_SEC, derived)


def test_tcp_latency(host, port, timeout=None):
    """TCP 连接测试，返回耗时毫秒；超时/拒绝/错误返回 None。"""
    if timeout is None:
        timeout = TEST_TIMEOUT_SEC
    started = time.perf_counter()
    try:
        connection = socket.create_connection((host, port), timeout=timeout)
    except (OSError, ValueError):
        return None
    try:
        connection.close()
    except OSError:
        pass
    return int(round((time.perf_counter() - started) * 1000))


def _recv_exact(sock, count, deadline):
    """精确读取 count 字节；每次读取前按 deadline 收紧 socket 超时，
    连接提前关闭或超预算抛 OSError。"""
    data = b""
    while len(data) < count:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            raise OSError("deadline exceeded")
        sock.settimeout(remaining)
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise OSError("connection closed")
        data += chunk
    return data


def _socks5_connect(sock, target_host, target_port, deadline):
    """在已连接的套接字上完成 SOCKS5 无认证握手与 CONNECT，失败抛 OSError。

    域名目标用 ATYP=3 由代理端解析（避免本地 DNS 污染）；IP 目标用 ATYP=1。
    deadline 为 time.perf_counter() 基准的绝对时限，逐步骤收紧 socket 超时。
    """
    sock.sendall(b"\x05\x01\x00")
    if _recv_exact(sock, 2, deadline) != b"\x05\x00":
        raise OSError("socks5 handshake rejected")
    try:
        ip = socket.inet_aton(target_host)
        payload = b"\x05\x01\x00\x01" + ip + struct.pack(">H", target_port)
    except OSError:
        target = target_host.encode("idna")
        payload = (
            b"\x05\x01\x00\x03" + bytes([len(target)]) + target
            + struct.pack(">H", target_port)
        )
    sock.sendall(payload)
    reply = _recv_exact(sock, 4, deadline)
    if reply[1] != 0:  # reply code 非 0x00（如 0x01 通用失败 / 0x02 不允许）
        raise OSError("socks5 connect failed: %d" % reply[1])
    atyp = reply[3]
    if atyp == 1:
        _recv_exact(sock, 6, deadline)
    elif atyp == 3:
        one = _recv_exact(sock, 1, deadline)
        _recv_exact(sock, 1 + one[0] + 2, deadline)
    elif atyp == 4:
        _recv_exact(sock, 18, deadline)
    else:
        raise OSError("socks5 bad atyp %d" % atyp)


def test_socks5_telegram_dc(host, port, timeout=None,
                            dc_host=TELEGRAM_DC_HOST, dc_port=TELEGRAM_DC_PORT):
    """经代理 CONNECT Telegram 数据中心 IP，返回往返毫秒；不通返回 None。

    timeout 为本关总预算（秒）：连接、握手、CONNECT 共享同一个 deadline。
    """
    if timeout is None:
        timeout = TG_TEST_TIMEOUT_SEC
    started = time.perf_counter()
    deadline = started + timeout
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except (OSError, ValueError):
        return None
    try:
        _socks5_connect(sock, dc_host, dc_port, deadline)
        return int(round((time.perf_counter() - started) * 1000))
    except (OSError, ValueError):
        return None
    finally:
        try:
            sock.close()
        except OSError:
            pass


def test_telegram_web_via_socks5(host, port, timeout=None,
                                 target_host=TELEGRAM_TEST_HOST,
                                 target_port=TELEGRAM_TEST_PORT):
    """TG 实测第二关：经代理全链路访问 Telegram 站点，返回往返毫秒；不通返回 None。

    流程：代理 -> desktop.telegram.org（CONNECT + TLS 证书校验 + HTTP 状态行，
    验证全链路可用、无劫持）。
    """
    if timeout is None:
        timeout = TG_TEST_TIMEOUT_SEC
    started = time.perf_counter()
    deadline = started + timeout
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except (OSError, ValueError):
        return None
    try:
        _socks5_connect(sock, target_host, target_port, deadline)
        # 隧道建好，套 TLS 并发一次真实 HTTP 请求（TLS 握手共享剩余预算）
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            return None
        sock.settimeout(remaining)
        tls_sock = ssl.create_default_context().wrap_socket(
            sock, server_hostname=target_host
        )
        try:
            tls_sock.sendall(
                (
                    "GET / HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\n"
                    "Connection: close\r\n\r\n" % (target_host, USER_AGENT)
                ).encode()
            )
            status = tls_sock.recv(64)
        finally:
            try:
                tls_sock.close()
            except OSError:
                pass
        if not status.startswith(b"HTTP/"):
            return None
        return int(round((time.perf_counter() - started) * 1000))
    except (OSError, ValueError):
        return None
    finally:
        try:
            sock.close()
        except OSError:
            pass


def tg_dc_timeout_for(max_tg_latency_ms):
    """第一关（CONNECT Telegram DC）的单节点预算，从延迟门槛派生。

    与 `tcp_timeout_for` 同构：`test_socks5_telegram_dc` 的返回值 dc_ms **就是**
    最终的 `tg_latency`，门槛比较的就是它。所以第一关预算必须 ≥ 门槛 + 余量，
    否则「能过门槛」的节点会在第一关被提前砍掉。

    实测依据：默认门槛 1000ms 下，通过两关的节点 dc_p50 ≈ 741ms，2.0s 足够；
    而 dc_fail 组全部顶在超时上限，8.0s 是纯浪费。

    第二关（TLS+HTTP）**不能**跟着这个值走——实测 web_p50 就有 2~3s，
    用 2s 会砍掉一半以上。两关预算必须分开。
    """
    try:
        limit = float(max_tg_latency_ms)
    except (TypeError, ValueError):
        limit = float(TG_LATENCY_LIMIT_MS)
    if limit <= 0:
        limit = float(TG_LATENCY_LIMIT_MS)
    derived = limit / 1000.0 + TG_DC_TIMEOUT_MARGIN_SEC
    return max(TG_DC_TIMEOUT_BASE_SEC, derived)


def tg_deadline_for(dc_timeout, web_timeout):
    """单节点总预算：必须 ≥ 两关之和，否则高门槛用户还没测完就被 deadline 砍掉。

    门槛 60s 时 dc 预算 61s，若 deadline 固定 20s，第一关永远跑不完 ——
    节点会被误判为 deadline 超时。所以 deadline 要跟着两关预算扩展。
    """
    return max(TG_TEST_DEADLINE_SEC, dc_timeout + web_timeout + 2.0)


def test_telegram_via_socks5(host, port, dc_timeout=None, web_timeout=None,
                             deadline=None, sink=None, max_latency_ms=None):
    """TG 实测（管线入口，两关串行）：先经代理 CONNECT Telegram 数据中心
    （客户端实际线路，其往返毫秒作为延迟），通过后再全链路访问
    desktop.telegram.org 验证可用且无劫持；任一关失败返回 None。

    **两关使用各自独立的预算**（不再共用一个 timeout）：
    - dc_timeout  第一关，默认按延迟门槛派生（见 tg_dc_timeout_for）
    - web_timeout 第二关，默认 TG_TEST_TIMEOUT_SEC（8s，与门槛无关）
    - deadline    单节点总预算，默认 max(20, dc + web + 2)，随门槛自动扩展

    sink：可选回调，每节点调用一次，收到
    {"stage": "dc_fail"|"deadline"|"latency_rejected"|"web_fail"|"ok",
     "dc_ms": float, "web_ms": float|None}。只用于度量，不影响返回值。

    max_latency_ms：延迟门槛。**传入时启用剪枝**：第一关的往返就是最终的
    `tg_latency`（门槛比较的就是这个数），所以 `dc_ms > max_latency_ms` 的
    节点必然被剔除，第二关（TLS+HTTP）不必再跑。这是等价变换，不是估算。

    剪枝时**返回 dc_ms 而不是 None**：让下游把它当成「过了两关但超门槛」，
    从而 `tg_failed` 计数与「通过则清零连败」的语义都与剪枝前一致。
    已知偏差：被剪枝节点的第二关结果未知，这里按「连通」计（对拉黑是保守
    方向——宁可不清零，也不误增连败）。
    """
    if dc_timeout is None:
        dc_timeout = tg_dc_timeout_for(
            TG_LATENCY_LIMIT_MS if max_latency_ms is None else max_latency_ms)
    if web_timeout is None:
        web_timeout = TG_TEST_TIMEOUT_SEC
    if deadline is None:
        deadline = tg_deadline_for(dc_timeout, web_timeout)

    started = time.perf_counter()

    t_gate = time.perf_counter()
    dc_ms = test_socks5_telegram_dc(host, port, dc_timeout)
    dc_elapsed = (time.perf_counter() - t_gate) * 1000
    if dc_ms is None:
        if sink is not None:
            sink({"stage": "dc_fail", "dc_ms": round(dc_elapsed, 1), "web_ms": None})
        return None
    if max_latency_ms is not None and dc_ms > max_latency_ms:
        # 剪枝：tg_latency 已注定超门槛，第二关跑了也是白跑
        if sink is not None:
            sink({"stage": "latency_rejected", "dc_ms": round(dc_elapsed, 1),
                  "web_ms": None})
        return dc_ms
    remaining = deadline - (time.perf_counter() - started)
    if remaining <= 1.0:
        if sink is not None:
            sink({"stage": "deadline", "dc_ms": round(dc_elapsed, 1), "web_ms": None})
        return None
    t_gate = time.perf_counter()
    # 第二关用自己的预算，但不越过剩余总预算——防止第一关吃光后拿到极短超时
    web_ok = test_telegram_web_via_socks5(
        host, port, min(web_timeout, remaining)) is not None
    web_elapsed = (time.perf_counter() - t_gate) * 1000
    if sink is not None:
        sink({"stage": "ok" if web_ok else "web_fail",
              "dc_ms": round(dc_elapsed, 1),
              "web_ms": round(web_elapsed, 1)})
    return dc_ms if web_ok else None


def _percentile(values, q):
    """最近秩百分位（样本量小时比插值更贴近观测值）。"""
    if not values:
        return None
    ordered = sorted(values)
    index = int(round(q * (len(ordered) - 1)))
    return round(ordered[max(0, min(len(ordered) - 1, index))], 1)


def summarize_tg_gates(samples):
    """把逐节点的两关耗时样本汇总成分布（不保留原始样本，避免状态膨胀）。

    回答三个问题：多少节点卡在第一关 / 第二关；成功节点的两关各花多久；
    失败节点的耗时是不是恰好顶在超时上限。
    """
    summary = {
        "total": len(samples),
        "timeout_sec": TG_TEST_TIMEOUT_SEC,
        "deadline_sec": TG_TEST_DEADLINE_SEC,
    }
    for stage in ("dc_fail", "deadline", "latency_rejected", "web_fail", "ok"):
        rows = [r for r in samples if r.get("stage") == stage]
        item = {"count": len(rows)}
        dc = [r["dc_ms"] for r in rows if r.get("dc_ms") is not None]
        if dc:
            item["dc_p50"] = _percentile(dc, 0.5)
            item["dc_p90"] = _percentile(dc, 0.9)
            item["dc_max"] = round(max(dc), 1)
        web = [r["web_ms"] for r in rows if r.get("web_ms") is not None]
        if web:
            item["web_p50"] = _percentile(web, 0.5)
            item["web_p90"] = _percentile(web, 0.9)
            item["web_max"] = round(max(web), 1)
        summary[stage] = item
    return summary


def sort_proxies(proxies):
    """TG 实测通过的在前按 TG 延迟升序，未验证的（MTProto）分组在后。"""
    def sort_key(item):
        tg_ok = item.get("tg_ok")
        tg_latency = item.get("tg_latency_ms")
        return (
            0 if tg_ok else 1,
            tg_latency if tg_latency is not None else float("inf"),
            str(item.get("host") or ""),
            int(item.get("port") or 0),
        )

    return sorted(proxies, key=sort_key)


class ProxyManager:
    """代理状态管理：多源抓取 -> 合并去重 -> 抽样 -> 并发测试 -> 供 API 读取。"""

    def __init__(self, proxy_list_urls=None, max_tg_latency_ms=TG_LATENCY_LIMIT_MS,
                 known_good_path=None, source_health_path=None, known_bad_path=None,
                 on_sources_changed=None, known_good_enabled=True, _clock=None):
        self._lock = threading.RLock()  # 可重入：体检全程持锁，内部再取锁不致死锁
        self._proxy_list_urls = list(proxy_list_urls or DEFAULT_PROXY_LIST_URLS)
        self._max_tg_latency_ms = max_tg_latency_ms
        self._known_good_path = known_good_path
        self._known_good_enabled = bool(known_good_enabled)
        self._source_health_path = source_health_path
        self._known_bad_path = known_bad_path
        self._clock = _clock or time.time  # 可注入时钟：单测虚拟推进解封判定
        self._known_bad = {}  # "host:port" -> {"fails": 连败数, "last_fail": 时间戳}
        self._on_sources_changed = on_sources_changed
        self._notice = None
        self._proxies = []
        self._sources = []
        self._stats = {"available": 0, "unverified": 0, "tg_failed": 0, "dropped": 0,
                       "excluded": 0, "blacklisted": 0}
        self._updated_at = None
        self._fetching = False
        self._error = None
        # 上一轮的分阶段耗时（秒）：fetch / prepare / tcp / tg / commit / total。
        # 纯度量，不参与任何判定；P2（流水线化）要不要做，取决于 tcp 与 tg 的占比。
        self._stage_durations = {}
        # 上一轮 TG 两关各自的耗时分布（dc_fail / web_fail / ok 的计数与分位）
        self._tg_gate_stats = {}
        # 实时进度：独立锁，避免与 _lock 抢（_lock 在体检全程持有）
        self._progress_lock = threading.Lock()
        self._live_progress = {"stage": "idle", "done": 0, "total": 0}
        self._load_known_bad()

    def set_proxy_list_urls(self, urls):
        """热更新数据源列表（下次刷新生效）。

        手动编辑按 per-URL diff 宽恕：**只清除本次新增 URL** 的 parked/hist/fails
        （重新观察），未改动的源其健康历史保持不变；读改写在 _lock 内完成，
        避免与刷新线程的体检写盘互相覆盖。
        """
        new_urls = list(urls) if urls else list(DEFAULT_PROXY_LIST_URLS)
        with self._lock:
            old_urls = set(self._proxy_list_urls)
            self._proxy_list_urls = new_urls
            added = [u for u in new_urls if u not in old_urls]
            if not added:
                return
            health = self._load_source_health()
            changed = False
            for url in added:
                h = health.get(url)
                if h and (h.get("parked") or h.get("hist") or h.get("fails")):
                    health[url] = {"hist": [], "fails": 0, "parked": None}
                    changed = True
            if changed:
                self._save_source_health(health)

    def set_known_good_enabled(self, enabled):
        """热更新 known_good 沉淀开关（下次刷新生效）：关闭后不再读取或写入
        known_good.json（已有文件保留不动，重新开启后恢复使用）。"""
        with self._lock:
            self._known_good_enabled = bool(enabled)

    def set_max_tg_latency_ms(self, value):
        """热更新 TG 实测延迟门槛（下次刷新生效）。"""
        with self._lock:
            self._max_tg_latency_ms = value

    def _set_progress(self, stage, done=0, total=0):
        """更新实时进度。

        用独立锁而不是 _lock：体检阶段会长时间持有 _lock，进度上报若挤在
        同一把锁上，会把 /api/proxies 的读取拖慢。
        """
        with self._progress_lock:
            self._live_progress = {"stage": stage, "done": done, "total": total}

    def get_state(self):
        """返回当前完整状态快照（与 /api/proxies 字段一致）。"""
        with self._lock:
            return {
                "updated_at": self._updated_at,
                "fetching": self._fetching,
                "error": self._error,
                "notice": self._notice,
                "stats": dict(self._stats),
                "stage_durations": dict(self._stage_durations),
                "tg_gate_stats": dict(self._tg_gate_stats),
                "progress": dict(self._live_progress),
                "sources": [dict(item) for item in self._sources],
                "proxies": [dict(item) for item in sort_proxies(self._proxies)],
            }

    def refresh_async(self):
        """后台线程触发一轮刷新；已在刷新中则返回 False。"""
        with self._lock:
            if self._fetching:
                return False
        threading.Thread(target=self.refresh, name="proxy-refresh", daemon=True).start()
        return True

    def refresh(self):
        """阻塞式执行一轮刷新（供调度线程或异步线程调用）。"""
        with self._lock:
            if self._fetching:
                return False
            self._fetching = True
            self._error = None
        try:
            self._run_refresh()
        except Exception as exc:  # 兜底：任何异常都要落到 _error，UI 才能感知
            with self._lock:
                self._error = "刷新过程发生异常：%s" % exc
        finally:
            with self._lock:
                self._fetching = False
            # 无论成功、异常还是提前退出，进度都必须回到 idle，否则界面会卡在
            # 一个永远不动的阶段上
            self._set_progress("idle", 0, 0)
        return True

    def _fetch_one(self, url):
        """抓取单个数据源，返回源状态记录（含文本）。"""
        try:
            text, via_mirror = fetch_proxy_text(url)
        except Exception as exc:
            return {"url": url, "status": "failed", "kept": 0,
                    "via_mirror": False, "error": str(exc), "text": None}
        return {"url": url, "status": "ok", "kept": 0,
                "via_mirror": via_mirror, "error": None, "text": text}

    def _run_refresh(self):
        round_started = time.perf_counter()
        stages = {}

        urls = list(self._proxy_list_urls)[:FETCH_MAX_SOURCES]
        sources = []
        t_stage = time.perf_counter()
        self._set_progress("fetch", 0, len(urls))
        fetched = [None] * len(urls)
        with ThreadPoolExecutor(max_workers=max(1, len(urls))) as executor:
            futures = {executor.submit(self._fetch_one, url): index
                       for index, url in enumerate(urls)}
            fetched_done = 0
            for future in as_completed(futures):
                fetched[futures[future]] = future.result()
                fetched_done += 1
                self._set_progress("fetch", fetched_done, len(urls))
        stages["fetch"] = time.perf_counter() - t_stage
        self._set_progress("prepare", 0, 0)
        t_stage = time.perf_counter()

        merged = []
        seen = set()
        excluded_total = 0
        dropped_total = 0
        failures = []
        for item in fetched:
            record = dict(item)
            record.pop("text")
            if item["status"] != "ok":
                failures.append("%s：%s" % (record["url"], item["error"]))
                sources.append(record)
                continue
            entries, excluded_count, dropped_count = parse_proxy_text(item["text"])
            if len(entries) < 5:
                # 按行解析几乎无产出：多为需要爬取的 HTML / JSON 页面，走通用抽取
                crawled = crawl_extract(item["text"])
                if len(crawled) > len(entries):
                    entries, excluded_count, dropped_count = crawled, 0, 0
            if len(entries) > MAX_PER_SOURCE:
                entries = random.sample(entries, MAX_PER_SOURCE)
            kept = 0
            for entry in entries:
                key = (entry["host"], entry["port"])
                if key not in seen:
                    seen.add(key)
                    entry["_src"] = record["url"]  # 逐源归属：TG 验证后计健康度
                    merged.append(entry)
                    kept += 1
            record["kept"] = kept
            sources.append(record)
            excluded_total += excluded_count
            dropped_total += dropped_count

        # 上轮验证通过的节点优先复测：不在池子里也补进来，抽样时优先保留
        preferred_keys = self._merge_known_good(merged, seen)

        # 连续失败拉黑（v1.9.0）：连败达标且未过期的节点跳过本轮测试（含 known_good
        # 补入的节点——连续 3 轮都过不了的沉淀节点同样不值得再测），节省筛选时间
        blacklisted_keys = {
            (host, port) for (host, port) in seen
            if self._is_blacklisted(host, port)
        }
        if blacklisted_keys:
            merged = [
                e for e in merged if (e["host"], e["port"]) not in blacklisted_keys
            ]

        if len(merged) > MAX_TOTAL:
            preferred = [e for e in merged if (e["host"], e["port"]) in preferred_keys]
            rest = [e for e in merged if (e["host"], e["port"]) not in preferred_keys]
            # 每源保底：全局抽样时每个源先各留 SOURCE_SAMPLE_FLOOR 条，
            # 防小源被整源抽空而被体检误读为「0 产出」
            by_src = {}
            for entry in rest:
                by_src.setdefault(entry.get("_src"), []).append(entry)
            floor_entries = []
            floor_keys = set()
            for _src_url, lst in by_src.items():
                take = lst if len(lst) <= SOURCE_SAMPLE_FLOOR \
                    else random.sample(lst, SOURCE_SAMPLE_FLOOR)
                floor_entries.extend(take)
                floor_keys.update((e["host"], e["port"]) for e in take)
            pool = [e for e in rest if (e["host"], e["port"]) not in floor_keys]
            keep_n = max(0, MAX_TOTAL - len(preferred) - len(floor_entries))
            merged = preferred + floor_entries \
                + random.sample(pool, min(len(pool), keep_n))
        stages["prepare"] = time.perf_counter() - t_stage
        t_stage = time.perf_counter()
        tested = self._test_entries(merged)
        stages["tcp"] = time.perf_counter() - t_stage
        # 第一阶段：TCP 通的才进入第二阶段
        tcp_ok = [item for item in tested if item["status"] == "available"]
        # TCP 预筛不通 -> 连败 +1（通过的留到 TG 阶段按两关结论计）
        for item in tested:
            if item["status"] != "available":
                self._record_bad_fail(item["host"], item["port"])
        # 第二阶段：TG 实测（DC -> 站点串行两关）
        t_stage = time.perf_counter()
        verified = self._test_telegram_entries(tcp_ok)
        stages["tg"] = time.perf_counter() - t_stage
        # TG 结论沉淀连败计数：两关通过（含超门槛）清零，未过 +1；MTProto 未验证不计
        for item in verified:
            if item["tg_ok"] is True:
                self._record_bad_pass(item["host"], item["port"])
            elif item["tg_ok"] is False:
                self._record_bad_fail(item["host"], item["port"])
        # TG 两关通过且延迟达标的才保留；不通或太慢的都剔除。
        # MTProto 未参与 TG 实测（tg_ok=None）单独计为 unverified，不计入 available
        verified_ok = [item for item in verified if item["tg_ok"] is True]
        unverified = [item for item in verified if item["tg_ok"] is None]
        kept = [
            item for item in verified_ok
            if item["tg_latency_ms"] is None or item["tg_latency_ms"] <= self._max_tg_latency_ms
        ]
        # tg_failed 含两关未过与超门槛（「含超慢」口径），保证四项统计之和 = 本轮测试总数
        tg_failed = len(verified) - len(unverified) - len(kept)
        # 逐源健康度：每个源本轮贡献的 TG 验证通过数（按合并时首次归属）
        per_src_verified = {}
        for item in verified:
            if item["tg_ok"] is True and item["tg_latency_ms"] is not None \
                    and item["tg_latency_ms"] <= self._max_tg_latency_ms:
                # known_good 兜底补入的节点没有 _src（不属于任何源），跳过归因
                src_url = item.get("_src")
                if not src_url:
                    continue
                per_src_verified[src_url] = per_src_verified.get(src_url, 0) + 1
        notice = self._update_source_health(sources, per_src_verified)
        self._set_progress("commit", 0, 0)
        t_stage = time.perf_counter()
        with self._lock:
            previous = self._proxies
            if kept:
                self._proxies = kept
            elif previous:
                # 本轮 0 达标（含全源抓取失败）：保留上一轮列表，兑现「失败时保留旧列表」
                self._proxies = previous
            else:
                self._proxies = []
            self._sources = sources
            self._stats = {
                "available": len(kept),
                "unverified": len(unverified),
                "tg_failed": tg_failed,
                "dropped": len(tested) - len(tcp_ok),
                "excluded": excluded_total + dropped_total,
                "blacklisted": len(blacklisted_keys),
            }
            self._updated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            self._notice = notice
            if failures and len(failures) == len(urls):
                if kept:
                    self._error = (
                        "全部 %d 个数据源抓取失败：%s；已用 known_good 沉淀复测，保留 %d 个仍可用节点"
                        % (len(urls), failures[0], len(kept))
                    )
                elif previous:
                    self._error = "全部 %d 个数据源抓取失败：%s；已保留上一轮列表" % (
                        len(urls), failures[0])
                else:
                    self._error = "全部 %d 个数据源抓取失败：%s" % (len(urls), failures[0])
            elif not kept and previous:
                self._error = "本轮 0 个节点通过 TG 实测（门槛 %d ms），已保留上一轮列表" % (
                    self._max_tg_latency_ms)
            else:
                self._error = None
        for item in kept:
            item.pop("_src", None)
        for item in unverified:
            item.pop("_src", None)
        # 沉淀本轮验证通过的节点，供下轮优先复测（锁外落盘）；开关关闭时不写
        if kept and self._known_good_enabled:
            self._save_known_good(kept)
        # 连败计数落盘（锁外；每轮一次，先清理过期/超量）
        self._save_known_bad()

        stages["commit"] = time.perf_counter() - t_stage
        stages["total"] = time.perf_counter() - round_started
        with self._lock:
            self._stage_durations = {k: round(v, 3) for k, v in stages.items()}

    def _load_known_good(self):
        """读取上轮验证通过的节点（known_good.json；缺失/损坏返回空表）。"""
        if not self._known_good_path or not os.path.isfile(self._known_good_path):
            return []
        try:
            with open(self._known_good_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return []
        if not isinstance(data, list):
            return []
        return [item for item in data if isinstance(item, dict)]

    def _merge_known_good(self, merged, seen):
        """把上轮验证通过的节点补进候选（不在池子里也补），返回优先保留键集。

        known_good 沉淀开关关闭时不读取、不补入（known_good.json 保留不动）。
        """
        preferred = set()
        if not self._known_good_enabled:
            return preferred
        for item in self._load_known_good():
            host = parse_ipv4(str(item.get("host") or ""))
            try:
                port = int(str(item.get("port")))
            except (TypeError, ValueError):
                continue
            if not host or not 1 <= port <= 65535:
                continue
            key = (host, port)
            preferred.add(key)
            if key not in seen:
                seen.add(key)
                merged.append({
                    "link": "socks5://%s:%d" % (host, port),
                    "host": host,
                    "port": port,
                    "type": "socks5",
                    "latency_ms": None,
                    "status": "unavailable",
                    "location": "",
                    "isp": "",
                    "added_at": "",
                })
        return preferred

    def _save_known_good(self, kept):
        """沉淀本轮验证通过的节点（写盘失败不影响运行）；开关关闭时不写。"""
        if not self._known_good_path or not self._known_good_enabled:
            return
        records = [
            {
                "host": item["host"],
                "port": item["port"],
                "tg_latency_ms": item.get("tg_latency_ms"),
                "ts": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            }
            for item in kept if item.get("tg_ok") is True
        ]
        records.sort(
            key=lambda r: r["tg_latency_ms"] if r["tg_latency_ms"] is not None else 1 << 30
        )
        _atomic_write_json(self._known_good_path, records[:200],
                           ensure_ascii=False, indent=1)

    # ------------------------------------------------------------------
    # 连续失败拉黑（v1.9.0：连败达标的节点跳过测试，节省筛选时间）
    # ------------------------------------------------------------------

    def _load_known_bad(self):
        """读取连败拉黑表（known_bad.json；缺失/损坏/条目非法返回空表，
        并顺带丢弃已过保质期的记录）。"""
        self._known_bad = {}
        if not self._known_bad_path or not os.path.isfile(self._known_bad_path):
            return
        try:
            with open(self._known_bad_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return
        if not isinstance(data, dict):
            return
        now = self._clock()
        for key, item in data.items():
            if not isinstance(item, dict):
                continue
            try:
                fails = int(item["fails"])
                last_fail = float(item["last_fail"])
            except (KeyError, TypeError, ValueError):
                continue
            if fails > 0 and now - last_fail < KNOWN_BAD_EXPIRY_SEC:
                self._known_bad[key] = {"fails": fails, "last_fail": last_fail}

    def _is_blacklisted(self, host, port):
        """该节点当前是否拉黑中（连败达标且未过保质期）。"""
        item = self._known_bad.get("%s:%s" % (host, port))
        if not item:
            return False
        return (item["fails"] >= KNOWN_BAD_STREAK_LIMIT
                and self._clock() - item["last_fail"] < KNOWN_BAD_EXPIRY_SEC)

    def _record_bad_fail(self, host, port):
        """记一次失败（连败 +1）。只改内存，轮末 _save_known_bad 统一落盘。"""
        key = "%s:%s" % (host, port)
        item = self._known_bad.get(key) or {"fails": 0, "last_fail": 0.0}
        item["fails"] += 1
        item["last_fail"] = self._clock()
        self._known_bad[key] = item

    def _record_bad_pass(self, host, port):
        """记一次通过（连败清零；含超门槛——通只是慢，不算坏）。"""
        self._known_bad.pop("%s:%s" % (host, port), None)

    def _save_known_bad(self):
        """轮末落盘：先清理过期与超量条目，再整表覆盖；写盘失败静默。"""
        if not self._known_bad_path:
            return
        now = self._clock()
        self._known_bad = {
            key: item for key, item in self._known_bad.items()
            if now - item["last_fail"] < KNOWN_BAD_EXPIRY_SEC
        }
        if len(self._known_bad) > KNOWN_BAD_MAX_ENTRIES:
            ordered = sorted(self._known_bad.items(),
                             key=lambda kv: kv[1]["last_fail"])
            for key, _item in ordered[:len(self._known_bad) - KNOWN_BAD_MAX_ENTRIES]:
                self._known_bad.pop(key, None)
        _atomic_write_json(self._known_bad_path, self._known_bad,
                           ensure_ascii=False)

    def forgive_sources(self, urls):
        """对给定 URL 中的「被停用源」执行宽恕（清 parked/hist/fails，重新观察）。

        供 main() 启动时调用：用户手动编辑 config.json 加回被停用的源后，
        重启应用即生效。只动被停用的源，健康源的历史不受影响。
        """
        with self._lock:
            health = self._load_source_health()
            changed = False
            for url in urls:
                h = health.get(url)
                if h and h.get("parked"):
                    health[url] = {"hist": [], "fails": 0, "parked": None}
                    changed = True
            if changed:
                self._save_source_health(health)
            return changed

    def _load_source_health(self):
        """读取逐源健康度（source_health.json；缺失/损坏返回空表）。"""
        if not self._source_health_path or not os.path.isfile(self._source_health_path):
            return {}
        try:
            with open(self._source_health_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}

    def _save_source_health(self, health):
        """落盘逐源健康度（写盘失败不影响运行）。"""
        if not self._source_health_path:
            return
        _atomic_write_json(self._source_health_path, health,
                           ensure_ascii=False, indent=1)

    def _update_source_health(self, sources, per_src_verified):
        """逐源体检：更新健康度；连续失败或连续 0 产出的源停用，并从候选池补位。

        - 连续 SOURCE_PARK_FAILS 轮抓取失败 -> 停用
        - 最近 SOURCE_PARK_ZERO_ROUNDS 个有产出的轮次全部 0 个 TG 可用节点 -> 停用
        - 停用后按 SOURCE_CANDIDATES 顺序补位（已停用的候选不再回用）；
          至少保留一个活跃源；变更经 on_sources_changed 回调持久化到配置。
        返回给 UI 的 notice 文本（无变化时为 None）。
        """
        # 全程持锁（RLock 可重入）：健康度读改写与活跃源变更对设置保存线程原子，
        # 消除「体检保存覆盖设置保存」的竞态窗口。
        with self._lock:
            health = self._load_source_health()
            active = list(self._proxy_list_urls)
            # 环境异常判定（分级比例口径）：成功抓取的源中 0 产出占比过高时，
            # 判为 Telegram 封锁 / 门槛调严而非「源变垃圾」——本轮不累计零产出、
            # 不停用、不补位，避免部分劣化（还有个别源活着）时把用户的源全部换掉。
            # 分级：<=2 源无法区分源垃圾与环境异常，仅全 0 时保护；
            #       3~4 源 80% 阈值会退化为「必须全 0」，放宽为「至多 1 个源有产出」；
            #       >=5 源按 80% 比例（SOURCE_GLOBAL_ZERO_RATIO）。
            ok_sources = [r for r in sources if r["status"] == "ok"]
            n_ok = len(ok_sources)
            zero_cnt = sum(
                1 for r in ok_sources if per_src_verified.get(r["url"], 0) == 0
            )
            if n_ok <= 2:
                protected = zero_cnt == n_ok
            elif n_ok <= 4:
                protected = zero_cnt >= n_ok - 1
            else:
                protected = zero_cnt * 10 >= int(
                    round(SOURCE_GLOBAL_ZERO_RATIO * 10)) * n_ok
            global_zero = bool(ok_sources) and protected
            parked_now = []
            for record in sources:
                url = record["url"]
                h = health.setdefault(url, {"hist": [], "fails": 0, "parked": None})
                if record["status"] != "ok":
                    h["fails"] = int(h.get("fails", 0)) + 1
                    h["last_error"] = str(record.get("error") or "")[:120]
                    if h["fails"] >= SOURCE_PARK_FAILS and not h.get("parked"):
                        h["parked"] = "连续 %d 轮抓取失败" % h["fails"]
                else:
                    h["fails"] = 0
                    verified_now = int(per_src_verified.get(url, 0))
                    if global_zero and verified_now == 0:
                        # 环境异常轮：零产出不累计（历史保持不变），暂停停用判定；
                        # 有产出的源照常记录，便于区分「个别源还行」的情况
                        continue
                    hist = [int(x) for x in (h.get("hist") or [])]
                    hist = hist[-(SOURCE_HIST_KEEP - 1):] + [verified_now]
                    h["hist"] = hist
                    tail = hist[-SOURCE_PARK_ZERO_ROUNDS:]
                    if len(tail) >= SOURCE_PARK_ZERO_ROUNDS \
                            and all(x == 0 for x in tail) and not h.get("parked"):
                        h["parked"] = "连续 %d 轮 0 个 TG 可用节点" % SOURCE_PARK_ZERO_ROUNDS
                if h.get("parked") and url in active \
                        and url not in [u for u, _ in parked_now]:
                    parked_now.append((url, h["parked"]))
            # 至少保留一个活跃源：仅当「无候选可补且会把活跃源清零」时撤销本轮停用
            candidates = [c for c in SOURCE_CANDIDATES
                          if c not in active and not health.get(c, {}).get("parked")]
            remaining_after = len(active) - len(parked_now) + min(
                len(parked_now), len(candidates))
            if active and remaining_after < 1:
                for url, _ in parked_now:
                    health[url]["parked"] = None
                parked_now = []
            replaced = []
            for i, (url, reason) in enumerate(parked_now):
                active.remove(url)
                entry_text = "停用 %s（%s）" % (url, reason)
                if i < len(candidates):
                    active.append(candidates[i])
                    health.setdefault(
                        candidates[i], {"hist": [], "fails": 0, "parked": None})
                    entry_text += "，补位 %s" % candidates[i]
                replaced.append(entry_text)
            if replaced:
                self._proxy_list_urls = active
            self._save_source_health(health)  # 无论是否有替换，本轮健康度都落盘
            callback = self._on_sources_changed
            if callback is not None:
                try:
                    callback(list(active))
                except Exception:
                    # 不再静默：这个回调曾因 NameError + 裸 pass 静默失效，
                    # 体检换源从未落盘却无人察觉。留痕优先于「不影响运行」。
                    logger.exception(
                        "源列表持久化回调失败：活跃源已在内存生效，但未落盘"
                    )
        parts = []
        if global_zero:
            parts.append(
                "本轮 %d/%d 个源 0 个 TG 可用节点（占比过高），疑似网络/门槛问题，已暂停自动换源"
                % (zero_cnt, len(ok_sources))
            )
        if replaced:
            parts.append("源自动体检：" + "；".join(replaced))
        return "；".join(parts) if parts else None

    def find_entry(self, host, port):
        """按 host:port 查当前可用列表中的代理（供一键唤起 Telegram），无则返回 None。"""
        with self._lock:
            for item in self._proxies:
                if item["host"] == host and item["port"] == port:
                    return dict(item)
        return None

    def _test_telegram_entries(self, entries):
        """并发 TG 实测：每节点一条线程内先 DC 后站点串行两关（避免对同一免费代理
        同时开两条连接触发限流的假失败）；延迟取「经代理到 Telegram 数据中心的往返」
        （与 Telegram 客户端实际线路一致）。仅 SOCKS5 参与；MTProto 标记为未验证。"""
        if not entries:
            with self._lock:
                self._tg_gate_stats = {}
            return []
        samples = []
        samples_lock = threading.Lock()

        def collect(record):
            with samples_lock:
                samples.append(record)

        # 进度必须在 submit 之前设置：submit 会立刻起 worker，晚一步设的话
        # 第一个任务读到的还是上一个阶段的进度
        socks5_total = sum(1 for e in entries if e["type"] == "socks5")
        self._set_progress("tg", 0, socks5_total)
        results = [None] * len(entries)
        with ThreadPoolExecutor(max_workers=TG_TEST_MAX_WORKERS) as executor:
            futures = {
                index: executor.submit(
                    test_telegram_via_socks5, entry["host"], entry["port"],
                    sink=collect, max_latency_ms=self._max_tg_latency_ms,
                )
                for index, entry in enumerate(entries)
                if entry["type"] == "socks5"
            }
            futures_rev = {f: i for i, f in futures.items()}
            tg_total = len(futures)
            tg_done = 0
            # futures 是 {index: future}，as_completed 要的是 future 序列
            for future in as_completed(futures.values()):
                index = futures_rev[future]
                entry = dict(entries[index])
                latency = future.result()
                entry["tg_ok"] = latency is not None
                entry["tg_latency_ms"] = latency
                results[index] = entry
                tg_done += 1
                if tg_done % PROGRESS_STEP == 0 or tg_done == tg_total:
                    self._set_progress("tg", tg_done, tg_total)
            # 未参与 SOCKS5 实测的（MTProto）标记为未验证
            for index, entry in enumerate(entries):
                if index not in futures:
                    item = dict(entry)
                    item["tg_ok"] = None
                    item["tg_latency_ms"] = None
                    results[index] = item
        with self._lock:
            self._tg_gate_stats = summarize_tg_gates(samples)
        return [item for item in results if item is not None]

    def _test_entries(self, entries):
        """并发 TCP 测试全部代理并回填延迟与状态。"""
        if not entries:
            return []
        # 预筛超时由当前门槛派生：门槛调高时自动放宽，避免把「能过门槛」的
        # 节点提前淘汰（见 tcp_timeout_for 的说明）。
        timeout = tcp_timeout_for(self._max_tg_latency_ms)
        if (timeout > TCP_TIMEOUT_WARN_SEC
                and timeout != getattr(self, "_warned_tcp_timeout", None)):
            self._warned_tcp_timeout = timeout
            logger.warning(
                "TG 延迟门槛 %sms → TCP 预筛超时 %.1fs，本轮耗时会明显增加",
                self._max_tg_latency_ms, timeout,
            )
        results = [None] * len(entries)
        total = len(entries)
        self._set_progress("tcp", 0, total)
        with ThreadPoolExecutor(max_workers=TEST_MAX_WORKERS) as executor:
            futures = {
                executor.submit(test_tcp_latency, entry["host"], entry["port"], timeout): index
                for index, entry in enumerate(entries)
            }
            done = 0
            for future in as_completed(futures):
                index = futures[future]
                entry = dict(entries[index])
                latency = future.result()
                entry["latency_ms"] = latency
                entry["status"] = "available" if latency is not None else "unavailable"
                results[index] = entry
                done += 1
                if done % PROGRESS_STEP == 0 or done == total:
                    self._set_progress("tcp", done, total)
        return results
