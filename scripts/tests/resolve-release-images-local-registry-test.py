"""Optional local Buildx proof. Uses only cached registry:2 and loopback; never cloud/pull/build."""
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=45).stdout.strip()


def encode(value):
    return json.dumps(value, separators=(',', ':')).encode()


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def main():
    # An absent local image is a prerequisite failure, never an implicit download.
    run('docker', 'image', 'inspect', 'registry:2')
    name = 'ims-release-digest-test-' + uuid.uuid4().hex[:12]
    created = False
    try:
        run('docker', 'run', '-d', '--pull=never', '--name', name,
            '-p', '127.0.0.1::5000', 'registry:2')
        created = True
        bound = run('docker', 'port', name, '5000/tcp')
        assert bound.startswith('127.0.0.1:') and len(bound.splitlines()) == 1, bound
        base = 'http://' + bound

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                raise AssertionError('The local fixture must not redirect HTTP requests')

        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

        def request(path, data=None, method='GET', content_type='application/octet-stream'):
            # Registry redirects must remain loopback; no external requests are expected.
            url = path if path.startswith(base + '/') else base + path
            with opener.open(urllib.request.Request(url, data=data, method=method,
                    headers={'Content-Type': content_type,
                             'Accept': 'application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json'}), timeout=5) as response:
                assert response.url.startswith(base + '/'), response.url
                return response.read(), response.headers

        deadline = time.monotonic() + 15
        while True:
            try:
                request('/v2/')
                break
            except (urllib.error.URLError, ConnectionError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

        config = encode({'architecture': 'amd64', 'os': 'linux', 'rootfs': {'type': 'layers', 'diff_ids': []}})
        _, headers = request('/v2/source/blobs/uploads/', data=b'', method='POST')
        upload = headers['Location']
        assert upload.startswith(base + '/'), upload
        separator = '&' if '?' in upload else '?'
        request(upload + separator + 'digest=' + digest(config), config, 'PUT')
        manifest = encode({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                           'config': {'mediaType': 'application/vnd.oci.image.config.v1+json',
                                      'size': len(config), 'digest': digest(config)}, 'layers': []})
        request('/v2/source/manifests/' + digest(manifest), manifest, 'PUT', 'application/vnd.oci.image.manifest.v1+json')
        index = encode({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json',
                        'manifests': [{'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                                       'size': len(manifest), 'digest': digest(manifest),
                                       'platform': {'architecture': 'amd64', 'os': 'linux'}}]})
        request('/v2/source/manifests/' + digest(index), index, 'PUT', 'application/vnd.oci.image.index.v1+json')
        # Cover both an OCI index (provenance-capable build shape) and a single platform image.
        for package, body in [('target-index', index), ('target-single', manifest)]:
            expected = digest(body)
            run('docker', 'buildx', 'imagetools', 'create', '--prefer-index=false',
                '--tag', f'{bound}/{package}@{expected}', f'{bound}/source@{expected}')
            copied, headers = request(f'/v2/{package}/manifests/{expected}')
            assert copied == body and headers['Docker-Content-Digest'] == expected, 'Full OCI bytes changed'
            child, _ = request(f'/v2/{package}/manifests/{digest(manifest)}')
            assert child == manifest, 'Runnable child was not copied intact'
            copied_config, _ = request(f'/v2/{package}/blobs/{digest(config)}')
            assert copied_config == config, 'Runtime config was not copied intact'
            try:
                tags, _ = request(f'/v2/{package}/tags/list')
                assert not json.loads(tags).get('tags'), 'Digest destination unexpectedly created a mutable tag'
            except urllib.error.HTTPError as error:
                # Distribution has no tag-index directory for a digest-only repository.
                codes = {item['code'] for item in json.loads(error.read()).get('errors', [])}
                assert error.code == 404 and codes == {'NAME_UNKNOWN'}, 'Unexpected tag lookup failure'
        print(json.dumps({'passed': True, 'buildx': run('docker', 'buildx', 'version'),
                          'cases': ['index digest destination', 'single manifest digest destination'],
                          'assertions': 'Exact index, child and config bytes; no mutable target tags',
                          'scope': 'Loopback registry only; no image pulls or cloud calls'}))
    finally:
        if created:
            run('docker', 'rm', '-f', name)


if __name__ == '__main__':
    main()
