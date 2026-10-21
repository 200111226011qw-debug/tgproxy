"""tgproxy 入口：启动本地 HTTP 服务、后台定时刷新并打开浏览器。"""

import json
import os
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from proxy_manager import DEFAULT_PROXY_LIST_URL, ProxyManager

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
STATIC_DIR = os.path.join(BASE_DIR, "static")
INDEX_FILE = os.path.join(STATIC_DIR, "index.html")

DEFAULT_PORT = 8765
MAX_PORT_OFFSET = 50
MIN_INTERVAL_MINUTES = 1
MAX_INTERVAL_MINUTES = 1440

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


def normalize_config(config):
    """校正配置项类型与取值范围。"""
    try:
        interval = int(config.get("interval_minutes", 10))
    except (TypeError, ValueError):
        interval = 10
    config["interval_minutes"] = max(
        MIN_INTERVAL_MINUTES, min(MAX_INTERVAL_MINUTES, interval)
    )

    url = str(config.get("proxy_list_url") or "").strip() or DEFAULT_PROXY_LIST_URL
    config["proxy_list_url"] = url

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
        "proxy_list_url": DEFAULT_PROXY_LIST_URL,
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
            "proxy_list_url": self.settings["proxy_list_url"],
            "port": self.server_address[1],
        }

    def update_settings(self, interval_minutes=None, proxy_list_url=None):
        """更新设置：立即生效并持久化到 config.json。"""
        if interval_minutes is not None:
            self.settings["interval_minutes"] = interval_minutes
            if self.scheduler is not None:
                self.scheduler.set_interval_minutes(interval_minutes)
        if proxy_list_url is not None:
            self.settings["proxy_list_url"] = proxy_list_url
            self.manager.set_proxy_list_url(proxy_list_url)
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

    def do_POST(self):
        try:
            path = urlparse(self.path).path
            if path == "/api/refresh":
                self.server.manager.refresh_async()
                self._send_json({"ok": True})
            elif path == "/api/settings":
                self._handle_settings_update()
            else:
                self._send_json({"ok": False, "error": "Not Found"}, status=404)
        except Exception as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=500)

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
            url = str(url).strip()
            if not url.startswith(("http://", "https://")):
                self._send_json(
                    {
                        "ok": False,
                        "error": "proxy_list_url 必须以 http:// 或 https:// 开头",
                    },
                    status=400,
                )
                return

        settings = self.server.update_settings(
            interval_minutes=interval, proxy_list_url=url
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
    manager = ProxyManager(config["proxy_list_url"])
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
