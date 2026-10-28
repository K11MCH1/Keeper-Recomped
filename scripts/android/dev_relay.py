"""Engineering download relay for a test phone without internet.

Runs on the PC; the phone reaches it through `adb reverse tcp:8765 tcp:8765`.
Debug builds started with `--es debug_relay http://127.0.0.1:8765` fetch
GET /fetch?u=<https url> through it. Only keeperfx.net and GitHub release hosts
are forwarded; Range requests pass through so resumable downloads work.

  python scripts/android/dev_relay.py [--port 8765]
"""
import argparse
import shutil
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED = ('keeperfx.net', 'github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com',
           'codeload.github.com', 'api.github.com', 'raw.githubusercontent.com')


def allowed(host):
    return any(host == h or host.endswith('.' + h) for h in ALLOWED)


class Relay(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        target = urllib.parse.parse_qs(parsed.query).get('u', [''])[0]
        t = urllib.parse.urlparse(target)
        if parsed.path != '/fetch' or t.scheme != 'https' or not allowed(t.hostname or ''):
            self.send_error(403, 'not allowed')
            return
        headers = {'User-Agent': self.headers.get('User-Agent', 'KeeperRecomped-relay')}
        if self.headers.get('Range'):
            headers['Range'] = self.headers['Range']
        try:
            upstream = urllib.request.urlopen(urllib.request.Request(target, headers=headers), timeout=60)
        except urllib.error.HTTPError as e:
            upstream = e
        except Exception as e:  # network failure on the PC side
            self.send_error(502, str(e)[:200])
            return
        with upstream:
            self.send_response(upstream.status if hasattr(upstream, 'status') else upstream.code)
            for name in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges', 'Last-Modified', 'ETag'):
                value = upstream.headers.get(name)
                if value:
                    self.send_header(name, value)
            self.end_headers()
            try:
                shutil.copyfileobj(upstream, self.wfile, 1 << 16)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, fmt, *args):
        print('relay: ' + fmt % args, flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--port', type=int, default=8765)
    a = p.parse_args()
    ThreadingHTTPServer(('127.0.0.1', a.port), Relay).serve_forever()


if __name__ == '__main__':
    main()
