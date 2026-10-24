"""tgproxy 入口：启动本地 HTTP 服务、后台定时刷新并打开浏览器。"""

import json
import os
import subprocess
import sys
import threading
import time
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
    STATIC_DIR = os.path.join(getattr(sys, "_MEIPASS", BASE_DIR), "static")
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    STATIC_DIR = os.path.join(BASE_DIR, "static")
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
INDEX_FILE = os.path.join(STATIC_DIR, "index.html")

DEFAULT_PORT = 8765
MAX_PORT_OFFSET = 50
MIN_INTERVAL_MINUTES = 1
MAX_INTERVAL_MINUTES = 1440
MAX_PROXY_LIST_URLS = 20
MIN_TG_LATENCY_MS = 500
MAX_TG_LATENCY_MS = 60000

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
    """持久化配置到 config.json（写入失败不影响运行）。"""
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
            json.dump(normalize_config(config), fh, ensure_ascii=False, indent=2)
    except OSError:
        pass


def open_external_url(url):
    """用系统默认处理程序打开 URL scheme（tg:// 由已安装的 Telegram Desktop 接管）。"""
    if sys.platform == "win32":
        os.startfile(url)  # noqa: 仅 Windows 存在
    elif sys.platform == "darwin":
        subprocess.Popen(["open", url])
    else:
        subprocess.Popen(["xdg-open", url])


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
            "port": self.server_address[1],
        }

    def update_settings(self, interval_minutes=None, proxy_list_urls=None,
                        max_tg_latency_ms=None):
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
        save_config(self.settings)
        return self.public_settings()


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
        sys.stdout.write("[http] %s\n" % (fmt % args))

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
        try:
            open_external_url(url)
        except OSError:
            self._send_json(
                {"ok": False, "error": "无法唤起 Telegram，请确认已安装 Telegram Desktop"},
                status=500,
            )
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
            max_tg_latency_ms=tg_limit,
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
    config = load_config()
    manager = ProxyManager(
        config["proxy_list_urls"],
        config["max_tg_latency_ms"],
        known_good_path=os.path.join(BASE_DIR, "known_good.json"),
    )
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
    print("tgproxy 已启动: %s" % address)
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


if __name__ == "__main__":
    main()
