function Get-CloudRunIamCatalog {
    @(
        [pscustomobject]@{ Name = "identity-service"; CloudRunService = "custoking-identity-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "tenant-school-service"; CloudRunService = "custoking-tenant-school-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "identity-service") }
        [pscustomobject]@{ Name = "student-service"; CloudRunService = "custoking-student-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "attendance-service"; CloudRunService = "custoking-attendance-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "fee-service"; CloudRunService = "custoking-fee-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "catalog-service"; CloudRunService = "custoking-catalog-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "workflow-service"; CloudRunService = "custoking-workflow-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "firefighting-service"; CloudRunService = "custoking-firefighting-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "reporting-service"; CloudRunService = "custoking-reporting-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "billing-service"; CloudRunService = "custoking-billing-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "audit-service"; CloudRunService = "custoking-audit-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "notification-service"; CloudRunService = "custoking-notification-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "pubsubPush") }
        [pscustomobject]@{ Name = "frontend"; CloudRunService = "custoking-frontend"; Exposure = "public"; RequiredInvokers = @("allUsers") }
        [pscustomobject]@{ Name = "api-gateway"; CloudRunService = "custoking-api-gateway"; Exposure = "public"; RequiredInvokers = @("allUsers") }
    )
}
