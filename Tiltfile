docker_compose('docker-compose.yml')

dc_resource(
    'postgres',
    labels=['infra'],
)

dc_resource(
    'backend',
    labels=['app'],
    resource_deps=['postgres'],
    links=['http://localhost:8080/actuator/health'],
)

dc_resource(
    'notification-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8081/actuator/health'],
)

dc_resource(
    'audit-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8082/actuator/health'],
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

dc_resource(
    'catalog-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8088/actuator/health'],
)

dc_resource(
    'workflow-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8089/actuator/health'],
)

dc_resource(
    'firefighting-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8090/actuator/health'],
)

dc_resource(
    'reporting-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8091/actuator/health'],
)

dc_resource(
    'billing-service',
    labels=['app', 'microservice'],
    resource_deps=['postgres'],
    links=['http://localhost:8092/actuator/health'],
)

dc_resource(
    'frontend',
    labels=['app'],
    resource_deps=[],
)

dc_resource(
    'api-gateway',
    labels=['app', 'gateway'],
    resource_deps=['frontend', 'backend', 'notification-service', 'audit-service', 'identity-service', 'tenant-school-service', 'student-service', 'attendance-service', 'fee-service', 'catalog-service', 'workflow-service', 'firefighting-service', 'reporting-service', 'billing-service'],
    links=['http://localhost'],
)
