profile = os.getenv('TILT_COMPOSE_PROFILE', 'full')
if profile not in ['core', 'full']:
    fail("profile must be 'core' or 'full'")

docker_compose('docker-compose.yml', profiles=[profile])

dc_resource(
    'postgres',
    labels=['infra'],
)

core_services = {
    'identity-service': 'http://localhost:8083/actuator/health',
    'school-core-service': 'http://localhost:8084/actuator/health',
}

for name, link in core_services.items():
    dc_resource(
        name,
        labels=['app', 'microservice'],
        resource_deps=['postgres'],
        links=[link],
    )

full_only_services = {
    'operations-service': 'http://localhost:8089/actuator/health',
    'platform-service': 'http://localhost:8091/actuator/health',
    'billing-service': 'http://localhost:8092/actuator/health',
}

grant_schemas = 'identity,tenant_school,student,attendance,fee,catalog'
grant_deps = ['identity-service', 'school-core-service']

if profile == 'full':
    for name, link in full_only_services.items():
        dc_resource(
            name,
            labels=['app', 'microservice'],
            resource_deps=['postgres'],
            links=[link],
        )
    grant_schemas = 'identity,tenant_school,student,attendance,fee,catalog,workflow,firefighting,reporting,notification,audit,billing'
    grant_deps += list(full_only_services.keys())

local_resource(
    'local-runtime-grants',
    cmd='powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\ensure-app-rt-local.ps1 -RequiredSchemas ' + grant_schemas,
    resource_deps=grant_deps,
    labels=['setup'],
)

local_resource(
    'local-dev-users',
    cmd='powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\ensure-local-dev-users.ps1',
    resource_deps=['local-runtime-grants'],
    labels=['setup'],
)

dc_resource(
    'frontend',
    labels=['app'],
    resource_deps=[],
)

gateway_deps = ['local-dev-users', 'identity-service', 'school-core-service']
if profile == 'full':
    gateway_deps += list(full_only_services.keys())

dc_resource(
    'api-gateway',
    labels=['app', 'gateway'],
    resource_deps=gateway_deps,
    links=['http://localhost', 'http://localhost/gateway-health'],
)
