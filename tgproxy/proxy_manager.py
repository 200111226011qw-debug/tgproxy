"""代理列表抓取、解析、Telegram 适配筛选与连通性测试（仅 Python 标准库）。"""

import json
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
FETCH_MAX_SOURCES = 8
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
        except ValueError:
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
    """单次抓取：先常规 SSL，失败换免验证上下文重试一次。"""
    last_error = None
    for ssl_context in (None, ssl._create_unverified_context()):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=timeout, context=ssl_context) as response:
                return response.read().decode("utf-8", errors="replace")
        except Exception as exc:  # 网络环境多样，统一兜底重试一次
            last_error = exc
    raise last_error


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


def _recv_exact(sock, count):
    """精确读取 count 字节，连接提前关闭抛 OSError。"""
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise OSError("connection closed")
        data += chunk
    return data


def _socks5_connect(sock, target_host, target_port, timeout):
    """在已连接的套接字上完成 SOCKS5 无认证握手与 CONNECT，失败抛 OSError。

    域名目标用 ATYP=3 由代理端解析（避免本地 DNS 污染）；IP 目标用 ATYP=1。
    """
    sock.settimeout(timeout)
    sock.sendall(b"\x05\x01\x00")
    if _recv_exact(sock, 2) != b"\x05\x00":
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
    reply = _recv_exact(sock, 4)
    if reply[1] != 0:  # reply code 非 0x00（如 0x01 通用失败 / 0x02 不允许）
        raise OSError("socks5 connect failed: %d" % reply[1])
    atyp = reply[3]
    if atyp == 1:
        _recv_exact(sock, 6)
    elif atyp == 3:
        _recv_exact(sock, 1 + _recv_exact(sock, 1)[0] + 2)
    elif atyp == 4:
        _recv_exact(sock, 18)
    else:
        raise OSError("socks5 bad atyp %d" % atyp)


def test_socks5_telegram_dc(host, port, timeout=TG_TEST_TIMEOUT_SEC,
                            dc_host=TELEGRAM_DC_HOST, dc_port=TELEGRAM_DC_PORT):
    """经代理 CONNECT Telegram 数据中心 IP，返回往返毫秒；不通返回 None。"""
    started = time.perf_counter()
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except (OSError, ValueError):
        return None
    try:
        _socks5_connect(sock, dc_host, dc_port, timeout)
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
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except (OSError, ValueError):
        return None
    try:
        _socks5_connect(sock, target_host, target_port, timeout)
        # 隧道建好，套 TLS 并发一次真实 HTTP 请求
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


def test_telegram_via_socks5(host, port, timeout=TG_TEST_TIMEOUT_SEC):
    """TG 实测（串行合并口径，供脚本复用）：DC 与站点两关都通返回站点耗时。"""
    if test_socks5_telegram_dc(host, port, timeout) is None:
        return None
    return test_telegram_web_via_socks5(host, port, timeout)


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

    def __init__(self, proxy_list_urls=None, max_tg_latency_ms=TG_LATENCY_LIMIT_MS):
        self._lock = threading.Lock()
        self._proxy_list_urls = list(proxy_list_urls or DEFAULT_PROXY_LIST_URLS)
        self._max_tg_latency_ms = max_tg_latency_ms
        self._proxies = []
        self._sources = []
        self._stats = {"available": 0, "tg_failed": 0, "dropped": 0, "excluded": 0}
        self._updated_at = None
        self._fetching = False
        self._error = None

    def set_proxy_list_urls(self, urls):
        """热更新数据源列表（下次刷新生效）。"""
        with self._lock:
            self._proxy_list_urls = list(urls) if urls else list(DEFAULT_PROXY_LIST_URLS)

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
                    merged.append(entry)
                    kept += 1
            record["kept"] = kept
            sources.append(record)
            excluded_total += excluded_count
            dropped_total += dropped_count

        if len(merged) > MAX_TOTAL:
            merged = random.sample(merged, MAX_TOTAL)
        tested = self._test_entries(merged)
        # 第一阶段：TCP 通的才进入第二阶段
        tcp_ok = [item for item in tested if item["status"] == "available"]
        # 第二阶段：TG 实测——经代理真实访问 desktop.telegram.org，不通的剔除
        verified = self._test_telegram_entries(tcp_ok)
        # TG 两关通过且延迟达标的才保留；不通或太慢的都剔除
        kept = [
            item for item in verified
            if item["tg_ok"] is not False
            and (item["tg_latency_ms"] is None or item["tg_latency_ms"] <= self._max_tg_latency_ms)
        ]
        tg_failed = len(verified) - len(kept)
        dropped = len(tested) - len(tcp_ok)
        with self._lock:
            self._proxies = kept
            self._sources = sources
            self._stats = {
                "available": len(kept),
                "tg_failed": tg_failed,
                "dropped": dropped,
                "excluded": excluded_total + dropped_total,
            }
            self._updated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            # 部分源失败不视为整体失败；仅全部失败才提示
            self._error = (
                "全部 %d 个数据源抓取失败：%s" % (len(urls), failures[0])
                if len(failures) == len(urls) and urls
                else None
            )

    def find_entry(self, host, port):
        """按 host:port 查当前可用列表中的代理（供一键唤起 Telegram），无则返回 None。"""
        with self._lock:
            for item in self._proxies:
                if item["host"] == host and item["port"] == port:
                    return dict(item)
        return None

    def _test_telegram_entries(self, entries):
        """并发 TG 实测：DC 与站点两关并行检测，延迟取两者较慢者（贴近真实体验）；
        仅 SOCKS5 参与；MTProto 不适用（tg_ok=None 原样保留）。"""
        if not entries:
            return []
        results = [None] * len(entries)
        with ThreadPoolExecutor(max_workers=TG_TEST_MAX_WORKERS) as executor:
            futures = {}
            for index, entry in enumerate(entries):
                if entry["type"] != "socks5":
                    continue
                dc_future = executor.submit(
                    test_socks5_telegram_dc, entry["host"], entry["port"]
                )
                web_future = executor.submit(
                    test_telegram_web_via_socks5, entry["host"], entry["port"]
                )
                futures[(dc_future, web_future)] = index
            tested = set()
            for (dc_future, web_future), index in futures.items():
                entry = dict(entries[index])
                dc_ms = dc_future.result()
                web_ms = web_future.result()
                if dc_ms is not None and web_ms is not None:
                    # 两关都过才算可用；延迟取「经代理到 Telegram 数据中心的往返」
                    # （与 Telegram 客户端实际线路一致，最贴近使用体感）
                    entry["tg_ok"] = True
                    entry["tg_latency_ms"] = dc_ms
                else:
                    entry["tg_ok"] = False
                    entry["tg_latency_ms"] = None
                results[index] = entry
                tested.add(index)
            # 未参与 SOCKS5 实测的（MTProto）标记为未验证
            for index, entry in enumerate(entries):
                if index not in tested:
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
