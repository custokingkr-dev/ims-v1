function Get-MicroserviceTestCatalog {
    return @(
        @{ Name = "identity-service"; Path = "services/identity-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "school-core-service"; Path = "services/school-core-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "operations-service"; Path = "services/operations-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "platform-service"; Path = "services/platform-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "billing-service"; Path = "services/billing-service"; Tool = "maven"; Command = @("mvn", "-B", "test", "--no-transfer-progress") },
        @{ Name = "api-gateway"; Path = "services/api-gateway"; Tool = "node"; Command = @("node", "--test", "server.test.js") },
        @{ Name = "frontend"; Path = "frontend"; Tool = "npm"; Command = @("npm", "test") }
    )
}
