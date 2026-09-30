"""Local diagnostic: reproduce the browser's thumbnail fetch and report CORS + errors.

Not part of the app or CI — a debugging aid for "photos show as Missing resource".
Usage: python3 scripts/diag_resource_fetch.py [email]
"""
import json
import socket
import sys
import urllib.error
import urllib.request

BASE = 'http://localhost:8080'
EMAIL = sys.argv[1] if len(sys.argv) > 1 else 'rutvik106@live.com'
ORIGIN = 'http://localhost:5173'
socket.setdefaulttimeout(20)


def req(path, data=None, token=None):
    method = 'POST' if data is not None else 'GET'
    r = urllib.request.Request(BASE + path, method=method)
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    body = json.dumps(data).encode() if data is not None else None
    if body:
        r.add_header('Content-Type', 'application/json')
    return json.load(urllib.request.urlopen(r, body))


def main():
    otp = req('/v1/auth/otp/request', {'email': EMAIL})
    tok = req('/v1/auth/otp/verify', {'email': EMAIL, 'code': otp['devCode']})['sessionToken']

    sync = req('/v1/sync/changes?cursor=0', token=tok)
    assets = sync['assets']
    live = [a for a in assets if not a['deleted']]
    print('assets:', len(assets), 'live:', len(live), 'trashed:', len(assets) - len(live))

    for a in live[:3]:
        try:
            res_url = req('/v1/resource/%s/thumbnail' % a['assetId'], token=tok)['url']
            r = urllib.request.Request(res_url, headers={'Origin': ORIGIN})
            resp = urllib.request.urlopen(r)
            hdrs = {k.lower(): v for k, v in resp.headers.items()}
            acao = hdrs.get('access-control-allow-origin')
            print('asset', a['assetId'][:8], 'S3', resp.status, 'bytes', hdrs.get('content-length'),
                  'CORS:', acao if acao else '*** MISSING -> the browser blocks this ***')
        except urllib.error.HTTPError as e:
            print('asset', a['assetId'][:8], 'S3 HTTP', e.code, e.read()[:200])
        except Exception as e:  # noqa: BLE001 - diagnostic script
            print('asset', a['assetId'][:8], 'ERROR', type(e).__name__, e)

    # Does the sync payload carry usable resource metadata?
    print('sample resource payload:', json.dumps(live[0]['resources'].get('thumbnail')))


if __name__ == '__main__':
    main()
