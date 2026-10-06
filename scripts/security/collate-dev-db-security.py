"""Offline catalog-only collator. No network, credentials, or application rows."""
import argparse, datetime, hashlib, json, pathlib, sys

SCHEMAS = {'ims_identity_rt': {'identity'}, 'ims_school_core_rt': {'tenant_school','student','attendance','fee','catalog'}, 'ims_operations_rt': {'workflow','firefighting'}, 'ims_platform_rt': {'reporting','notification','audit'}, 'ims_billing_rt': {'billing'}}
FLAGS = ('superuser','bypassRls','createRole','createDb','replication')
CRUD = ('runtimeSelect','runtimeInsert','runtimeUpdate','runtimeDelete')
ALL_SCHEMAS = set.union(*SCHEMAS.values())

def verify(role, data):
    unsafe = []
    def check(condition, name):
        if not condition: unsafe.append(name)
    check(data.get('currentRole') == role, 'currentRole')
    check(data.get('sessionRole') == role, 'sessionRole')
    check(data.get('ssl') is True, 'TLS')
    roles = data.get('roles', [])
    matches = [r for r in roles if r.get('name') == role]
    check(len(matches) == 1, 'roleMetadata')
    if len(matches) == 1:
        check(matches[0].get('canLogin') is True, 'runtimeLogin')
        for flag in FLAGS: check(matches[0].get(flag) is False, 'roleFlag:' + flag)
    reachable = data.get('reachableRoles')
    check(isinstance(reachable,list) and len(reachable) == 1 and reachable[0].get('name') == role, 'reachableRoles')
    for r in reachable or []:
        for flag in FLAGS: check(r.get(flag) is False, 'reachableFlag:' + flag)
    check(data.get('memberships') == [], 'memberships')
    check(data.get('ownedDatabases') == [], 'ownedDatabases')
    check(data.get('ownedSchemas') == [], 'ownedSchemas')
    policies = data.get('policies')
    check(isinstance(policies,list), 'policyMetadata')
    policies = policies if isinstance(policies,list) else []
    fingerprints = []
    for policy in policies:
        identifier = str(policy.get('schema')) + '.' + str(policy.get('table'))
        check(policy.get('schema') in ALL_SCHEMAS, 'unexpectedPolicy:' + identifier)
        check(isinstance(policy.get('roles'),list) and bool(policy['roles']), 'policyRoles:' + identifier)
        if policy.get('schema') in SCHEMAS[role]:
            fingerprint = hashlib.sha256(json.dumps({k:policy.get(k) for k in ('schema','table','name','cmd','permissive','roles','qual','withCheck')},sort_keys=True,separators=(',',':')).encode()).hexdigest()
            fingerprints.append({'table':identifier,'name':policy.get('name'),'cmd':policy.get('cmd'),'permissive':policy.get('permissive'),'roles':policy.get('roles'),'sha256':fingerprint})
    tables = data.get('tables', [])
    check(isinstance(tables,list) and bool(tables), 'tableMetadata')
    names = set(); owned = 0; rls = 0; foreign = 0; repairs = {}
    counts = {schema: 0 for schema in SCHEMAS[role]}
    for table in tables:
        schema, name = table.get('schema'), table.get('table')
        if not isinstance(schema,str) or not isinstance(name,str): unsafe.append('invalidTableName'); continue
        identifier = schema + '.' + name
        check(identifier not in names, 'duplicate:' + identifier); names.add(identifier)
        check(schema in ALL_SCHEMAS, 'unexpectedSchema:' + identifier)
        check(table.get('owner') not in SCHEMAS, 'runtimeOwner:' + identifier)
        for flag in CRUD: check(isinstance(table.get(flag),bool), 'missingPrivilege:' + identifier)
        for flag in ('rls','forced'): check(isinstance(table.get(flag),bool), 'missingRls:' + identifier)
        check(type(table.get('policyCount')) is int and table['policyCount'] >= 0, 'missingPolicyCount:' + identifier)
        relevant = [p for p in policies if p.get('schema') == schema and p.get('table') == name]
        applicable = [p for p in relevant if 'public' in p.get('roles',[]) or role in p.get('roles',[])]
        check(table.get('policyCount') == len(relevant), 'policyCountMismatch:' + identifier)
        check(type(table.get('runtimePolicyCount')) is int and table['runtimePolicyCount'] == len(applicable), 'runtimePolicyCountMismatch:' + identifier)
        if schema in SCHEMAS[role]:
            owned += 1; counts[schema] += 1
            if table.get('rls'):
                rls += 1; check(table.get('forced') is True, 'unforced:' + identifier)
                check(type(table.get('policyCount')) is int and table['policyCount'] >= 1, 'missingPolicy:' + identifier)
                if any(table.get(flag) for flag in CRUD):
                    check(bool(applicable), 'noApplicablePolicy:' + identifier)
                    for flag, command in zip(CRUD,('SELECT','INSERT','UPDATE','DELETE')):
                        if table.get(flag): check(any(p.get('cmd') in ('ALL',command) for p in applicable), 'noCommandPolicy:' + identifier + ':' + command)
        else:
            foreign += 1; check(not any(table.get(flag) for flag in CRUD), 'foreignCrud:' + identifier)
        if role == 'ims_school_core_rt' and identifier in {'student.guardian_safe_create_repair_runs','student.guardian_safe_create_repair_actions'}:
            repairs[identifier] = table
            check(table.get('rls') is True and table.get('forced') is True and type(table.get('policyCount')) is int and table['policyCount'] >= 1 and all(table.get(flag) is False for flag in CRUD), 'repairIsolation:' + identifier)
            check(len(relevant) == 1 and relevant[0].get('name') == 'repair_audit_deny_all' and relevant[0].get('cmd') == 'ALL' and relevant[0].get('roles') == ['public'] and relevant[0].get('permissive') == 'PERMISSIVE' and relevant[0].get('qual') == 'false' and relevant[0].get('withCheck') == 'false', 'repairDenyPolicy:' + identifier)
    for schema, count in counts.items(): check(count > 0, 'missingSchema:' + schema)
    if role == 'ims_school_core_rt': check(len(repairs) == 2, 'missingRepairTables')
    shared = [r for r in roles if r.get('name') == 'app_rt']
    check(len(shared) == 1 and isinstance(shared[0].get('canLogin'),bool), 'sharedRoleMetadata')
    shared_login = shared[0].get('canLogin') if len(shared) == 1 else None
    check(shared_login is False, 'app_rtLoginStillEnabled')
    return {'role':role, 'safe':not unsafe, 'ownedSchemaTableCounts':counts, 'ownedSchemaTables':owned, 'ownedSchemaRlsTables':rls, 'foreignTablesChecked':foreign, 'catalogTablesChecked':len(tables), 'repairAuditTablesChecked':len(repairs), 'ownedPolicyCount':len(fingerprints),'policyFingerprints':sorted(fingerprints,key=lambda p:(p['table'],p['name'])), 'appRtCanLogin':shared_login, 'unsafeNames':sorted(set(unsafe))}

def timestamp(value):
    parsed = datetime.datetime.fromisoformat(value.replace('Z','+00:00'))
    if parsed.tzinfo is None: raise ValueError('Timestamp must include timezone')
    return parsed

def load_capture(directory, not_before):
    root = pathlib.Path(directory)
    summary = json.loads((root/'summary.json').read_text(encoding='utf-8-sig'))
    if summary.get('readOnly') is not True or summary.get('project') != 'custoking-dev' or timestamp(summary['capturedAtUtc']) < not_before: raise ValueError('Capture provenance/time rejected')
    logs = json.loads((root/'logs.json').read_text(encoding='utf-8-sig'))
    payloads = [entry['jsonPayload'] for entry in logs if isinstance(entry.get('jsonPayload'),dict) and 'currentRole' in entry['jsonPayload']]
    if len(payloads) != 1: raise ValueError('Expected one structured catalog payload')
    return payloads[0]

def self_test():
    def fixture(role):
        tables = [{'schema':s,'table':'fixture','owner':'owner','rls':True,'forced':True,'policyCount':1,**{k:False for k in CRUD}} for s in SCHEMAS[role]]
        if role == 'ims_school_core_rt':
            for name in ('guardian_safe_create_repair_runs','guardian_safe_create_repair_actions'): tables.append({'schema':'student','table':name,'owner':'owner','rls':True,'forced':True,'policyCount':1,**{k:False for k in CRUD}})
        policies=[]
        for table in tables:
            table['runtimePolicyCount']=1
            repair=table['table'].startswith('guardian_safe_create_repair_')
            policies.append({'schema':table['schema'],'table':table['table'],'name':'repair_audit_deny_all' if repair else 'tenant_isolation','cmd':'ALL','permissive':'PERMISSIVE','roles':['public'],'qual':'false' if repair else "school_id = nullif(current_setting('app.current_school_id', true), '')::bigint",'withCheck':'false' if repair else None})
        return {'currentRole':role,'sessionRole':role,'ssl':True,'memberships':[],'ownedDatabases':[],'ownedSchemas':[],'reachableRoles':[{'name':role,**dict.fromkeys(FLAGS,False)}],'roles':[{'name':role,'canLogin':True,**dict.fromkeys(FLAGS,False)},{'name':'app_rt','canLogin':False}], 'tables':tables,'policies':policies}
    for role in SCHEMAS: assert verify(role,fixture(role))['safe']
    role='ims_school_core_rt'
    for key,value in [('ssl',False),('sessionRole','owner'),('ownedSchemas',['public']),('memberships',[{'member':role,'role':'owner'}])]:
        data=fixture(role); data[key]=value; assert not verify(role,data)['safe']
    for key,value in [('forced',False),('policyCount',0),('owner',role)]:
        data=fixture(role); data['tables'][0][key]=value; assert not verify(role,data)['safe']
    data=fixture(role); data['tables'][-1]['runtimeSelect']=True; assert not verify(role,data)['safe']
    data=fixture(role); data['tables'].append({'schema':'identity','table':'foreign', 'owner':'owner','rls':False,'forced':False,'policyCount':0,**dict.fromkeys(CRUD,True)}); assert not verify(role,data)['safe']
    data=fixture(role); data['roles'][-1]['canLogin']=True; assert not verify(role,data)['safe']
    data=fixture(role); data['policies'][-1]['qual']='true'; assert not verify(role,data)['safe']
    data=fixture(role); data['tables'][0]['runtimeSelect']=True; data['tables'][0]['runtimePolicyCount']=0; data['policies'][0]['roles']=['another_role']; assert not verify(role,data)['safe']
    print('Offline collator controlled fixtures passed; no live proof written.')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--capture',action='append',default=[]); parser.add_argument('--not-before'); parser.add_argument('--output'); parser.add_argument('--self-test',action='store_true'); args=parser.parse_args()
    if args.self_test: self_test(); sys.exit(0)
    if not args.not_before or not args.output: parser.error('--not-before and --output required')
    captures={}
    for item in args.capture:
        role, path=item.split('=',1)
        if role not in SCHEMAS or role in captures: parser.error('Unexpected or duplicate role')
        captures[role]=path
    if set(captures) != set(SCHEMAS): parser.error('Exactly five dedicated role captures required')
    results=[verify(role,load_capture(captures[role],timestamp(args.not_before))) for role in SCHEMAS]
    result={'scope':'Dev postcutover catalog-only dedicated runtime roles','notBefore':args.not_before,'capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'safe':all(r['safe'] for r in results),'roles':results,'appRtNoLoginVerified':all(r['appRtCanLogin'] is False for r in results)}
    pathlib.Path(args.output).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'safe':result['safe'],'roles':len(results),'appRtNoLoginVerified':result['appRtNoLoginVerified']})); sys.exit(0 if result['safe'] else 1)
