"""Local Android TUN regression fixture; emulator reaches the host at 10.0.2.2.
Run this alongside RuntimeSmokeInstrumentation. No external proxy credentials.
"""
import http.server
import socket
import socketserver
import threading
import select

authorized_relay_ports = set()

class Echo(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.server.server_port == 18082 and self.client_address[1] not in authorized_relay_ports:
            self.send_error(403, 'Expected QA-RELAY, traffic bypassed proxy rule')
            return
        data = self.rfile.read(int(self.headers['Content-Length']))
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass

class Relay(socketserver.StreamRequestHandler):
    def handle(self):
        request = self.rfile.readline().decode().split()
        if len(request) < 2 or request[0] != 'CONNECT': return
        while self.rfile.readline() not in (b'\r\n', b''): pass
        host, port = request[1].rsplit(':', 1)
        if host != '10.0.2.2' or int(port) != 18082: return
        with socket.create_connection(('127.0.0.1', 18082)) as remote:
            authorized_relay_ports.add(remote.getsockname()[1])
            self.wfile.write(b'HTTP/1.1 200 Connection Established\r\n\r\n'); self.wfile.flush()
            peers = [self.connection, remote]
            while True:
                ready, _, _ = select.select(peers, [], [], 15)
                if not ready: return
                for source in ready:
                    data = source.recv(65536)
                    if not data: return
                    (remote if source is self.connection else self.connection).sendall(data)

servers = [http.server.ThreadingHTTPServer(('127.0.0.1', p), Echo) for p in [18081, 18082]]
class RelayServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
servers.append(RelayServer(('127.0.0.1', 18083), Relay))
for server in servers:
    threading.Thread(target=server.serve_forever, daemon=True).start()
print('Ready: DIRECT 18081, proxied 18082, HTTP CONNECT relay 18083', flush=True)
try:
    threading.Event().wait()
except KeyboardInterrupt:
    for server in servers: server.shutdown(); server.server_close()
