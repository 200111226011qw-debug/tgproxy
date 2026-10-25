"""代理列表抓取、解析、Telegram 适配筛选与连通性测试（仅 Python 标准库）。"""

import json
import json
import os
import random
import re
import socket
import ssl
import struct
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from urllib.parse import parse_qs, urlparse

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
FETCH_TIMEOUT_SEC = 15
FETCH_MAX_SOURCES = 20  # 与 app.py 的 MAX_PROXY_LIST_URLS 对齐，用户配的源全部参与抓取与体检
TEST_TIMEOUT_SEC = 5.0
# 免费列表动辄数万条，全量 TCP 测试耗时过久；抽样上限让单轮刷新控制在分钟级
MAX_PER_SOURCE = 1000
MAX_TOTAL = 3000
TEST_MAX_WORKERS = 150
# TG 实测：通过 SOCKS5 代理真实访问 Telegram，验证节点能否连通 Telegram
# 1) Telegram 数据中心（客户端收发消息实际走的线路）  2) Telegram 站点全链路（TLS+HTTP）
TELEGRAM_DC_HOST = "149.154.167.51"
TELEGRAM_DC_PORT = 443
TELEGRAM_TEST_HOST = "desktop.telegram.org"
TELEGRAM_TEST_PORT = 443
TG_TEST_TIMEOUT_SEC = 8.0
TG_TEST_MAX_WORKERS = 100
# 单节点 TG 实测总预算（秒）：两关串行共享，防个别节点拖垮整轮
TG_TEST_DEADLINE_SEC = 20.0
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


def fetch_proxy_text(url, timeout=FETCH_TIMEOUT_SEC):
    """抓取代理列表文本；直连失败时自动回退 jsDelivr 镜像。

    返回 (text, via_mirror)。
    """
    last_error = None
    for candidate in [url] + mirror_urls(url):
        try:
            return _http_get(candidate, timeout), candidate != url
        except Exception as exc:
            last_error = exc
    raise last_error


def test_tcp_latency(host, port, timeout=TEST_TIMEOUT_SEC):
    """TCP 连接测试，返回耗时毫秒；超时/拒绝/错误返回 None。"""
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


def test_socks5_telegram_dc(host, port, timeout=TG_TEST_TIMEOUT_SEC,
                            dc_host=TELEGRAM_DC_HOST, dc_port=TELEGRAM_DC_PORT):
    """经代理 CONNECT Telegram 数据中心 IP，返回往返毫秒；不通返回 None。

    timeout 为本关总预算（秒）：连接、握手、CONNECT 共享同一个 deadline。
    """
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


def test_telegram_web_via_socks5(host, port, timeout=TG_TEST_TIMEOUT_SEC,
                                 target_host=TELEGRAM_TEST_HOST,
                                 target_port=TELEGRAM_TEST_PORT):
    """TG 实测第二关：经代理全链路访问 Telegram 站点，返回往返毫秒；不通返回 None。

    流程：代理 -> desktop.telegram.org（CONNECT + TLS 证书校验 + HTTP 状态行，
    验证全链路可用、无劫持）。
    """
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


def test_telegram_via_socks5(host, port, timeout=TG_TEST_TIMEOUT_SEC,
                             deadline=TG_TEST_DEADLINE_SEC):
    """TG 实测（管线入口，两关串行）：先经代理 CONNECT Telegram 数据中心
    （客户端实际线路，其往返毫秒作为延迟），通过后再全链路访问
    desktop.telegram.org 验证可用且无劫持；任一关失败返回 None。
    两关共享单节点总预算 deadline（秒），防个别节点拖垮整轮。
    """
    stage = max(1.0, min(timeout, deadline))
    started = time.perf_counter()
    dc_ms = test_socks5_telegram_dc(host, port, stage)
    if dc_ms is None:
        return None
    remaining = deadline - (time.perf_counter() - started)
    if remaining <= 1.0:
        return None
    if test_telegram_web_via_socks5(host, port, min(timeout, remaining)) is None:
        return None
    return dc_ms


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
                 known_good_path=None, source_health_path=None,
                 on_sources_changed=None):
        self._lock = threading.RLock()  # 可重入：体检全程持锁，内部再取锁不致死锁
        self._proxy_list_urls = list(proxy_list_urls or DEFAULT_PROXY_LIST_URLS)
        self._max_tg_latency_ms = max_tg_latency_ms
        self._known_good_path = known_good_path
        self._source_health_path = source_health_path
        self._on_sources_changed = on_sources_changed
        self._notice = None
        self._proxies = []
        self._sources = []
        self._stats = {"available": 0, "unverified": 0, "tg_failed": 0, "dropped": 0,
                       "excluded": 0}
        self._updated_at = None
        self._fetching = False
        self._error = None

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

    def set_max_tg_latency_ms(self, value):
        """热更新 TG 实测延迟门槛（下次刷新生效）。"""
        with self._lock:
            self._max_tg_latency_ms = value

    def get_state(self):
        """返回当前完整状态快照（与 /api/proxies 字段一致）。"""
        with self._lock:
            return {
                "updated_at": self._updated_at,
                "fetching": self._fetching,
                "error": self._error,
                "notice": self._notice,
                "stats": dict(self._stats),
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
        urls = list(self._proxy_list_urls)[:FETCH_MAX_SOURCES]
        sources = []
        with ThreadPoolExecutor(max_workers=max(1, len(urls))) as executor:
            fetched = list(executor.map(self._fetch_one, urls))

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
        tested = self._test_entries(merged)
        # 第一阶段：TCP 通的才进入第二阶段
        tcp_ok = [item for item in tested if item["status"] == "available"]
        # 第二阶段：TG 实测（DC -> 站点串行两关）
        verified = self._test_telegram_entries(tcp_ok)
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
        # 沉淀本轮验证通过的节点，供下轮优先复测（锁外落盘）
        if kept:
            self._save_known_good(kept)

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
        """把上轮验证通过的节点补进候选（不在池子里也补），返回优先保留键集。"""
        preferred = set()
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
        """沉淀本轮验证通过的节点（写盘失败不影响运行）。"""
        if not self._known_good_path:
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
        try:
            with open(self._known_good_path, "w", encoding="utf-8") as fh:
                json.dump(records[:200], fh, ensure_ascii=False, indent=1)
        except OSError:
            pass

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
        try:
            with open(self._source_health_path, "w", encoding="utf-8") as fh:
                json.dump(health, fh, ensure_ascii=False, indent=1)
        except OSError:
            pass

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
                    pass  # 配置持久化失败不影响运行；下次刷新会再次尝试
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
            return []
        results = [None] * len(entries)
        with ThreadPoolExecutor(max_workers=TG_TEST_MAX_WORKERS) as executor:
            futures = {
                index: executor.submit(
                    test_telegram_via_socks5, entry["host"], entry["port"]
                )
                for index, entry in enumerate(entries)
                if entry["type"] == "socks5"
            }
            for index, future in futures.items():
                entry = dict(entries[index])
                latency = future.result()
                entry["tg_ok"] = latency is not None
                entry["tg_latency_ms"] = latency
                results[index] = entry
            # 未参与 SOCKS5 实测的（MTProto）标记为未验证
            for index, entry in enumerate(entries):
                if index not in futures:
                    item = dict(entry)
                    item["tg_ok"] = None
                    item["tg_latency_ms"] = None
                    results[index] = item
        return [item for item in results if item is not None]

    def _test_entries(self, entries):
        """并发 TCP 测试全部代理并回填延迟与状态。"""
        if not entries:
            return []
        results = [None] * len(entries)
        with ThreadPoolExecutor(max_workers=TEST_MAX_WORKERS) as executor:
            futures = {
                executor.submit(test_tcp_latency, entry["host"], entry["port"]): index
                for index, entry in enumerate(entries)
            }
            for future, index in futures.items():
                entry = dict(entries[index])
                latency = future.result()
                entry["latency_ms"] = latency
                entry["status"] = "available" if latency is not None else "unavailable"
                results[index] = entry
        return results
