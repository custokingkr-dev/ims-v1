function Get-MicroserviceBuildCatalog {
    return @(
        @{ Name = "identity-service"; Context = "services/identity-service"; Image = "custoking-identity-service" },
        @{ Name = "school-core-service"; Context = "services/school-core-service"; Image = "custoking-school-core-service" },
        @{ Name = "operations-service"; Context = "services/operations-service"; Image = "custoking-operations-service" },
        @{ Name = "platform-service"; Context = "services/platform-service"; Image = "custoking-platform-service" },
        @{ Name = "billing-service"; Context = "services/billing-service"; Image = "custoking-billing-service" },
        @{ Name = "frontend"; Context = "frontend"; Image = "custoking-frontend"; BuildArgs = @("VITE_API_BASE_URL=/api/v1") },
        @{ Name = "api-gateway"; Context = "services/api-gateway"; Image = "custoking-api-gateway" }
    )
}
