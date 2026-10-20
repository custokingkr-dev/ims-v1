# Dev application edge

This separate module provisions a dev HTTPS load balancer with frontend/API routing, Cloud Armor WAF rules, a coarse per-IP flood budget and TLS 1.2 minimum. It targets only `custoking-dev` in `asia-south2`. It does not own existing Cloud Run images, IAM, environments or traffic. No resources have been applied by adding this module.

An explicit owned DNS hostname is required; there is no shared wildcard-domain fallback. Keep Terraform state in an approved separate state backend. Validate the plan and costs against the existing dev budget before applying. The WAF uses sensitivity one; verify legitimate JSON, multipart imports and shared-school NAT traffic before cutover. Cloud Armor rate limits are per backend and region and supplement the gateway's verified-user quotas.

The WAF inspection window is explicitly 64KB, the supported maximum. Larger uploads remain subject to the application's parser, expansion and size controls; the edge does not inspect their entire body. Do not claim full-body WAF coverage or add a blanket 64KB upload limit that breaks legitimate imports. Match any reviewed exceptions to a precise route and retain application authorization and parser limits.

```powershell
terraform init -backend=false
terraform validate
terraform plan -var="domain=YOUR-OWNED-DEV-HOSTNAME" -out=dev-edge.tfplan
```

After provisioning, set that hostname's DNS A record to the reserved address and wait for the managed certificate to become ACTIVE. Test login/refresh/logout, CSP, previews, uploads and two-school permissions through the edge before closing existing public entry paths. API requests route directly to the gateway; the frontend proxy is not the public edge API route.

Cutover must be a reviewed Cloud Deploy configuration change: set frontend and gateway ingress to `internal-and-cloud-load-balancing`, include only the chosen origin in gateway/identity cookie and CORS allowlists, and select the gateway's trusted proxy-hop count from the actual verified header chain. Internal callers still need an eligible VPC route. Do not change private Java service IAM. Reconcile Cloud Deploy targets so subsequent releases preserve the ingress settings.

WebAuthn RP identifiers require a separate migration check: an existing credential for the old Cloud Run RP cannot simply authenticate under a different owned domain. Inspect only aggregate credential counts, preserve factors and require an authenticated re-enrollment/recovery path if needed. Do not silently disable MFA during domain migration.

Acceptance requires an ACTIVE certificate, expected routing and application behavior, enforced WAF/rate-limit evidence, and external requests to both original public Cloud Run URLs being denied. Provisioning an edge without closing direct ingress leaves a bypass and is not completion. Preserve a tested secure rollback route; do not re-open unrestricted origin ingress merely to hide a failed edge probe.

References: [Google Cloud Armor serverless integration and bypass controls](https://docs.cloud.google.com/armor/docs/integrating-cloud-armor), [Cloud Run ingress and internal routing](https://docs.cloud.google.com/run/docs/securing/ingress), [Cloud Armor rate limiting](https://docs.cloud.google.com/armor/docs/rate-limiting-overview), [preconfigured WAF tuning](https://docs.cloud.google.com/armor/docs/configure-waf).

Current price preparation (2026-10-07) for the checked-in module assumes Cloud Armor Standard, one policy/eight rules, one global forwarding rule and 730 hours/month. With no existing global forwarding rules observed in dev, the approximate fixed baseline is **USD 31.25/month**: forwarding rule 18.25, policy 5.00 and rules 8.00. This is an estimate from the module and published prices, excluding request/data processing, internet transfer, domain/DNS, logs, existing app resources, taxes, contracted discounts and currency conversion. Confirm the applicable billing plan and intended traffic in the calculator before approving a budget. [Google Armor prices](https://cloud.google.com/armor/pricing), [network prices and calculator](https://cloud.google.com/vpc/network-pricing).

[Read-only dev edge preparation](../../../docs/security-remediation/acceptance-dev-edge-owner-preparation.json) found zero global forwarding rules and zero global Compute SSL certificates; Cloud DNS API is disabled. This does not prove hostname ownership or absence of certificates in other systems. No API was enabled and no edge, DNS, ingress, OAuth or budget change was applied. The owned hostname and budget decision remain required.
