"""tgproxy 入口：启动本地 HTTP 服务、后台定时刷新并打开浏览器。"""

import json
import logging
import logging.handlers
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from proxy_manager import (
    DEFAULT_PROXY_LIST_URLS,
    TG_LATENCY_LIMIT_MS,
    ProxyManager,
)

# PyInstaller 冻结运行时：可执行文件目录存配置，解包目录（_MEIPASS）取静态资源
if getattr(sys, "frozen", False):
    BASE_DIR = os.path.dirname(os.path.abspath(sys.executable))
    RESOURCE_DIR = getattr(sys, "_MEIPASS", BASE_DIR)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    RESOURCE_DIR = BASE_DIR
STATIC_DIR = os.path.join(RESOURCE_DIR, "static")
VERSION_PATH = os.path.join(RESOURCE_DIR, "VERSION")
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
LOG_PATH = os.path.join(BASE_DIR, "tgproxy.log")
INDEX_FILE = os.path.join(STATIC_DIR, "index.html")

DEFAULT_PORT = 8765
MAX_PORT_OFFSET = 50
# 单实例锁端口：不监听、只 bind，用来判断「是否已有本程序在跑」。
# 取 DEFAULT_PORT-1，避开 HTTP 服务的顺延区间。
SINGLETON_PORT = DEFAULT_PORT - 1
_singleton_socket = None
MIN_INTERVAL_MINUTES = 1
MAX_INTERVAL_MINUTES = 1440
MAX_PROXY_LIST_URLS = 20
MIN_TG_LATENCY_MS = 500
MAX_TG_LATENCY_MS = 60000


def get_app_version():
    """读取 VERSION 单一源；缺失时返回 "unknown"，不让版本号缺失阻断启动。"""
    try:
        with open(VERSION_PATH, "r", encoding="utf-8") as fh:
            version = fh.read().strip()
        return version or "unknown"
    except OSError:
        return "unknown"


APP_VERSION = get_app_version()

MIME_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
}


def normalize_proxy_list_urls(value):
    """校正数据源列表：接受字符串列表 / 单个字符串（旧配置），过滤非法项并去重。"""
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return list(DEFAULT_PROXY_LIST_URLS)
    cleaned = []
    for item in value:
        url = str(item or "").strip()
        if url.startswith(("http://", "https://")) and url not in cleaned:
            cleaned.append(url)
        if len(cleaned) >= MAX_PROXY_LIST_URLS:
            break
    return cleaned or list(DEFAULT_PROXY_LIST_URLS)


def normalize_config(config):
    """校正配置项类型与取值范围。"""
    try:
        interval = int(config.get("interval_minutes", 10))
    except (TypeError, ValueError):
        interval = 10
    config["interval_minutes"] = max(
        MIN_INTERVAL_MINUTES, min(MAX_INTERVAL_MINUTES, interval)
    )

    # 旧版单源键 proxy_list_url 迁移为 proxy_list_urls
    urls = config.pop("proxy_list_url", None)
    if config.get("proxy_list_urls") is None:
        config["proxy_list_urls"] = urls
    config["proxy_list_urls"] = normalize_proxy_list_urls(config.get("proxy_list_urls"))

    try:
        tg_limit = int(config.get("max_tg_latency_ms", TG_LATENCY_LIMIT_MS))
    except (TypeError, ValueError):
        tg_limit = TG_LATENCY_LIMIT_MS
    config["max_tg_latency_ms"] = max(MIN_TG_LATENCY_MS, min(MAX_TG_LATENCY_MS, tg_limit))

    # known_good 沉淀开关：接受 true/false（字符串宽容转换），非法值回退默认开
    kg_enabled = config.get("known_good_enabled", True)
    if isinstance(kg_enabled, str):
        kg_enabled = kg_enabled.strip().lower()
        if kg_enabled in ("true", "1", "yes", "on"):
            kg_enabled = True
        elif kg_enabled in ("false", "0", "no", "off"):
            kg_enabled = False
    if not isinstance(kg_enabled, bool):
        kg_enabled = True
    config["known_good_enabled"] = kg_enabled

    try:
        port = int(config.get("port", DEFAULT_PORT))
    except (TypeError, ValueError):
        port = DEFAULT_PORT
    config["port"] = port if 1 <= port <= 65535 else DEFAULT_PORT
    return config


def load_config():
    """读取 config.json，不存在或损坏时回退默认值。"""
    config = {
        "interval_minutes": 10,
        "proxy_list_urls": list(DEFAULT_PROXY_LIST_URLS),
        "max_tg_latency_ms": TG_LATENCY_LIMIT_MS,
        "known_good_enabled": True,
        "port": DEFAULT_PORT,
    }
    if os.path.isfile(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                loaded = json.load(fh)
            if isinstance(loaded, dict):
                config.update(loaded)
        except (OSError, ValueError):
            pass
    return normalize_config(config)


def save_config(config):
    """持久化配置到 config.json（原子写；写入失败不影响运行）。"""
    tmp = CONFIG_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(normalize_config(config), fh, ensure_ascii=False, indent=2)
        os.replace(tmp, CONFIG_PATH)
    except OSError:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass


def setup_logging():
    """把日志落到 exe 同目录的 tgproxy.log；重复调用幂等。

    windowed 打包后 sys.stdout / sys.stderr 均为 None，任何依赖打印的诊断都会
    静默消失，所以必须走文件。轮转失败（例如被另一进程占用）时退化为 NullHandler，
    不阻塞启动——单实例检测落地前，这是唯一的兜底。
    """
    logger = logging.getLogger("tgproxy")
    if logger.handlers:
        return logger
    logger.setLevel(logging.INFO)
    logger.propagate = False
    formatter = logging.Formatter(
        "%(asctime)s %(levelname)-7s %(name)s | %(message)s"
    )
    try:
        handler = logging.handlers.RotatingFileHandler(
            LOG_PATH, maxBytes=1_000_000, backupCount=3, encoding="utf-8"
        )
    except OSError:
        handler = logging.NullHandler()
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    return logger


def _flush_logging():
    """退出前把日志刷盘——os._exit 不会跑 handler 的 atexit。"""
    for handler in logging.getLogger("tgproxy").handlers:
        try:
            handler.flush()
        except Exception:
            pass


def persist_active_urls(config, urls):
    """源自动体检替换后，把活跃源列表持久化回 config.json。

    config 与 create_server 收到的 settings 是同一个 dict（main 里传入），
    因此原地更新即可同时反映到 /api/settings 与后续落盘。

    2026-10-27 修复：原实现是 main() 内的闭包，引用了不存在的 `settings`，
    每次调用抛 NameError，又被调用点的 `except Exception: pass` 吞掉 ——
    体检换源从未落盘，重启即回到原样。

    未变化时不写盘：调用点每轮都会触发回调（不限于发生替换的轮次），
    无条件 save_config 会变成每轮重写一次 config.json。
    """
    urls = list(urls)
    if config.get("proxy_list_urls") == urls:
        return
    config["proxy_list_urls"] = urls
    save_config(config)
    logging.getLogger("tgproxy").info(
        "源体检换源已落盘：活跃源 %d 个", len(urls)
    )


def open_external_url(url):
    """用系统默认处理程序打开 URL scheme（tg:// 由已安装的 Telegram Desktop 接管）。"""
    if sys.platform == "win32":
        os.startfile(url)  # noqa: 仅 Windows 存在
    elif sys.platform == "darwin":
        subprocess.Popen(["open", url])
    else:
        subprocess.Popen(["xdg-open", url])


def protocol_has_handler(scheme):
    """检查 URL scheme 是否真的关联到了可执行程序。

    为什么必须自己查：Windows 上如果注册表里存在
    `HKCU\\Software\\Classes\\<scheme>` 但**没有 `shell\\open\\command`**
    （典型来源：装过便携版/商店版后残留的残缺键），ShellExecute 会找到这个
    空壳 handler、却没有任何命令可执行 —— 而 `os.startfile` **静默返回成功**，
    不抛异常。表现就是「点了没反应，但程序以为成功了」。

    这里返回 False 时调用方应当降级为「把链接交给用户手动复制」。
    """
    if sys.platform != "win32":
        return True
    try:
        import winreg
    except ImportError:  # pragma: no cover - 非 Windows 分支已提前返回
        return True
    candidates = (
        (winreg.HKEY_CURRENT_USER, r"Software\Classes\%s\shell\open\command" % scheme),
        (winreg.HKEY_CLASSES_ROOT, r"%s\shell\open\command" % scheme),
    )
    for root, path in candidates:
        try:
            with winreg.OpenKey(root, path) as key:
                value, _ = winreg.QueryValueEx(key, "")
        except OSError:
            continue
        if str(value or "").strip():
            return True
    return False


def telegram_proxy_url(entry):
    """把可用代理转换为 Telegram 可识别的深链。

    SOCKS5 必须用 tg://socks（tg://proxy 仅接受带 secret 的 MTProto 链接，
    缺 secret 会被 Telegram 忽略——表现为点击无反应）。
    MTProto 统一收敛为 tg:// 前缀（t.me 链接转 tg://，避免唤起浏览器）。
    """
    if entry["type"] == "mtproto":
        link = entry["link"]
        if link.startswith("tg://"):
            return link
        lowered = link.lower()
        if lowered.startswith(("http://t.me/", "https://t.me/",
                               "http://www.t.me/", "https://www.t.me/")):
            return "tg://" + link.split("://", 1)[1].split("/", 1)[1]
        return link
    return "tg://socks?server=%s&port=%d" % (entry["host"], entry["port"])


class RefreshScheduler:
    """后台线程：按 interval_minutes 周期性触发刷新，间隔可热更新。"""

    def __init__(self, manager, interval_minutes):
        self._manager = manager
        self._interval_minutes = interval_minutes
        self._deadline = time.monotonic() + interval_minutes * 60
        self._stop_event = threading.Event()

    def set_interval_minutes(self, minutes):
        """修改间隔并立即重置倒计时。"""
        self._interval_minutes = minutes
        self._deadline = time.monotonic() + minutes * 60

    def next_run_in_sec(self):
        return max(0, int(round(self._deadline - time.monotonic())))

    def stop(self):
        self._stop_event.set()

    def run(self):
        while not self._stop_event.is_set():
            remaining = self._deadline - time.monotonic()
            if remaining <= 0:
                self._manager.refresh()
                self._deadline = time.monotonic() + self._interval_minutes * 60
            else:
                self._stop_event.wait(min(remaining, 1.0))


class TgProxyServer(ThreadingHTTPServer):
    """携带 manager / settings / scheduler 的 HTTP 服务器。"""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, manager, settings):
        super().__init__(address, handler)
        self.manager = manager
        self.settings = settings
        self.scheduler = None

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            return
        sys.stderr.write("[http] 请求处理异常: %r\n" % (exc,))

    def public_settings(self):
        """对外暴露的设置（与实际监听端口一致）。"""
        return {
            "interval_minutes": self.settings["interval_minutes"],
            "proxy_list_urls": list(self.settings["proxy_list_urls"]),
            "max_tg_latency_ms": self.settings["max_tg_latency_ms"],
            "known_good_enabled": self.settings["known_good_enabled"],
            "port": self.server_address[1],
        }

    def update_settings(self, interval_minutes=None, proxy_list_urls=None,
                        max_tg_latency_ms=None, known_good_enabled=None):
        """更新设置：立即生效并持久化到 config.json。"""
        if interval_minutes is not None:
            self.settings["interval_minutes"] = interval_minutes
            if self.scheduler is not None:
                self.scheduler.set_interval_minutes(interval_minutes)
        if proxy_list_urls is not None:
            self.settings["proxy_list_urls"] = proxy_list_urls
            self.manager.set_proxy_list_urls(proxy_list_urls)
        if max_tg_latency_ms is not None:
            self.settings["max_tg_latency_ms"] = max_tg_latency_ms
            self.manager.set_max_tg_latency_ms(max_tg_latency_ms)
        if known_good_enabled is not None:
            self.settings["known_good_enabled"] = bool(known_good_enabled)
            self.manager.set_known_good_enabled(bool(known_good_enabled))
        save_config(self.settings)
        return self.public_settings()


def acquire_single_instance():
    """用「绑定固定回环端口」做单实例锁，返回 True 表示拿到了锁。

    为什么不用锁文件：进程被强杀时文件删不掉，会留下永久假锁。
    为什么不用扫端口：实测 Windows 对回环关闭端口不是立刻 RST，而是等满
    timeout —— 扫 51 个端口要 10.4s，等于每次冷启动白等 10 秒。
    socket 由操作系统在进程退出时释放，异常退出也能自动解锁。
    """
    global _singleton_socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", SINGLETON_PORT))
    except OSError:
        sock.close()
        return False
    # 保活：进程存活期间一直占着这个端口，不能被 GC 掉
    _singleton_socket = sock
    return True


def probe_instance(port):
    """探测指定端口上是否是本程序的实例。

    只认 `/api/version` 返回 `app == "tgproxy"` 的响应——端口被别的软件占用
    是正常情况（create_server 会顺延），不能据此判定为重复实例。
    """
    if not 1 <= port <= 65535:
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.15)
        if probe.connect_ex(("127.0.0.1", port)) != 0:
            return False
    try:
        with urllib.request.urlopen(
            "http://127.0.0.1:%d/api/version" % port, timeout=1.0
        ) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception:
        return False
    return isinstance(payload, dict) and payload.get("app") == "tgproxy"


def create_server(handler, manager, settings):
    """从配置端口开始探测，被占用则顺延，最多 MAX_PORT_OFFSET 个。"""
    last_error = None
    for offset in range(MAX_PORT_OFFSET + 1):
        port = settings["port"] + offset
        if port > 65535:
            break
        try:
            server = TgProxyServer(("127.0.0.1", port), handler, manager, settings)
            return server, port
        except OSError as exc:
            last_error = exc
    raise RuntimeError(
        "无法绑定端口 %d-%d：%s"
        % (settings["port"], settings["port"] + MAX_PORT_OFFSET, last_error)
    )


class ApiHandler(BaseHTTPRequestHandler):
    """JSON API 与静态页路由。"""

    server_version = "tgproxy/1.0"
    protocol_version = "HTTP/1.1"

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, body, content_type, status=200):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def log_message(self, fmt, *args):
        """HTTP 访问日志。

        windowed 打包后 sys.stdout 是 None，而 send_response() 会先调
        log_request → log_message 再写响应头；直接 write 会让每个请求在发响应
        之前抛 AttributeError，整个服务不可用。必须短路。
        """
        if sys.stdout:
            sys.stdout.write("[http] %s\n" % (fmt % args))
        # 高频访问日志只进 DEBUG，避免 2.5s 轮询把日志文件刷满
        logging.getLogger("tgproxy.http").debug(
            "%s %s", self.address_string(), fmt % args
        )

    def do_GET(self):
        try:
            path = urlparse(self.path).path
            if path == "/api/proxies":
                state = self.server.manager.get_state()
                state["next_run_in_sec"] = (
                    self.server.scheduler.next_run_in_sec()
                    if self.server.scheduler
                    else 0
                )
                self._send_json(state)
            elif path == "/api/version":
                self._send_json({"app": "tgproxy", "version": APP_VERSION})
            elif path == "/api/settings":
                self._send_json(self.server.public_settings())
            elif path in ("/", "/index.html"):
                self._serve_static("index.html")
            elif path.startswith("/static/"):
                self._serve_static(path[len("/static/"):])
            else:
                self._send_json({"ok": False, "error": "Not Found"}, status=404)
        except Exception as exc:  # 兜底：异常也以 JSON 返回，避免前端拿到非 JSON
            self._send_json({"ok": False, "error": str(exc)}, status=500)

    def _origin_allowed(self):
        """写接口防跨站投毒：本服务仅监听 127.0.0.1，浏览器发起的跨站 POST
        必带 Origin 头；只放行本机来源，显式拒绝 Origin: null（file:// /
        sandboxed iframe），无 Origin 的命令行客户端放行（遇
        Sec-Fetch-Site: cross-site 仍拒绝）。"""
        origin = (self.headers.get("Origin") or "").strip()
        if not origin:
            site = (self.headers.get("Sec-Fetch-Site") or "").strip().lower()
            return site != "cross-site"
        if origin.lower() == "null":
            return False
        port = self.server.server_address[1]
        return origin in (
            "http://127.0.0.1:%d" % port,
            "http://localhost:%d" % port,
        )

    def do_POST(self):
        if not self._origin_allowed():
            self._send_json(
                {"ok": False, "error": "已拒绝跨站请求"}, status=403
            )
            return
        try:
            path = urlparse(self.path).path
            if path == "/api/refresh":
                self.server.manager.refresh_async()
                self._send_json({"ok": True})
            elif path == "/api/shutdown":
                # 先回响应再关服务：shutdown() 会阻塞到 serve_forever 退出，
                # 必须放到独立线程，否则响应发不出去、前端只看到连接被重置。
                # 注意不能在子线程里 sys.exit()——那只会在该线程抛 SystemExit，
                # 进程不会退出；正确做法是让 serve_forever 返回，main() 自然结束。
                self._send_json({"ok": True, "message": "正在退出"})
                logging.getLogger("tgproxy").info("收到 /api/shutdown，服务即将退出")
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            elif path == "/api/settings":
                self._handle_settings_update()
            elif path == "/api/open_telegram":
                self._handle_open_telegram()
            else:
                self._send_json({"ok": False, "error": "Not Found"}, status=404)
        except Exception as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=500)

    def _handle_open_telegram(self):
        """唤起 Telegram Desktop 打开添加代理窗口（不经过网页）。"""
        try:
            body = self._read_json_body()
        except (ValueError, UnicodeDecodeError):
            self._send_json({"ok": False, "error": "请求体不是合法 JSON"}, status=400)
            return
        host = str(body.get("host") or "").strip()
        try:
            port = int(body.get("port"))
        except (TypeError, ValueError):
            port = 0
        if not 1 <= port <= 65535:
            self._send_json({"ok": False, "error": "端口不合法"}, status=400)
            return
        # 只允许唤起当前可用列表里存在的代理，避免任意 URL scheme 注入
        entry = self.server.manager.find_entry(host, port)
        if entry is None:
            self._send_json(
                {"ok": False, "error": "该代理不在当前可用列表中，请先刷新"}, status=404
            )
            return
        url = telegram_proxy_url(entry)
        # 先确认协议真的关联了程序：空壳 handler 下 os.startfile 会静默成功，
        # 不校验就会一直返回 ok 而 Telegram 毫无反应
        if not protocol_has_handler("tg"):
            self._send_json({
                "ok": False,
                "error": "系统未正确注册 tg:// 协议，无法自动唤起 Telegram",
                "url": url,
                "hint": "已把链接放到下面的输入框，复制后在 Telegram 收藏夹里粘贴并点击；"
                        "或在 Telegram 设置里搜索 registertg 重新注册协议",
            })
            return
        try:
            open_external_url(url)
        except OSError:
            self._send_json({
                "ok": False,
                "error": "无法唤起 Telegram，请确认已安装 Telegram Desktop",
                "url": url,
                "hint": "复制链接后在 Telegram 收藏夹里粘贴并点击",
            }, status=500)
            return
            return
        self._send_json({"ok": True, "url": url})

    def _handle_settings_update(self):
        try:
            body = self._read_json_body()
        except (ValueError, UnicodeDecodeError):
            self._send_json({"ok": False, "error": "请求体不是合法 JSON"}, status=400)
            return
        if not isinstance(body, dict):
            self._send_json({"ok": False, "error": "请求体必须是 JSON 对象"}, status=400)
            return

        interval = body.get("interval_minutes")
        if interval is not None:
            try:
                interval = int(interval)
            except (TypeError, ValueError):
                self._send_json(
                    {"ok": False, "error": "interval_minutes 必须为整数"}, status=400
                )
                return
            if not MIN_INTERVAL_MINUTES <= interval <= MAX_INTERVAL_MINUTES:
                self._send_json(
                    {
                        "ok": False,
                        "error": "interval_minutes 需在 %d~%d 之间"
                        % (MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES),
                    },
                    status=400,
                )
                return

        url = body.get("proxy_list_url")
        if url is not None:
            # 兼容旧版单源设置：字符串转单元素列表
            body["proxy_list_urls"] = [url]

        urls_body = body.get("proxy_list_urls")
        if urls_body is not None:
            if isinstance(urls_body, str):
                urls_body = [urls_body]
            if not isinstance(urls_body, list) or not urls_body:
                self._send_json(
                    {"ok": False, "error": "proxy_list_urls 必须是非空的 URL 数组"},
                    status=400,
                )
                return
            urls = []
            for item in urls_body:
                url = str(item).strip()
                if not url.startswith(("http://", "https://")):
                    self._send_json(
                        {
                            "ok": False,
                            "error": "proxy_list_urls 中每一项都必须以 http:// 或 https:// 开头",
                        },
                        status=400,
                    )
                    return
                if url not in urls:
                    urls.append(url)
            if len(urls) > MAX_PROXY_LIST_URLS:
                self._send_json(
                    {"ok": False, "error": "数据源最多 %d 个" % MAX_PROXY_LIST_URLS},
                    status=400,
                )
                return
            urls_body = urls

        kg_enabled = body.get("known_good_enabled")
        if kg_enabled is not None and not isinstance(kg_enabled, bool):
            self._send_json(
                {"ok": False, "error": "known_good_enabled 必须是布尔值"}, status=400
            )
            return
        tg_limit = body.get("max_tg_latency_ms")
        if tg_limit is not None:
            try:
                tg_limit = int(tg_limit)
            except (TypeError, ValueError):
                self._send_json(
                    {"ok": False, "error": "max_tg_latency_ms 必须为整数"}, status=400
                )
                return
            if not MIN_TG_LATENCY_MS <= tg_limit <= MAX_TG_LATENCY_MS:
                self._send_json(
                    {
                        "ok": False,
                        "error": "max_tg_latency_ms 需在 %d~%d 之间"
                        % (MIN_TG_LATENCY_MS, MAX_TG_LATENCY_MS),
                    },
                    status=400,
                )
                return

        settings = self.server.update_settings(
            interval_minutes=interval, proxy_list_urls=urls_body,
            max_tg_latency_ms=tg_limit, known_good_enabled=kg_enabled,
        )
        self._send_json({"ok": True, "settings": settings})

    def _serve_static(self, relative):
        relative = relative or "index.html"
        full_path = os.path.normpath(os.path.join(STATIC_DIR, relative))
        if full_path != INDEX_FILE and not full_path.startswith(STATIC_DIR + os.sep):
            self._send_json({"ok": False, "error": "Forbidden"}, status=403)
            return
        if not os.path.isfile(full_path):
            self._send_json({"ok": False, "error": "Not Found"}, status=404)
            return
        ext = os.path.splitext(full_path)[1].lower()
        content_type = MIME_TYPES.get(ext, "application/octet-stream")
        with open(full_path, "rb") as fh:
            self._send_bytes(fh.read(), content_type)


def main():
    setup_logging()
    config = load_config()

    running_port = None
    if not acquire_single_instance():
        # 锁被占：要么真有另一个实例，要么这个端口被别的软件占了。
        # config 里存的是上次成功绑定的端口，所以只探这一个就够。
        if probe_instance(config["port"]):
            running_port = config["port"]
        else:
            logging.getLogger("tgproxy").warning(
                "单实例锁端口 %d 被占用，但 %d 上没有本程序的实例，"
                "继续启动（本次无单实例保护）",
                SINGLETON_PORT, config["port"],
            )
    if running_port is not None:
        address = "http://127.0.0.1:%d/" % running_port
        logging.getLogger("tgproxy").info(
            "检测到已有实例（端口 %d），打开浏览器后退出", running_port
        )
        print("tgproxy %s 已在运行: %s" % (APP_VERSION, address))
        try:
            webbrowser.open(address)
        except Exception:
            pass
        return

    manager = ProxyManager(
        config["proxy_list_urls"],
        config["max_tg_latency_ms"],
        known_good_path=os.path.join(BASE_DIR, "known_good.json"),
        source_health_path=os.path.join(BASE_DIR, "source_health.json"),
        known_bad_path=os.path.join(BASE_DIR, "known_bad.json"),
        rounds_path=os.path.join(BASE_DIR, "rounds.jsonl"),
        on_sources_changed=lambda urls: persist_active_urls(config, urls),
        known_good_enabled=config["known_good_enabled"],
    )
    # 用户手动编辑 config.json 加回被停用的源后，重启即宽恕（重新观察）
    manager.forgive_sources(config["proxy_list_urls"])
    server, port = create_server(ApiHandler, manager, config)
    config["port"] = port
    save_config(config)
    server.scheduler = RefreshScheduler(manager, config["interval_minutes"])

    # 启动即执行一轮抓取 + 测试，之后由调度线程按间隔执行
    manager.refresh_async()
    threading.Thread(
        target=server.scheduler.run, name="refresh-scheduler", daemon=True
    ).start()

    address = "http://127.0.0.1:%d/" % port
    print("tgproxy %s 已启动: %s" % (APP_VERSION, address))
    print("配置文件: %s" % CONFIG_PATH)
    print("按 Ctrl+C 退出。")
    try:
        webbrowser.open(address)
    except Exception:
        print("无法自动打开浏览器，请手动访问: %s" % address)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n正在退出 tgproxy ...")
    finally:
        server.scheduler.stop()
        server.server_close()
        logging.getLogger("tgproxy").info("tgproxy %s 已退出", APP_VERSION)
        _flush_logging()
        # 退出时若正有一轮刷新在跑，concurrent.futures 的 atexit 钩子会 join
        # 所有 worker 线程（最坏等于一整轮耗时，实测 >12s），表现为「点了退出
        # 但进程挂着」。这里直接结束进程。落盘已全部改为原子写（tmp +
        # os.replace），强退不会留下半个 JSON。
        os._exit(0)


if __name__ == "__main__":
    main()
