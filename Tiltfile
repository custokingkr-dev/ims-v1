profile = os.getenv('TILT_COMPOSE_PROFILE', 'full')
if profile not in ['core', 'full']:
    fail("profile must be 'core' or 'full'")

docker_compose('docker-compose.yml', profiles=[profile])

dc_resource(
    'postgres',
    labels=['infra'],
)

dc_resource(
    'identity-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8083/actuator/health'],
)

dc_resource(
        'school-core-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8084/actuator/health'],
)

dc_resource(
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8085/actuator/health'],
)

dc_resource(
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8086/actuator/health'],
)

dc_resource(
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8087/actuator/health'],
)

local_resource(
    'local-dev-users',
    cmd='powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\ensure-local-dev-users.ps1',
    resource_deps=['identity-service', 'school-core-service'],
    labels=['setup'],
)

full_only_services = {
    'platform-service': 'http://localhost:8091/actuator/health',
    'school-core-service': 'http://localhost:8084/actuator/health',
    'operations-service': 'http://localhost:8089/actuator/health',
    'billing-service': 'http://localhost:8092/actuator/health',
}
full_only_service_names = [
        'platform-service',
    'operations-service',
    'billing-service',
]

if profile == 'full':
    for name in full_only_service_names:
        dc_resource(
            name,
            labels=['app', 'microservice'],
            resource_deps=['postgres'],
            links=[full_only_services[name]],
        )

dc_resource(
    'frontend',
    labels=['app'],
    resource_deps=[],
)

gateway_deps = ['local-dev-users', 'identity-service', 'school-core-service']
if profile == 'full':
    gateway_deps += full_only_service_names

dc_resource(
    'api-gateway',
    labels=['app', 'gateway'],
    resource_deps=gateway_deps,
    links=['http://localhost'],
)
