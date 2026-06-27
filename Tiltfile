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
    'tenant-school-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8084/actuator/health'],
)

dc_resource(
    'student-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8085/actuator/health'],
)

dc_resource(
    'attendance-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8086/actuator/health'],
)

dc_resource(
    'fee-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8087/actuator/health'],
)

local_resource(
    'local-dev-users',
    cmd='powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\ensure-local-dev-users.ps1',
    resource_deps=['identity-service', 'tenant-school-service'],
    labels=['setup'],
)

full_only_services = {
    'notification-service': 'http://localhost:8081/actuator/health',
    'audit-service': 'http://localhost:8082/actuator/health',
    'catalog-service': 'http://localhost:8088/actuator/health',
    'workflow-service': 'http://localhost:8089/actuator/health',
    'firefighting-service': 'http://localhost:8090/actuator/health',
    'reporting-service': 'http://localhost:8091/actuator/health',
    'billing-service': 'http://localhost:8092/actuator/health',
}
full_only_service_names = [
    'notification-service',
    'audit-service',
    'catalog-service',
    'workflow-service',
    'firefighting-service',
    'reporting-service',
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

gateway_deps = ['local-dev-users', 'identity-service', 'tenant-school-service', 'student-service', 'attendance-service', 'fee-service']
if profile == 'full':
    gateway_deps += full_only_service_names

dc_resource(
    'api-gateway',
    labels=['app', 'gateway'],
    resource_deps=gateway_deps,
    links=['http://localhost'],
)
