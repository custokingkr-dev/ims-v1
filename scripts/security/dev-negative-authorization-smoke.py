"""Small dev-only negative authorization smoke. Default plans only; no response bodies logged.
Use --execute after cutover. Optional --id-token-directory contains independently signed
Cloud Run ID tokens named <service>.token, acquired externally by existing authorized
impersonation only. This script performs no credential acquisition or IAM mutations.
"""
import argparse
import http.client
import json
from pathlib import Path
import re
import ssl
import sys
from urllib.parse import urlsplit

SERVICES = ('identity-service', 'school-core-service', 'operations-service', 'platform-service', 'billing-service')
FORGED = {'X-Authenticated-User-Id': '999999999', 'X-Authenticated-Role': 'SUPERADMIN',
          'X-Authenticated-School-Id': '999999999', 'X-Authenticated-Branch-Id': '999999999',
          'X-Authenticated-Permissions': '*', 'X-IMS-Principal-Carrier-Token': 'Bearer controlled.invalid.signature'}


def load(path):
    raw = Path(path).read_bytes()
    return json.loads(raw.decode('utf-16') if raw.startswith(b'\xff\xfe') else raw.decode('utf-8-sig'))


def request(url, method, headers):
    parsed = urlsplit(url)
    connection = http.client.HTTPSConnection(parsed.hostname, timeout=20, context=ssl.create_default_context())
    try:
        # No cookies, proxy configuration, redirect following or automatic retries.
        connection.request(method, parsed.path, headers=headers)
        response = connection.getresponse()
        status = response.status
        response.close()  # Deliberately do not read or record a response body.
        return status
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory', default='tmp/dev-run-services.json')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--id-token-directory')
    args = parser.parse_args()
    inventory = load(args.inventory)
    urls = {}
    for item in inventory:
        name = item['metadata']['name']
        if name in {f'custoking-{service}-dev' for service in (*SERVICES, 'api-gateway')}:
            url = item['status']['url']
            parsed = urlsplit(url)
            expected = name
            if parsed.scheme != 'https' or parsed.path not in ('', '/') or parsed.query or parsed.username or not re.fullmatch(re.escape(expected) + r'-[a-z0-9]+-em\.a\.run\.app', parsed.hostname or ''):
                raise ValueError('Inventory must contain exact approved dev service HTTPS hosts')
            urls[name] = url.rstrip('/')
    if len(urls) != 6:
        raise ValueError('Inventory must contain all five private Java services and the dev gateway')
    cases = []
    tokens = {}
    for service in SERVICES:
        base = urls[f'custoking-{service}-dev']
        cases.append((service, 'anonymous_private', base + '/actuator/health', 'GET', {}, {401, 403}))
        cases.append((service, 'forged_user_without_id_token', base + '/api/v1/users', 'GET', FORGED, {401, 403}))
        if args.id_token_directory:
            token = (Path(args.id_token_directory) / f'{service}.token').read_text().strip()
            if not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token):
                raise ValueError('Expected independently signed Cloud Run ID token file')
            tokens[service] = token
            headers = {**FORGED, 'Authorization': 'Bearer ' + token}
            # Valid Cloud Run transport must not convert forged user metadata into authority.
            cases.append((service, 'signed_transport_forged_principal', base + '/api/v1/users', 'GET', headers, {401, 403}))
    cases.append(('identity-service', 'forged_internal_machine', urls['custoking-identity-service-dev'] + '/api/v1/internal/password-reset/drain', 'POST', {'X-Identity-Service-Token': 'controlled-invalid-peer', **FORGED}, {401, 403}))
    if args.id_token_directory:
        cases.append(('identity-service', 'signed_transport_wrong_machine_peer', urls['custoking-identity-service-dev'] + '/api/v1/internal/password-reset/drain', 'POST', {'Authorization': 'Bearer ' + tokens['identity-service'], 'X-Identity-Service-Token': 'controlled-invalid-peer'}, {401, 403}))
    cases.append(('api-gateway', 'anonymous_protected_gateway', urls['custoking-api-gateway-dev'] + '/api/v1/users', 'GET', FORGED, {401}))
    if not args.execute:
        print(json.dumps({'execute': False, 'requests': len(cases), 'cases': [{'service': service, 'case': case, 'method': method, 'expectedStatuses': sorted(expected)} for service, case, _, method, _, expected in cases]}, indent=2))
        return 0
    passed = True
    for service, case, url, method, headers, expected in cases:
        try:
            status = request(url, method, headers)
            ok = status in expected
            result = {'service': service, 'case': case, 'status': status, 'passed': ok}
        except Exception:
            ok = False
            result = {'service': service, 'case': case, 'passed': False, 'error': 'REQUEST_FAILED'}
        passed &= ok
        print(json.dumps(result), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
