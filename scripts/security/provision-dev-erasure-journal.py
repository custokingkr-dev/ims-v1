"""Review/apply only the dedicated five-resource dev erasure-journal module."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "deploy/gcp/erasure-journal"
PROJECT = "custoking-dev"
BUCKET = "custoking-dev-erasure-journal"
MEMBER = "serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com"
EXPECTED = {
    "google_storage_bucket.journal",
    "google_project_iam_custom_role.writer",
    "google_storage_bucket_iam_member.writer",
    "google_project_iam_custom_role.control_reader",
    "google_storage_bucket_iam_member.control_reader",
}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def review(plan):
    resources = plan.get("resource_changes", [])
    require({r["address"] for r in resources} == EXPECTED and len(resources) == 5,
            "Plan must contain only the exact five dedicated dev resources")
    summary = []
    for r in resources:
        change = r["change"]
        require(change["actions"] in (["create"], ["no-op"]), "Updates, replacement and destruction are not authorized")
        after = change["after"]
        if r["type"] == "google_storage_bucket":
            require(after["project"] == PROJECT and after["name"] == BUCKET
                    and after["location"] == "ASIA-SOUTH2" and after["storage_class"] == "STANDARD"
                    and after["public_access_prevention"] == "enforced" and after["uniform_bucket_level_access"]
                    and not after["force_destroy"] and after["versioning"][0]["enabled"]
                    and not after.get("lifecycle_rule") and not after.get("retention_policy"),
                    "Bucket privacy, scope or retention plan mismatch")
        elif r["type"] == "google_project_iam_custom_role":
            writer = r["address"].endswith(".writer")
            require(after["project"] == PROJECT and after["role_id"] ==
                    ("erasureJournalWriter_dev" if writer else "erasureJournalControlReader_dev")
                    and set(after["permissions"]) == ({"storage.objects.create", "storage.objects.get"} if writer else {"storage.objects.get"}),
                    "Custom permission scope mismatch")
        else:
            writer = r["address"].endswith(".writer")
            prefix = "intents/" if writer else "control/"
            role = "projects/" + PROJECT + "/roles/" + ("erasureJournalWriter_dev" if writer else "erasureJournalControlReader_dev")
            require(after.get("role") == role or (after.get("role") is None and change.get("after_unknown", {}).get("role") is True),
                    "Binding must use only its exact dedicated custom role")
            require(after["bucket"] in (BUCKET, "b/" + BUCKET) and after["member"] == MEMBER
                    and after["condition"][0]["expression"] ==
                    "resource.name.startsWith('projects/_/buckets/" + BUCKET + "/objects/" + prefix + "')",
                    "Runtime binding scope mismatch")
        summary.append({"address": r["address"], "actions": change["actions"]})
    return summary


def execute(args, env=None):
    result = subprocess.run(args, capture_output=True, text=True, env=env, timeout=180)
    if result.returncode:
        diagnostic = result.stdout + "\n" + result.stderr
        if env and env.get("GOOGLE_OAUTH_ACCESS_TOKEN"):
            diagnostic = diagnostic.replace(env["GOOGLE_OAUTH_ACCESS_TOKEN"], "[redacted]")
        (ROOT / "tmp/dev-erasure-journal-diagnostic-private.txt").write_text(diagnostic,encoding="utf-8")
    require(result.returncode == 0, "Exact journal metadata/plan operation failed; private output withheld")
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply-dev", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "tmp/dev-erasure-journal-plan.json")
    args = parser.parse_args()
    tf = shutil.which("terraform")
    require(tf is not None, "Terraform is required")
    cloud = "gcloud.cmd" if os.name == "nt" else "gcloud"
    token = execute([cloud, "auth", "print-access-token", "--project=" + PROJECT]).strip()
    env = os.environ.copy()
    env["GOOGLE_OAUTH_ACCESS_TOKEN"] = token
    parent = (ROOT / "tmp").resolve()
    parent.mkdir(exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="dev-erasure-journal-", dir=parent)).resolve()
    require(temporary.parent == parent and temporary.name.startswith("dev-erasure-journal-"), "Temporary cleanup must remain in workspace/tmp")
    env["TF_DATA_DIR"] = str(temporary / "terraform-data")
    proof = {"project": PROJECT, "backend": {"bucket": "custoking-dev-terraform-state", "prefix": "security/erasure-journal/dev"},
             "applied": False, "cloudRunChanged": False, "productionChanged": False, "controlObjectCreated": False}
    try:
        backend = temporary / "backend.hcl"
        backend.write_text('bucket = "custoking-dev-terraform-state"\nprefix = "security/erasure-journal/dev"\naccess_token = "' + token + '"\n', encoding="utf-8")
        base = [tf, "-chdir=" + str(MODULE)]
        execute(base + ["init", "-reconfigure", "-input=false", "-lockfile=readonly", "-backend-config=" + str(backend)], env)
        planfile = temporary / "reviewed.tfplan"
        execute(base + ["plan", "-input=false", "-lock-timeout=30s", "-out=" + str(planfile)], env)
        plan = json.loads(execute(base + ["show", "-json", str(planfile)], env))
        proof["reviewedChanges"] = review(plan)
        proof["configurationSha256"] = hashlib.sha256((MODULE / "main.tf").read_bytes().replace(b"\r\n",b"\n")).hexdigest()
        args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
        if args.apply_dev:
            execute(base + ["apply", "-input=false", "-lock-timeout=30s", str(planfile)], env)
            proof["applied"] = True
            execute(base + ["plan", "-input=false", "-lock-timeout=30s", "-out=" + str(planfile)], env)
            readback = review(json.loads(execute(base + ["show", "-json", str(planfile)], env)))
            require(all(r["actions"] == ["no-op"] for r in readback), "Applied dedicated module must read back without drift")
            proof["independentNoDriftPlan"] = readback
    finally:
        shutil.rmtree(temporary)
        proof["temporaryCredentialsAndPlanAbsent"] = not temporary.exists()
        proof["checkedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"applied": proof["applied"], "reviewedResources": len(proof["reviewedChanges"]), "temporaryFilesAbsent": proof["temporaryCredentialsAndPlanAbsent"]}))


if __name__ == "__main__":
    main()
