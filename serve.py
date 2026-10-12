"""Local dev server with caching off, so edits show up on a plain refresh."""
import http.server
import sys


class NoCache(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


port = int(sys.argv[1]) if len(sys.argv) > 1 else 5173
host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
print(f"Headway on http://{host}:{port}")
http.server.ThreadingHTTPServer((host, port), NoCache).serve_forever()
