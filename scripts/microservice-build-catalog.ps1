function Get-MicroserviceBuildCatalog {
    return @(
        @{ Name = "identity-service"; Context = "services/identity-service"; BuildContext = "."; Dockerfile = "services/identity-service/Dockerfile"; SourcePaths = @("pom.xml", "services/identity-service"); Image = "custoking-identity-service" },
        @{ Name = "school-core-service"; Context = "services/school-core-service"; BuildContext = "."; Dockerfile = "services/school-core-service/Dockerfile"; SourcePaths = @("pom.xml", "services/school-core-service"); Image = "custoking-school-core-service" },
        @{ Name = "operations-service"; Context = "services/operations-service"; BuildContext = "."; Dockerfile = "services/operations-service/Dockerfile"; SourcePaths = @("pom.xml", "services/operations-service"); Image = "custoking-operations-service" },
        @{ Name = "platform-service"; Context = "services/platform-service"; BuildContext = "."; Dockerfile = "services/platform-service/Dockerfile"; SourcePaths = @("pom.xml", "services/platform-service"); Image = "custoking-platform-service" },
        @{ Name = "billing-service"; Context = "services/billing-service"; BuildContext = "."; Dockerfile = "services/billing-service/Dockerfile"; SourcePaths = @("pom.xml", "services/billing-service"); Image = "custoking-billing-service" },
        @{ Name = "frontend"; Context = "frontend"; Image = "custoking-frontend"; BuildArgs = @("VITE_API_BASE_URL=/api/v1") },
        @{ Name = "api-gateway"; Context = "services/api-gateway"; Image = "custoking-api-gateway" }
    )
}
