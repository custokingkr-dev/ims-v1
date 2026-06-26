function Get-MicroserviceTestCatalog {
    return @(
        @{ Name = "identity-service"; Path = "services/identity-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "tenant-school-service"; Path = "services/tenant-school-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "student-service"; Path = "services/student-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "attendance-service"; Path = "services/attendance-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "fee-service"; Path = "services/fee-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "catalog-service"; Path = "services/catalog-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "workflow-service"; Path = "services/workflow-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "firefighting-service"; Path = "services/firefighting-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "reporting-service"; Path = "services/reporting-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "billing-service"; Path = "services/billing-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "audit-service"; Path = "services/audit-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "notification-service"; Path = "services/notification-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "frontend"; Path = "frontend"; Tool = "npm"; Command = @("npm", "test") }
    )
}
