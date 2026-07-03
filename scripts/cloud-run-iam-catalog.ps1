function Get-CloudRunIamCatalog {
    @(
        [pscustomobject]@{ Name = "identity-service"; CloudRunService = "custoking-identity-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "school-core-service"; CloudRunService = "custoking-school-core-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "identity-service") }
        [pscustomobject]@{ Name = "operations-service"; CloudRunService = "custoking-operations-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "platform-service"; CloudRunService = "custoking-platform-service"; Exposure = "private"; RequiredInvokers = @("api-gateway", "pubsubPush") }
        [pscustomobject]@{ Name = "billing-service"; CloudRunService = "custoking-billing-service"; Exposure = "private"; RequiredInvokers = @("api-gateway") }
        [pscustomobject]@{ Name = "frontend"; CloudRunService = "custoking-frontend"; Exposure = "public"; RequiredInvokers = @("allUsers") }
        [pscustomobject]@{ Name = "api-gateway"; CloudRunService = "custoking-api-gateway"; Exposure = "public"; RequiredInvokers = @("allUsers") }
    )
}
