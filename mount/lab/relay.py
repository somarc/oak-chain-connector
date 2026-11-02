#!/usr/bin/env python3
"""Disposable TCP relay for lab outage tests: listen on LISTEN_PORT, forward to TARGET.

Kill it to simulate "validator down"; start it again for "validator up".
The validator itself is never touched.

usage: relay.py <listen-port> <target-host:port>
"""
import socket
import sys
import threading


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
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
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    print(f"relay 127.0.0.1:{listen_port} -> {host}:{port}", flush=True)
    while True:
        client, _ = server.accept()
        try:
            upstream = socket.create_connection((host, int(port)), timeout=5)
        except OSError:
            client.close()
            continue
        threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
