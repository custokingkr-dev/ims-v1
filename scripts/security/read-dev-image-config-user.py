"""Dev-only digest-bound OCI configured-User proof; no runtime process UID claim.
Primary references: https://docs.cloud.google.com/artifact-registry/docs/docker/authentication
https://github.com/opencontainers/image-spec/blob/main/manifest.md
https://github.com/opencontainers/image-spec/blob/main/config.md
"""
import argparse
import base64
import hashlib
import http.client
import json
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import time
import queue
import threading
from urllib.parse import urlsplit, urljoin

HOST = 'asia-south2-docker.pkg.dev'
PREFIX = HOST + '/custoking-dev/custoking/'
LIMIT = 2 * 1024 * 1024
MANIFEST_TYPES = {'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'}
CONFIG_TYPES = {'application/vnd.oci.image.config.v1+json', 'application/vnd.docker.container.image.v1+json'}
DIGEST = re.compile(r'sha256:[a-f0-9]{64}')

class ProofRejected(Exception):
    pass

def decode(raw):
    return json.loads(raw.decode('utf-16') if raw.startswith(b'\xff\xfe') else raw.decode('utf-8-sig'))

def targets(inventory):
    found = {}
    for item in inventory['services']:
        service = item.get('service')
        if service not in ('frontend', 'api-gateway'):
            continue
        ref = item.get('runtimeRef', '')
        pattern = re.escape(PREFIX + 'custoking-' + service + '@') + r'(sha256:[a-f0-9]{64})'
        matched = re.fullmatch(pattern, ref)
        if not matched or service in found:
            raise ProofRejected('TARGET_REJECTED')
        found[service] = ('custoking-' + service, matched[1])
    if len(found) != 2:
        raise ProofRejected('BOTH_IMAGES_REQUIRED')
    return found

def fetch_bytes(image, kind, digest, token, connection_factory=http.client.HTTPSConnection):
    # A daemon worker also bounds DNS and slow header parsing; socket timeouts alone
    # do not establish a total deadline. A timeout aborts this proof, with no retry.
    completed = queue.Queue(maxsize=1)
    def worker():
        try:
            completed.put((True, _fetch_bytes(image, kind, digest, token, connection_factory)))
        except Exception as error:
            completed.put((False, error))
    threading.Thread(target=worker, daemon=True).start()
    try:
        success, value = completed.get(timeout=15)
    except queue.Empty:
        raise ProofRejected("RESPONSE_DEADLINE_REJECTED") from None
    if not success:
        raise value
    return value

def _fetch_bytes(image, kind, digest, token, connection_factory=http.client.HTTPSConnection):
    if image not in ('custoking-frontend', 'custoking-api-gateway') or kind not in ('manifests','blobs') or not DIGEST.fullmatch(digest):
        raise ProofRejected('REGISTRY_PATH_REJECTED')
    if not re.fullmatch(r'[A-Za-z0-9._~-]+', token):
        raise ProofRejected('TOKEN_SHAPE_REJECTED')
    started = time.monotonic()
    url = 'https://' + HOST + '/v2/custoking-dev/custoking/' + image + '/' + kind + '/' + digest
    auth = base64.b64encode(('oauth2accesstoken:' + token).encode()).decode()
    for hop in range(4):
        parsed = urlsplit(url)
        remaining = 15 - (time.monotonic() - started)
        if remaining <= 0:
            raise ProofRejected('RESPONSE_DEADLINE_REJECTED')
        connection = connection_factory(parsed.hostname, timeout=remaining, context=ssl.create_default_context())
        try:
            headers = {'Accept':','.join(sorted(MANIFEST_TYPES)) if kind=='manifests' else 'application/octet-stream', 'Accept-Encoding':'identity'}
            # Never send registry credentials to a storage origin, even when redirected.
            if parsed.hostname == HOST:
                headers['Authorization'] = 'Basic ' + auth
            connection.request('GET', parsed.path + ('?' + parsed.query if parsed.query else ''), headers=headers)
            response = connection.getresponse()
            if response.status in (301,302,303,307,308):
                if kind != 'blobs' or hop == 3:
                    raise ProofRejected('REDIRECT_REJECTED')
                location = response.getheader('Location')
                if not location or len(location) > 32768:
                    raise ProofRejected('REDIRECT_REJECTED')
                next_url = urljoin(url, location)
                next_parts = urlsplit(next_url)
                if next_parts.scheme != 'https' or next_parts.username or next_parts.password or next_parts.port not in (None,443) or next_parts.fragment:
                    raise ProofRejected('REDIRECT_REJECTED')
                host = next_parts.hostname or ''
                same_registry_download = host == HOST and next_parts.path.startswith('/artifacts-downloads/')
                google_storage = host == 'storage.googleapis.com' or bool(re.fullmatch(r'[a-z0-9][a-z0-9.-]*\.storage\.googleapis\.com',host))
                if not same_registry_download and not google_storage:
                    raise ProofRejected('REDIRECT_REJECTED')
                if not next_parts.path.startswith('/') or any(segment in ('.','..') for segment in next_parts.path.split('/')):
                    raise ProofRejected('REDIRECT_REJECTED')
                url = next_url
                continue
            if response.status != 200:
                raise ProofRejected('REGISTRY_STATUS_REJECTED')
            length = response.getheader('Content-Length')
            if length is not None and (not length.isdecimal() or int(length) > LIMIT):
                raise ProofRejected('RESPONSE_SIZE_REJECTED')
            data = bytearray()
            while True:
                remaining = 15 - (time.monotonic() - started)
                if remaining <= 0:
                    raise ProofRejected('RESPONSE_DEADLINE_REJECTED')
                if connection.sock is not None:
                    connection.sock.settimeout(remaining)
                chunk = response.read1(min(65536, LIMIT + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > LIMIT:
                    raise ProofRejected('RESPONSE_SIZE_REJECTED')
            if 'sha256:' + hashlib.sha256(data).hexdigest() != digest:
                raise ProofRejected('CONTENT_DIGEST_REJECTED')
            return bytes(data)
        finally:
            connection.close()
    raise ProofRejected('REDIRECT_REJECTED')

def inspect(image, digest, token, reader=fetch_bytes):
    manifest_bytes = reader(image, 'manifests', digest, token)
    # Also bind injected controlled readers to the expected content hashes.
    if len(manifest_bytes) > LIMIT or 'sha256:' + hashlib.sha256(manifest_bytes).hexdigest() != digest:
        raise ProofRejected('MANIFEST_DIGEST_REJECTED')
    manifest = json.loads(manifest_bytes)
    if manifest.get('schemaVersion') != 2 or manifest.get('mediaType') not in MANIFEST_TYPES:
        raise ProofRejected('CHILD_MANIFEST_REQUIRED')
    descriptor = manifest.get('config', {})
    config_digest = descriptor.get('digest', '')
    size = descriptor.get('size')
    if not DIGEST.fullmatch(config_digest) or descriptor.get('mediaType') not in CONFIG_TYPES or type(size) is not int or not 0 < size <= LIMIT:
        raise ProofRejected('CONFIG_DESCRIPTOR_REJECTED')
    config_bytes = reader(image, 'blobs', config_digest, token)
    if len(config_bytes) != size or 'sha256:' + hashlib.sha256(config_bytes).hexdigest() != config_digest:
        raise ProofRejected('CONFIG_DIGEST_REJECTED')
    config = json.loads(config_bytes)
    user = config.get('config', {}).get('User', '')
    if not isinstance(user,str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]*(:[A-Za-z0-9_.-]+)?|[0-9]+(:[A-Za-z0-9_.-]+)?', user):
        raise ProofRejected('CONFIGURED_USER_REJECTED')
    principal = user.split(':',1)[0]
    if principal.lower() == 'root' or (principal.isdecimal() and int(principal)==0):
        raise ProofRejected('ROOT_USER_REJECTED')
    return {'runtimeDigest':digest, 'configDigest':config_digest, 'configuredUser':user,
            'manifestHashVerified':True, 'configHashVerified':True, 'nonrootConfiguredUser':True}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--images', required=True)
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--output')
    args=parser.parse_args()
    selected=targets(decode(Path(args.images).read_bytes()))
    if not args.execute:
        print(json.dumps({'execute':False,'images':list(selected),'proofScope':'Image configured User only; not observed Cloud Run process UID'}))
        return 0
    cli=shutil.which('gcloud.cmd') or shutil.which('gcloud')
    if not cli:
        raise ProofRejected('GCLOUD_REQUIRED')
    result=subprocess.run([cli,'auth','print-access-token','--project=custoking-dev'],capture_output=True,timeout=15,check=False)
    if result.returncode:
        raise ProofRejected('TOKEN_ACQUISITION_FAILED')
    token=result.stdout.decode().strip()
    result=None
    proof={'proofScope':'Digest-bound image configured User only; not observed Cloud Run process UID','images':{}}
    for service,(image,digest) in selected.items():
        proof['images'][service]=inspect(image,digest,token)
    token=None
    encoded=json.dumps(proof,indent=2)+'\n'
    if args.output:
        Path(args.output).write_text(encoded,encoding='utf-8')
    else:
        print(encoded,end='')
    return 0

if __name__=='__main__':
    try:
        sys.exit(main())
    except Exception:
        print(json.dumps({'passed':False,'error':'IMAGE_USER_PROOF_REJECTED'}))
        sys.exit(1)
