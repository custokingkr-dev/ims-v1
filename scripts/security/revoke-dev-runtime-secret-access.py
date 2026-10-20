"""Dev-only owner credential ACL cutover. Defaults to read-only preflight/dry run."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import socket
import threading
import time
import urllib.request
import urllib.error

PROJECT, REGION = 'custoking-dev', 'asia-south2'
SERVICES = {'identity-service': ('identity', 'ims_identity_rt'), 'school-core-service': ('school-core', 'ims_school_core_rt'),
            'operations-service': ('operations', 'ims_operations_rt'), 'platform-service': ('platform', 'ims_platform_rt'),
            'billing-service': ('billing', 'ims_billing_rt'), 'api-gateway': ('api-gateway', None), 'frontend': ('frontend', None)}
def member(prefix): return f'serviceAccount:ims-{prefix}-dev@{PROJECT}.iam.gserviceaccount.com'
JAVA_MEMBERS = {member(prefix) for prefix, role in SERVICES.values() if role}
ALL_MEMBERS = {member(prefix) for prefix, role in SERVICES.values()}
MIGRATOR = f'serviceAccount:ims-db-migration-dev@{PROJECT}.iam.gserviceaccount.com'
ACCESSOR = 'roles/secretmanager.secretAccessor'
REMOVALS = {'db-password-dev': JAVA_MEMBERS, 'app-rt-password-dev': JAVA_MEMBERS, 'jwt-secret-dev': {member('api-gateway')}}
DANGEROUS = {'secretmanager.versions.access', 'secretmanager.secrets.setIamPolicy', 'resourcemanager.projects.setIamPolicy',
             'iam.serviceAccounts.getAccessToken', 'iam.serviceAccounts.setIamPolicy'}

class Blocked(RuntimeError): pass
def require(check, reason):
    if not check: raise Blocked(reason)

class Client:
    def __init__(self, command=None):
        self.command = command or ['gcloud.cmd' if os.name == 'nt' else 'gcloud']
        self.token = None
        self.body_timeout = 30
        self.write_attempts = []
        self.writes_returned = []
    def native(self, args):
        try:
            result = subprocess.run(self.command + args, capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise Blocked('Bounded cloud CLI failed') from error
        require(result.returncode == 0, 'Cloud metadata preflight failed; no policy write authorized')
        require(len(result.stdout.encode()) <= 2_000_000, 'Cloud metadata response exceeded limit')
        return result.stdout
    def gcloud(self, args): return json.loads(self.native(args + ['--format=json']))
    def rest(self, secret, policy=None):
        require(secret in REMOVALS, 'Unexpected secret resource')
        if self.token is None: self.token = self.native(['auth', 'print-access-token']).strip()
        require(bool(self.token), 'Missing short-lived API authorization')
        action = 'setIamPolicy' if policy is not None else 'getIamPolicy?options.requestedPolicyVersion=3'
        url = f'https://secretmanager.googleapis.com/v1/projects/{PROJECT}/secrets/{secret}:{action}'
        data = json.dumps({'policy': policy}).encode() if policy is not None else None
        request = urllib.request.Request(url, data=data, method='POST' if data else 'GET',
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        if policy is not None: self.write_attempts.append(secret)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                transport = getattr(getattr(getattr(response, 'fp', None), 'raw', None), '_sock', None)
                require(transport is not None, 'IAM response cancellation transport unavailable')
                def abort():
                    try: transport.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                timer = threading.Timer(self.body_timeout, abort); timer.daemon = True; timer.start()
                deadline = time.monotonic() + self.body_timeout; chunks = []; size = 0
                try:
                    while True:
                        require(time.monotonic() < deadline, 'IAM whole-body deadline exceeded')
                        chunk = response.read1(min(65536, 2_000_001 - size))
                        if not chunk: break
                        chunks.append(chunk); size += len(chunk)
                        require(size <= 2_000_000, 'IAM response exceeded limit')
                    require(time.monotonic() < deadline, 'IAM whole-body deadline exceeded')
                finally: timer.cancel()
            parsed = json.loads(b''.join(chunks))
            if policy is not None: self.writes_returned.append(secret)
            return parsed
        except Blocked: raise
        except Exception as error:
            if isinstance(error, urllib.error.HTTPError): error.close()
            raise Blocked('IAM REST request failed; etag conflict or permission failure blocks cutover') from error

def env_map(container):
    values = container.get('env', [])
    require(len({item['name'] for item in values}) == len(values), 'Duplicate environment entries')
    return {item['name']: item for item in values}

def container_contract(spec, service):
    prefix, role = SERVICES[service]
    require(spec.get('serviceAccountName') == member(prefix).split(':', 1)[1], 'Runtime service account mismatch')
    containers = spec.get('containers', [])
    require(len(containers) == 1, 'Unexpected sidecar/container configuration')
    env = env_map(containers[0])
    require(not any(name.startswith(('FLYWAY_', 'SPRING_FLYWAY_')) for name in env), 'Owner migration environment still attached')
    for entry in env.values():
        attached = entry.get('valueFrom', {}).get('secretKeyRef', {}).get('name')
        require(attached not in ('db-password-dev', 'app-rt-password-dev'), 'Legacy owner/shared database secret still attached')
        require(attached != 'jwt-secret-dev' or service == 'identity-service', 'Shared JWT signer secret attached outside identity')
    if role:
        require(env.get('APP_MIGRATIONS_ENABLED', {}).get('value') == 'false', 'Runtime migrations must be explicitly disabled')
        require(env.get('SPRING_DATASOURCE_USERNAME', {}).get('value') == role, 'Dedicated database role mismatch')
        secret = env.get('SPRING_DATASOURCE_PASSWORD', {}).get('valueFrom', {}).get('secretKeyRef', {})
        require(secret.get('name') == f'{prefix}-runtime-db-password-dev' and secret.get('key'), 'Dedicated runtime database secret mismatch')
    else:
        require(not any('JWT' in name and 'SECRET' in name for name in env), 'Shared JWT signing secret still attached')
        require(not any(item.get('valueFrom', {}).get('secretKeyRef', {}).get('name') in REMOVALS for item in env.values()), 'Legacy secret reference still attached')
    if service == 'api-gateway':
        require(env.get('GATEWAY_AUTH_MODE', {}).get('value') == 'enforce', 'Gateway authentication is not enforced')
        require(env.get('GATEWAY_LOCAL_JWT_VERIFY', {}).get('value') == 'disabled', 'Gateway local shared-key verification must be disabled')
        require(containers[0].get('ports') == [{'containerPort': 8080}] or
                len(containers[0].get('ports', [])) == 1 and containers[0]['ports'][0].get('containerPort') == 8080, 'Gateway must listen on port 8080')
    return True

def preflight_revisions(client):
    snapshot = {}
    for service in SERVICES:
        name = f'custoking-{service}-dev'
        current = client.gcloud(['run', 'services', 'describe', name, f'--project={PROJECT}', f'--region={REGION}'])
        status = current.get('status', {})
        revision = status.get('latestReadyRevisionName')
        require(revision and revision == status.get('latestCreatedRevisionName'), 'Latest revision is not current and ready')
        require(any(c.get('type') == 'Ready' and c.get('status') == 'True' for c in status.get('conditions', [])), 'Service is not ready')
        require(status.get('observedGeneration') == current.get('metadata', {}).get('generation'), 'Desired service generation is not observed')
        traffic = status.get('traffic', [])
        require(len(traffic) == 1 and traffic[0].get('percent') == 100 and traffic[0].get('revisionName') == revision
                and not traffic[0].get('tag'), 'Traffic must serve only the latest ready revision')
        desired_traffic = current.get('spec', {}).get('traffic', [])
        require(len(desired_traffic) == 1 and desired_traffic[0].get('percent') == 100 and not desired_traffic[0].get('tag')
                and (desired_traffic[0].get('latestRevision') is True or desired_traffic[0].get('revisionName') == revision), 'Desired traffic is not 100 percent current')
        container_contract(current['spec']['template']['spec'], service)
        ready = client.gcloud(['run', 'revisions', 'describe', revision, f'--project={PROJECT}', f'--region={REGION}'])
        require(ready.get('metadata', {}).get('name') == revision and any(c.get('type') == 'Ready' and c.get('status') == 'True'
                for c in ready.get('status', {}).get('conditions', [])), 'Live revision readiness mismatch')
        container_contract(ready['spec'], service)
        snapshot[service] = revision
    return snapshot

def role_permissions(client, role):
    args = ['iam', 'roles', 'describe', role]
    if role.startswith('projects/'):
        _, project, _, role_id = role.split('/'); args = ['iam', 'roles', 'describe', role_id, f'--project={project}']
    elif role.startswith('organizations/'):
        _, organization, _, role_id = role.split('/'); args = ['iam', 'roles', 'describe', role_id, f'--organization={organization}']
    description = client.gcloud(args)
    require(isinstance(description.get('includedPermissions'), list), 'IAM role capabilities cannot be verified')
    return set(description['includedPermissions'])

def inherited_guard(client):
    ancestors = client.gcloud(['projects', 'get-ancestors', PROJECT])
    require(any(a.get('type') == 'project' for a in ancestors), 'Project ancestry cannot be verified')
    role_cache = {}
    for ancestor in ancestors:
        kind, identifier = ancestor['type'], str(ancestor['id'])
        if kind == 'project': args = ['projects', 'get-iam-policy', PROJECT]
        elif kind == 'folder': args = ['resource-manager', 'folders', 'get-iam-policy', identifier]
        elif kind == 'organization': args = ['organizations', 'get-iam-policy', identifier]
        else: raise Blocked('Unknown IAM ancestry type')
        policy = client.gcloud(args)
        for binding in policy.get('bindings', []):
            members = binding.get('members', [])
            affected = bool(ALL_MEMBERS.intersection(members))
            uncertain = any(m in ('allUsers', 'allAuthenticatedUsers') or m.startswith('group:') for m in members)
            if not affected and not uncertain: continue
            role = binding['role']
            permissions = role_cache.setdefault(role, role_permissions(client, role)) if role not in role_cache else role_cache[role]
            require(not (DANGEROUS.intersection(permissions) or role in ('roles/secretmanager.secretAccessor', 'roles/secretmanager.admin', 'roles/owner', 'roles/editor')),
                    'Inherited runtime or unresolved group/public secret privilege blocks narrow revocation')
    return len(ancestors)

def retained(policy, principal):
    return any(b.get('role') == ACCESSOR and principal in b.get('members', []) and not b.get('condition') for b in policy.get('bindings', []))

def revoke_policy(policy, removals):
    require(bool(policy.get('etag')), 'IAM etag is mandatory')
    require(not any(b.get('condition') for b in policy.get('bindings', [])) or policy.get('version') == 3, 'Conditional IAM policy version is unsafe')
    result = copy.deepcopy(policy); removed = 0; bindings = []
    for binding in result.get('bindings', []):
        if binding.get('role') == ACCESSOR:
            original = binding.get('members', [])
            binding['members'] = [m for m in original if m not in removals]
            removed += len(original) - len(binding['members'])
            if not binding['members']: continue
        bindings.append(binding)
    result['bindings'] = bindings
    return result, removed

def canonical(policy):
    result = copy.deepcopy(policy); result.pop('etag', None)
    if not any(b.get('condition') for b in result.get('bindings', [])): result.pop('version', None)
    result['bindings'] = sorted([{**b, 'members': sorted(b.get('members', []))} for b in result.get('bindings', [])], key=lambda b: json.dumps(b, sort_keys=True))
    return result

def execute(client, apply=False):
    snapshot = preflight_revisions(client)
    ancestry_count = inherited_guard(client)
    policies = {secret: client.rest(secret) for secret in REMOVALS}
    require(retained(policies['db-password-dev'], MIGRATOR), 'Migration account must retain unconditional owner-secret access')
    require(retained(policies['jwt-secret-dev'], member('identity')), 'Identity signer must retain unconditional JWT-secret access')
    planned = {}; counts = {}
    for secret, removals in REMOVALS.items():
        for binding in policies[secret].get('bindings', []):
            present = ALL_MEMBERS.intersection(binding.get('members', []))
            allowed = removals | ({member('identity')} if secret == 'jwt-secret-dev' else set())
            uncertain = any(m in ('allUsers', 'allAuthenticatedUsers') or m.startswith('group:') for m in binding.get('members', []))
            if binding.get('role') == ACCESSOR:
                require(not present.difference(allowed), 'Unexpected runtime accessor on protected secret; broader revocation is forbidden')
                require(not uncertain, 'Unresolved group/public secret accessor blocks narrow revocation')
            elif present or uncertain:
                require(not DANGEROUS.intersection(role_permissions(client, binding['role'])), 'Non-accessor secret privilege requires separate remediation')
        planned[secret], counts[secret] = revoke_policy(policies[secret], removals)
    if apply:
        for secret in REMOVALS:
            require(preflight_revisions(client) == snapshot, 'Live revision changed after preflight; cutover blocked')
            if counts[secret]: client.rest(secret, planned[secret])
            require(canonical(client.rest(secret)) == canonical(planned[secret]), 'Post-write IAM differs from exact planned preservation')
        inherited_guard(client)
        final = {secret: client.rest(secret) for secret in REMOVALS}
        require(retained(final['db-password-dev'], MIGRATOR) and retained(final['jwt-secret-dev'], member('identity')), 'Required migration/signer capability was lost')
        require(preflight_revisions(client) == snapshot, 'Live revision changed during revocation')
    return {'project': PROJECT, 'region': REGION, 'apply': apply, 'preflightPassed': True, 'servicesVerified': len(snapshot),
            'revisions': snapshot, 'iamAncestorPoliciesVerified': ancestry_count, 'removedMemberships' if apply else 'plannedMembershipRemovals': counts,
            'migrationOwnerAccessRetained': True, 'identitySignerAccessRetained': True, 'secretValuesAccessed': False}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--evidence', type=Path, default=Path('release-evidence/runtime-secret-revocation-dev.json'))
    args = parser.parse_args()
    client = Client()
    try:
        evidence = execute(client, args.apply)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, indent=2))
    except Exception as error:
        reason = str(error) if isinstance(error, Blocked) else 'metadata/transport error'
        failure = {'project': PROJECT, 'region': REGION, 'applyRequested': args.apply, 'success': False, 'failure': reason,
                   'iamSetCallsAttempted': len(client.write_attempts), 'iamSetResponsesReturned': len(client.writes_returned), 'secretValuesAccessed': False}
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(failure, indent=2) + '\n')
        print('Runtime secret revocation blocked: ' + reason, file=sys.stderr)
        sys.exit(1)
