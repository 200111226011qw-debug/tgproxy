"""代理列表抓取、解析、Telegram 适配筛选与连通性测试（仅 Python 标准库）。"""

import random
import re
import socket
import ssl
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from urllib.parse import parse_qs, urlparse

# 默认数据源（均已实测可用；raw.githubusercontent.com 失败时自动回退 jsDelivr 镜像）
DEFAULT_PROXY_LIST_URLS = [
    # 原始源：SOCKS5 + MTProto，带入库时间/地区/ISP 元数据
    "https://raw.githubusercontent.com/watchttvv/free-proxy-list/refs/heads/main/proxy.txt",
    # 每 30 分钟自动验证更新，质量较高
    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
    # 每 6 小时更新，数量适中
    "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
    # 多源聚合大列表
    "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
    "https://raw.githubusercontent.com/zevtyardt/proxy-list/main/socks5.txt",
    "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
]
USER_AGENT = "tgproxy/1.0 (local Telegram proxy manager)"
FETCH_TIMEOUT_SEC = 15
FETCH_MAX_SOURCES = 8
TEST_TIMEOUT_SEC = 5.0
# 免费列表动辄数万条，全量 TCP 测试耗时过久；抽样上限让单轮刷新控制在分钟级
MAX_PER_SOURCE = 1000
MAX_TOTAL = 3000
TEST_MAX_WORKERS = 150
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


def sort_proxies(proxies):
    """可用在前按延迟升序，不可用分组在后。"""
    def sort_key(item):
        latency = item.get("latency_ms")
        return (
            0 if item.get("status") == "available" else 1,
            latency if latency is not None else float("inf"),
            str(item.get("host") or ""),
            int(item.get("port") or 0),
        )

    return sorted(proxies, key=sort_key)


class ProxyManager:
    """代理状态管理：多源抓取 -> 合并去重 -> 抽样 -> 并发测试 -> 供 API 读取。"""

    def __init__(self, proxy_list_urls=None):
        self._lock = threading.Lock()
        self._proxy_list_urls = list(proxy_list_urls or DEFAULT_PROXY_LIST_URLS)
        self._proxies = []
        self._sources = []
        self._stats = {"total_kept": 0, "available": 0, "unavailable": 0, "excluded": 0}
        self._updated_at = None
        self._fetching = False
        self._error = None

    def set_proxy_list_urls(self, urls):
        """热更新数据源列表（下次刷新生效）。"""
        with self._lock:
            self._proxy_list_urls = list(urls) if urls else list(DEFAULT_PROXY_LIST_URLS)

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
        # 只保留测试通过的代理，不通的直接剔除（跟随源池每轮自动更新）
        available = [item for item in tested if item["status"] == "available"]
        with self._lock:
            self._proxies = available
            self._sources = sources
            self._stats = {
                "available": len(available),
                "dropped": len(tested) - len(available),
                "excluded": excluded_total + dropped_total,
                "tested": len(tested),
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
