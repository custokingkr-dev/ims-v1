function Get-CloudRunIamCatalog {
    @(
        [pscustomobject]@{ Name = "identity-service"; CloudRunService = "custoking-identity-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "tenant-school-service"; CloudRunService = "custoking-tenant-school-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "identity-service") }
        [pscustomobject]@{ Name = "student-service"; CloudRunService = "custoking-student-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "attendance-service"; CloudRunService = "custoking-attendance-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "fee-service"; CloudRunService = "custoking-fee-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "catalog-service"; CloudRunService = "custoking-catalog-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "operations-service"; CloudRunService = "custoking-operations-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "platform-service"; CloudRunService = "custoking-platform-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "pubsubPush") }
        [pscustomobject]@{ Name = "billing-service"; CloudRunService = "custoking-billing-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "frontend"; CloudRunService = "custoking-frontend"; Exposure = "public"; RequiredInvokers = @("allUsers") }
        [pscustomobject]@{ Name = "api-gateway"; CloudRunService = "custoking-api-gateway"; Exposure = "public"; RequiredInvokers = @("allUsers") }
    )
}
