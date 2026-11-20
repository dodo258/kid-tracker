#!/usr/bin/env python3
"""本机记录服务：收 OsmAnd 协议上报（表单或查询参数），每个请求追加一行 JSON 到文件，返回 200。

只监听 127.0.0.1（模拟器里用 10.0.2.2 访问），测试数据不出本机。
用法：recorder.py <端口> <输出 jsonl> [<模式文件>]
模式文件（压测注入服务器故障用，可选）：内容是 ok（默认）、5xx（不记、回 503）、hang（不记、不回，挂到客户端自己断开）
或 slow（照记，过 SLOW_SECONDS 秒再回 200：让一条上传在途久一点，压测「队列满到上限」时用）。
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

HANG_SECONDS = 600
# 比 App 的读超时（20 秒）短：慢但收得到回复
SLOW_SECONDS = 12


class Handler(BaseHTTPRequestHandler):
    out = None
    mode_file: Path | None = None

    def _mode(self) -> str:
        if self.mode_file is None:
            return "ok"
        try:
            return self.mode_file.read_text().strip() or "ok"
        except OSError:
            return "ok"

    def _record(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        mode = self._mode()
        if mode == "hang":
            time.sleep(HANG_SECONDS)
            return
        if mode == "5xx":
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        params = dict(parse_qsl(urlsplit(self.path).query))
        params.update(parse_qsl(body))
        row = {"recv": time.time(), "method": self.command, "path": urlsplit(self.path).path, "params": params}
        with open(self.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
        if mode == "slow":
            time.sleep(SLOW_SECONDS)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_POST = _record

    def log_message(self, *args):
        pass


def main():
    port, out = int(sys.argv[1]), sys.argv[2]
    Handler.out = out
    Handler.mode_file = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
