import json, time, urllib.error, urllib.request

BASE = 'http://localhost:8080'

# Unique per run so repeated runs within the throttle window do not trip the OTP
# buckets (keyed by email). The per-IP bucket is deliberately generous (200/hour).
RUN = str(int(time.time()))
EMAIL = 'smoke+%s@test.local' % RUN
INTRUDER = 'intruder+%s@test.local' % RUN

def req(path, data=None, token=None, method=None):
    m = method or ('POST' if data else 'GET')
    r = urllib.request.Request(BASE + path, method=m)
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    body = json.dumps(data).encode() if data else None
    if body:
        r.add_header('Content-Type', 'application/json')
    return json.load(urllib.request.urlopen(r, body))

def put_raw(url, payload):
    r = urllib.request.Request(url, method='PUT', data=payload)
    return urllib.request.urlopen(r).status

def expect_status(path, data, token, status, method=None):
    m = method or ('POST' if data else 'GET')
    r = urllib.request.Request(BASE + path, method=m)
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    body = json.dumps(data).encode() if data else None
    if body:
        r.add_header('Content-Type', 'application/json')
    try:
        urllib.request.urlopen(r, body)
    except urllib.error.HTTPError as e:
        assert e.code == status, 'expected HTTP %d, got %d' % (status, e.code)
        return
    raise AssertionError('expected HTTP %d, got success' % status)

# --- login ---
otp = req('/v1/auth/otp/request', {'email': EMAIL})
s = req('/v1/auth/otp/verify', {'email': EMAIL, 'code': otp['devCode']})
tok = s['sessionToken']
print('login ok, hasVault:', s['hasVault'])
me = req('/v1/me', token=tok)
assert me['userId'] == s['userId'], '/v1/me must report the session account'
assert '@' in me['email'], '/v1/me must report the account email'
print('me ok:', me['email'])
req('/v1/vault', {'wrapped': {'kdfSalt': 'x', 'wrapped': 'y', 'v': 1}}, tok, method='PUT')

# --- upload session ---
# manifest is NOT an S3 resource: the server must not presign or return a key
# for it. Only original/preview/thumbnail get PUT URLs. The reserved manifest
# size equals the decoded length of headerB64+blobB64 used at completion.
manifest = {'headerB64': 'aGk=', 'blobB64': 'YmxvYg=='}  # decoded len = 2 + 4 = 6
sizes = {'original': 1048576, 'preview': 2048, 'thumbnail': 512, 'manifest': 6}
sess = req('/v1/upload-session', {'items': [{
    'originalSize': 900000,
    'encryptedSizes': sizes,
    'assetIdClient': 'clientref-1',
}]}, tok)
plan = sess['items'][0]
print('plan assetId:', plan['assetId'], 'variants:', sorted(plan['putUrls'].keys()))
assert 'manifest' not in plan['putUrls'], 'manifest must not be presigned'
assert 'manifest' not in plan['keys'], 'manifest must not get an S3 key'
assert set(plan['putUrls'].keys()) == {'original', 'preview', 'thumbnail'}

for variant, url in plan['putUrls'].items():
    status = put_raw(url, b'\x01' * sizes[variant])
    assert status == 200, status
print('all ciphertext PUTs ok')

# manifest is sent in the completion body (encrypted JSONB), not via S3.
resources = [
    {'variant': v, 'objectKey': plan['keys'][v], 'ciphertextSize': sizes[v]}
    for v in plan['keys']
]
done = req('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': manifest, 'resources': resources,
}]}, tok)
print('committed:', done)

# --- completion validation: wrong owner-derived key must be rejected ---
bad_resources = [dict(r) for r in resources]
bad_resources[0]['objectKey'] = 'intruder/' + plan['assetId'] + '/original'
expect_status('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': manifest, 'resources': bad_resources,
}]}, tok, 400)
print('wrong key rejected: HTTP 400')

# --- completion validation: unknown variant must be rejected ---
unk_resources = resources + [{'variant': 'raw', 'objectKey': 'x', 'ciphertextSize': 1}]
expect_status('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': manifest, 'resources': unk_resources,
}]}, tok, 400)
print('unknown variant rejected: HTTP 400')

# --- completion validation: manifest-size mismatch must be rejected ---
bad_manifest = {'headerB64': 'aGk=', 'blobB64': 'eHl6'}  # decoded len 2 + 3 = 5 != 6
expect_status('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': bad_manifest, 'resources': resources,
}]}, tok, 400)
print('manifest size mismatch rejected: HTTP 400')

# --- sync ---
sync = req('/v1/sync/changes?cursor=0', token=tok)
print('sync cursor:', sync['cursor'], 'assets:', len(sync['assets']),
      'variants:', sorted(sync['assets'][0]['resources'].keys()))

# Honest protection reporting: with no independent replica configured, the service
# must not claim anything is "protected remotely".
assert sync['assets'][0]['protected'] is False, 'asset must not be claimed protected without a replica'
status = req('/v1/backup/status', token=tok)
print('backup status:', {k: status[k] for k in
      ('totalAssets', 'protectedAssets', 'unreplicatedAssets', 'pendingUploads', 'replicaConfigured')})
assert status['replicaConfigured'] is False, 'this deployment has no replica bucket'
assert status['protectedAssets'] == 0, 'protectedAssets must be 0 without a replica'
assert status['totalAssets'] >= 1, 'the committed asset must be counted'

# --- authorized resource access ---
res = req('/v1/resource/%s/original' % plan['assetId'], token=tok)
cipher = urllib.request.urlopen(res['url']).read()
print('presigned GET fetched', len(cipher), 'bytes (expected 1048576)')

# --- idempotent repeat commit (same valid resource list) ---
done2 = req('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': manifest, 'resources': resources,
}]}, tok)
assert done2['assets'][0]['seq'] == done['assets'][0]['seq'], 'idempotent repeat seq must be unchanged'
print('idempotent repeat seq unchanged:', done2['assets'][0]['seq'] == done['assets'][0]['seq'])

# --- idempotent repeat with an INVALID resource list must NOT pass ---
inv_resources = [dict(r) for r in resources]
inv_resources[0]['ciphertextSize'] = 999999
expect_status('/v1/upload-session/complete', {'items': [{
    'assetId': plan['assetId'], 'keyEnvelope': 'env1', 'manifest': manifest, 'resources': inv_resources,
}]}, tok, 400)
print('invalid repeat rejected: HTTP 400')

# --- tenant isolation ---
otp2 = req('/v1/auth/otp/request', {'email': INTRUDER})
tok2 = req('/v1/auth/otp/verify', {'email': INTRUDER, 'code': otp2['devCode']})['sessionToken']
expect_status('/v1/resource/%s/original' % plan['assetId'], None, tok2, 401, method='GET')
print('tenant isolation ok (denied): HTTP 401')

# --- export snapshot: immutable, resumable, snapshot-scoped access ---
snap = req('/v1/export/snapshot', {}, tok, method='POST')
print('export snapshot:', {k: snap[k] for k in ('snapshotId', 'assetCount', 'byteTotal')})
assert snap['assetCount'] >= 1, 'snapshot must include the committed asset'
assert snap['byteTotal'] > 0, 'snapshot byte total must be positive'
assert snap['expiresAt'] > snap['createdAt'], 'snapshot must expire in the future'

listed = req('/v1/export/snapshots', token=tok)
assert any(s['snapshotId'] == snap['snapshotId'] for s in listed['snapshots']), 'snapshot not listed'
assert listed['retentionSeconds'] > 0, 'retention window must be disclosed'

page = req('/v1/export/snapshot/%s/assets' % snap['snapshotId'], token=tok)
assert len(page['assets']) >= 1, 'snapshot assets must page'
assert page['assets'][0]['resources'], 'snapshot assets must carry their resources'

# Snapshot-scoped resource access returns the original ciphertext.
sres = req('/v1/resource/%s/original?snapshot=%s' % (plan['assetId'], snap['snapshotId']), token=tok)
scipher = urllib.request.urlopen(sres['url']).read()
assert len(scipher) == sizes['original'], 'snapshot resource fetch must return the original'
print('snapshot-scoped resource fetch ok:', len(scipher), 'bytes')

# Unknown snapshot id -> 404 (and no cross-tenant reads).
expect_status('/v1/export/snapshot/00000000-0000-0000-0000-000000000000', None, tok, 404, method='GET')
expect_status('/v1/export/snapshot/%s' % snap['snapshotId'], None, tok2, 404, method='GET')
print('unknown/cross-tenant snapshot denied: HTTP 404')

# --- OTP abuse controls ---
# Wrong codes must never authenticate, and hammering one address must eventually
# return 429 from the per-email fixed-window throttle.
def post_status(path, data):
    r = urllib.request.Request(BASE + path, method='POST')
    r.add_header('Content-Type', 'application/json')
    try:
        urllib.request.urlopen(r, json.dumps(data).encode())
        return 200
    except urllib.error.HTTPError as e:
        return e.code

wg_email = 'wrongguess+%s@test.local' % RUN
issued = req('/v1/auth/otp/request', {'email': wg_email})
wrong = '000000' if issued['devCode'] != '000000' else '111111'
assert post_status('/v1/auth/otp/verify', {'email': wg_email, 'code': wrong}) == 401, \
    'a wrong code must not yield a session'
print('wrong OTP code rejected: HTTP 401')

rl_email = 'ratelimit+%s@test.local' % RUN
got_429 = False
for _ in range(10):
    if post_status('/v1/auth/otp/request', {'email': rl_email}) == 429:
        got_429 = True
        break
assert got_429, 'per-email OTP throttle must eventually return 429'
print('otp throttle enforced: HTTP 429')
