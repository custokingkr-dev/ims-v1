function Get-SecretManagerCatalog {
    @(
        [pscustomobject]@{ Name = "ims-app-password"; Purpose = "Backend application database password" }
        [pscustomobject]@{ Name = "db-password"; Purpose = "Flyway and service database password" }
        [pscustomobject]@{ Name = "aadhar-secret"; Purpose = "Aadhaar data encryption/signing secret" }
        [pscustomobject]@{ Name = "jwt-secret"; Purpose = "JWT signing secret shared by backend and identity-service" }
        [pscustomobject]@{ Name = "notification-pubsub-push-token"; Purpose = "Notification Pub/Sub push defense-in-depth token" }
        [pscustomobject]@{ Name = "notification-status-token"; Purpose = "Notification status/read internal token" }
        [pscustomobject]@{ Name = "msg91-auth-key"; Purpose = "MSG91 provider auth key" }
        [pscustomobject]@{ Name = "audit-ingest-token"; Purpose = "Audit service ingest internal token" }
        [pscustomobject]@{ Name = "identity-introspection-token"; Purpose = "Identity service token introspection internal token" }
        [pscustomobject]@{ Name = "tenant-school-read-token"; Purpose = "Tenant school service read internal token" }
        [pscustomobject]@{ Name = "student-read-token"; Purpose = "Student service read internal token" }
        [pscustomobject]@{ Name = "attendance-read-token"; Purpose = "Attendance service read internal token" }
        [pscustomobject]@{ Name = "fee-read-token"; Purpose = "Fee service read internal token" }
        [pscustomobject]@{ Name = "catalog-read-token"; Purpose = "Catalog service read internal token" }
        [pscustomobject]@{ Name = "workflow-read-token"; Purpose = "Workflow service read internal token" }
        [pscustomobject]@{ Name = "firefighting-read-token"; Purpose = "Firefighting service read internal token" }
        [pscustomobject]@{ Name = "reporting-read-token"; Purpose = "Reporting service read internal token" }
        [pscustomobject]@{ Name = "billing-service-token"; Purpose = "Billing service internal token" }
    )
}
