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
    'frontend',
    labels=['app'],
    resource_deps=['backend'],
    links=['http://localhost'],
)
