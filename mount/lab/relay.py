#!/usr/bin/env python3
"""Disposable TCP relay for lab outage tests: listen on LISTEN_PORT, forward to TARGET.

Kill it to simulate "validator down"; start it again for "validator up".
The validator itself is never touched.

With LOG, every HTTP request line seen from the client side is appended to LOG as
"<epoch ms> <method> <path>", e.g. to record which segments a mount fetched.

usage: relay.py <listen-port> <target-host:port> [LOG]
"""
import re
import socket
import sys
import threading
import time

REQUEST_LINE = re.compile(rb"(GET|HEAD|POST|PUT|DELETE) (\S+) HTTP/1\.[01]\r\n")
log_lock = threading.Lock()


def pipe(src, dst, log=None):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            if log is not None:
                lines = [f"{int(time.time() * 1000)} {m.group(1).decode()} {m.group(2).decode(errors='replace')}\n"
                         for m in REQUEST_LINE.finditer(data)]
                if lines:
                    with log_lock:
                        log.writelines(lines)
                        log.flush()
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.close()
            except OSError:
                pass


def main():
    listen_port = int(sys.argv[1])
    host, port = sys.argv[2].rsplit(":", 1)
    log = open(sys.argv[3], "a", encoding="utf-8") if len(sys.argv) > 3 else None
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    print(f"relay 127.0.0.1:{listen_port} -> {host}:{port}" + (f" (log {sys.argv[3]})" if log else ""), flush=True)
    while True:
        client, _ = server.accept()
        try:
            upstream = socket.create_connection((host, int(port)), timeout=5)
        except OSError:
            client.close()
            continue
        threading.Thread(target=pipe, args=(client, upstream, log), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
