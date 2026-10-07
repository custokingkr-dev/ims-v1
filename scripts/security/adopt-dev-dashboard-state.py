"""Adopt existing dev dashboard security resources; never apply infrastructure changes."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "deploy/gcp/observability"
PROJECT = "custoking-dev"
BUCKET = "custoking-dev-terraform-state"
PREFIX = "observability/dev"
ACCOUNT = "ims-dashboard@custoking-dev.iam.gserviceaccount.com"
DATABASE = f"projects/{PROJECT}/databases/ims-dashboard-dev"
IMPORTS = {
    "google_service_account.dashboard[0]": f"projects/{PROJECT}/serviceAccounts/{ACCOUNT}",
    "google_project_service.dashboard_firestore[0]": f"{PROJECT}/firestore.googleapis.com",
    "google_firestore_database.dashboard_security[0]": DATABASE,
    "google_firestore_field.dashboard_security_expiry[0]": f"{DATABASE}/collectionGroups/dashboardSecurityState/fields/expiresAt",
    "google_project_iam_custom_role.dashboard_security_state[0]": f"projects/{PROJECT}/roles/dashboardSecurityState_dev",
    "google_project_iam_member.dashboard_security_state[0]": f"{PROJECT} projects/{PROJECT}/roles/dashboardSecurityState_dev serviceAccount:{ACCOUNT} dashboard-named-database-only",
}
VARIABLES = ["-var=project=custoking-dev", "-var=env=dev", "-var=region=asia-south2",
             "-var=enable_dashboard_security_state=true", "-var=enable_shared_dashboard=false",
             "-var=discover_cloud_run_urls=false", "-var=enable_uptime_checks=false"]


def execute(args, *, env=None, timeout=180):
    result = subprocess.run(args, capture_output=True, text=True, env=env, timeout=timeout)
    if result.returncode:
        # CLI output can contain private state or credentials. Never echo it by default.
        diagnostic = result.stderr
        if env and env.get("GOOGLE_OAUTH_ACCESS_TOKEN"):
            diagnostic = diagnostic.replace(env["GOOGLE_OAUTH_ACCESS_TOKEN"], "[redacted]")
        (ROOT / "tmp").mkdir(exist_ok=True)
        (ROOT / "tmp/dashboard-adoption-error-private.txt").write_text(diagnostic, encoding="utf-8")
        raise RuntimeError(f"{Path(args[0]).name} {args[1]} failed (exit {result.returncode})")
    return result.stdout


def cloud(*args):
    return json.loads(execute(["gcloud.cmd" if os.name == "nt" else "gcloud", *args,
                               f"--project={PROJECT}", "--format=json"]))


def state_resources(state):
    rows = {}
    for resource in state.get("resources", []):
        if resource.get("mode", "managed") != "managed":
            continue
        base = resource["type"] + "." + resource["name"]
        if resource.get("module"):
            base = resource["module"] + "." + base
        for instance in resource.get("instances", []):
            suffix = "[" + json.dumps(instance["index_key"]) + "]" if "index_key" in instance else ""
            rows[base + suffix] = instance.get("attributes", {})
    return rows


def existing_identity(address, attrs):
    expected = IMPORTS[address]
    if address.startswith("google_project_iam_member"):
        return (attrs.get("project") == PROJECT and attrs.get("role") == f"projects/{PROJECT}/roles/dashboardSecurityState_dev"
                and attrs.get("member") == f"serviceAccount:{ACCOUNT}"
                and attrs.get("condition", [{}])[0].get("title") == "dashboard-named-database-only"
                and attrs.get("condition", [{}])[0].get("expression") == f"resource.name == '{DATABASE}'")
    return attrs.get("id") == expected


def read_state():
    return json.loads(execute(["gcloud.cmd" if os.name == "nt" else "gcloud", "storage", "cat",
                              f"gs://{BUCKET}/{PREFIX}/default.tfstate", f"--project={PROJECT}"]))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--import-dev", action="store_true", help="Write only six exact existing identities to the dev Terraform state")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/security-remediation/acceptance-dashboard-state-adoption.json")
    args = parser.parse_args()
    database = cloud("firestore", "databases", "describe", "--database=ims-dashboard-dev")
    role = cloud("iam", "roles", "describe", "dashboardSecurityState_dev")
    fields = cloud("firestore", "fields", "ttls", "list", "--database=ims-dashboard-dev", "--collection-group=dashboardSecurityState")
    field = next(row for row in fields if row["name"] == IMPORTS["google_firestore_field.dashboard_security_expiry[0]"])
    account = cloud("iam", "service-accounts", "describe", ACCOUNT)
    policy = cloud("projects", "get-iam-policy", PROJECT)
    require(database["name"] == DATABASE and database["type"] == "FIRESTORE_NATIVE", "Unexpected dashboard database identity/type")
    require(database["locationId"] == "asia-south2" and database["deleteProtectionState"] == "DELETE_PROTECTION_ENABLED", "Database region/protection must remain exact")
    require(set(role["includedPermissions"]) == {"datastore.entities.get", "datastore.entities.create"}, "Unexpected dashboard role permissions")
    require(not account.get("disabled", False), "Dashboard account is disabled")
    require(field["ttlConfig"]["state"] == "ACTIVE", "TTL policy is not ACTIVE")
    expected_condition = f"resource.name == '{DATABASE}'"
    bindings = [b for b in policy["bindings"] if b["role"] == f"projects/{PROJECT}/roles/dashboardSecurityState_dev"
                and f"serviceAccount:{ACCOUNT}" in b.get("members", [])]
    require(len(bindings) == 1 and bindings[0]["condition"]["expression"] == expected_condition, "Unexpected database IAM condition")
    require(bindings[0]["condition"]["title"] == "dashboard-named-database-only", "Unexpected IAM condition identity")
    before = read_state()
    original = state_resources(before)
    for address in IMPORTS:
        if address in original and not existing_identity(address, original[address]):
            raise RuntimeError(f"Refusing to replace a different existing state identity at {address}")
    proof = {"schemaVersion": 1, "project": PROJECT, "mode": "IMPORT_ONLY" if args.import_dev else "READ_ONLY",
             "backend": {"bucket": BUCKET, "prefix": PREFIX}, "lineage": before["lineage"],
             "serialBefore": before["serial"], "databaseProtection": database["deleteProtectionState"],
             "ttlState": field["ttlConfig"]["state"], "permissions": sorted(role["includedPermissions"]),
             "condition": expected_condition, "webDeploymentEnabled": False, "infrastructureApplyPerformed": False,
             "imports": [], "status": "PREFLIGHT_PASS"}
    def save():
        proof["checkedAtUtc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    save()
    if not args.import_dev:
        print(json.dumps(proof))
        return
    proof["status"] = "IMPORT_IN_PROGRESS"
    save()
    gcloud = "gcloud.cmd" if os.name == "nt" else "gcloud"
    token = execute([gcloud, "auth", "print-access-token", f"--project={PROJECT}"]).strip()
    env = os.environ.copy()
    env["GOOGLE_OAUTH_ACCESS_TOKEN"] = token
    task_parent = (ROOT / "tmp").resolve()
    task_parent.mkdir(exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix="dashboard-state-adoption-", dir=task_parent)).resolve()
    # The only recursive cleanup target is a fresh, verified directory under workspace/tmp.
    require(temp.parent == task_parent and temp.name.startswith("dashboard-state-adoption-"), "Temporary cleanup target must remain within workspace/tmp")
    env["TF_DATA_DIR"] = str(temp / "terraform-data")
    tf = shutil.which("terraform")
    if not tf:
        raise RuntimeError("Terraform is required")
    backend = temp / "backend.hcl"
    backend.write_text(f'bucket = "{BUCKET}"\nprefix = "{PREFIX}"\naccess_token = "{token}"\n', encoding="utf-8")
    try:
        execute([tf, f"-chdir={MODULE}", "init", "-reconfigure", "-input=false", "-lockfile=readonly", f"-backend-config={backend}"], env=env)
        for address, identity in IMPORTS.items():
            if address not in state_resources(read_state()):
                execute([tf, f"-chdir={MODULE}", "import", "-input=false", "-lock-timeout=30s", *VARIABLES, address, identity], env=env)
            current = read_state()
            require(current["lineage"] == before["lineage"], "State lineage changed concurrently")
            require(existing_identity(address, state_resources(current)[address]), "Imported identity does not match")
            proof["imports"].append({"address": address, "identityVerified": True})
            proof["serialAfter"] = current["serial"]
            save()
        after = read_state()
        current = state_resources(after)
        require(set(current) == set(original) | set(IMPORTS), "Unexpected concurrent state membership change")
        # Imports must not edit unrelated state entries (including existing prod-reader IAM).
        require(all(current[address] == attrs for address, attrs in original.items() if address not in IMPORTS), "Unexpected unrelated managed state change")
        proof["unrelatedManagedStateUnchanged"] = True
        plan = temp / "adoption-plan.tfplan"
        execute([tf, f"-chdir={MODULE}", "plan", "-input=false", "-refresh=false", "-lock-timeout=30s", *VARIABLES,
                 *[f"-target={address}" for address in IMPORTS], f"-out={plan}"], env=env)
        plan_json = json.loads(execute([tf, f"-chdir={MODULE}", "show", "-json", str(plan)], env=env))
        changes = []
        for row in plan_json.get("resource_changes", []):
            if row["mode"] != "managed":
                continue
            before_attrs = row["change"].get("before") or {}
            after_attrs = row["change"].get("after") or {}
            changed_keys = sorted(key for key in set(before_attrs) | set(after_attrs)
                                  if before_attrs.get(key) != after_attrs.get(key))
            changes.append({"address": row["address"], "actions": row["change"]["actions"], "changedFields": changed_keys})
        proof["targetedPlanChanges"] = changes
        proof["noCreateDeleteReplacePlanned"] = all(not ({"create", "delete"} & set(row["actions"])) for row in changes)
        proof["targetedPlanAllNoOp"] = all(row["actions"] == ["no-op"] for row in changes)
        proof["onlyDescriptionDisplayNameDrift"] = all(set(row["changedFields"]) <= {"description", "display_name"} for row in changes)
        require(proof["noCreateDeleteReplacePlanned"], "Targeted adoption plan proposed creation/deletion/replacement")
        proof["status"] = "IMPORTED_VERIFIED" if proof["targetedPlanAllNoOp"] else (
            "IMPORTED_VERIFIED_METADATA_ONLY" if proof["onlyDescriptionDisplayNameDrift"] else "IMPORTED_REVIEW_CONFIGURATION_DRIFT")
        save()
    except BaseException:
        proof["status"] = "INCOMPLETE_REVIEW_CHECKPOINT"
        save()
        raise
    finally:
        shutil.rmtree(temp)
        proof["temporaryCredentialAndPlanDirectoryAbsent"] = not temp.exists()
        save()
    print(json.dumps(proof))


if __name__ == "__main__":
    main()
