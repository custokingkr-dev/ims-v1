function Get-MicroserviceBuildCatalog {
    return @(
        @{ Name = "identity-service"; Context = "services/identity-service"; Image = "custoking-identity-service" },
        @{ Name = "tenant-school-service"; Context = "services/tenant-school-service"; Image = "custoking-tenant-school-service" },
        @{ Name = "student-service"; Context = "services/student-service"; Image = "custoking-student-service" },
        @{ Name = "attendance-service"; Context = "services/attendance-service"; Image = "custoking-attendance-service" },
        @{ Name = "fee-service"; Context = "services/fee-service"; Image = "custoking-fee-service" },
        @{ Name = "catalog-service"; Context = "services/catalog-service"; Image = "custoking-catalog-service" },
        @{ Name = "operations-service"; Context = "services/operations-service"; Image = "custoking-operations-service" },
        @{ Name = "platform-service"; Context = "services/platform-service"; Image = "custoking-platform-service" },
        @{ Name = "billing-service"; Context = "services/billing-service"; Image = "custoking-billing-service" },
        @{ Name = "frontend"; Context = "frontend"; Image = "custoking-frontend"; BuildArgs = @("VITE_API_BASE_URL=/api/v1") },
        @{ Name = "api-gateway"; Context = "services/api-gateway"; Image = "custoking-api-gateway" }
    )
}
