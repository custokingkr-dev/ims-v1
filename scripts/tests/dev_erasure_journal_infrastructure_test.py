import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("journal_infra", Path(__file__).resolve().parents[1] / "security/provision-dev-erasure-journal.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


def plan():
    rows = []
    for address in sorted(TOOL.EXPECTED):
        kind = address.split(".")[0]
        writer = address.endswith(".writer")
        if kind == "google_storage_bucket":
            after = {"project":TOOL.PROJECT,"name":TOOL.BUCKET,"location":"ASIA-SOUTH2","storage_class":"STANDARD",
                     "public_access_prevention":"enforced","uniform_bucket_level_access":True,"force_destroy":False,
                     "versioning":[{"enabled":True}],"lifecycle_rule":[],"retention_policy":[]}
        elif kind == "google_project_iam_custom_role":
            after = {"project":TOOL.PROJECT,"role_id":"erasureJournalWriter_dev" if writer else "erasureJournalControlReader_dev",
                     "permissions":["storage.objects.create","storage.objects.get"] if writer else ["storage.objects.get"]}
        else:
            prefix="intents/" if writer else "control/"
            after={"bucket":TOOL.BUCKET,"member":TOOL.MEMBER,
                   "role":"projects/"+TOOL.PROJECT+"/roles/"+("erasureJournalWriter_dev" if writer else "erasureJournalControlReader_dev"),
                   "condition":[{"expression":"resource.name.startsWith('projects/_/buckets/"+TOOL.BUCKET+"/objects/"+prefix+"')"}]}
        rows.append({"address":address,"type":kind,"change":{"actions":["create"],"after":after}})
    return {"resource_changes":rows}


class JournalInfrastructureGuards(unittest.TestCase):
    def test_exact_create_or_canonical_noop_scope(self):
        value=plan(); self.assertEqual(len(TOOL.review(value)),5)
        for row in value["resource_changes"]:
            row["change"]["actions"]=["no-op"]
            if row["type"]=="google_storage_bucket_iam_member": row["change"]["after"]["bucket"]="b/"+TOOL.BUCKET
        self.assertEqual(len(TOOL.review(value)),5)

    def test_reject_destructive_or_unrelated_changes(self):
        for actions in (["update"],["delete"],["delete","create"]):
            value=plan();value["resource_changes"][0]["change"]["actions"]=actions
            with self.assertRaises(RuntimeError): TOOL.review(value)
        value=plan();value["resource_changes"].append(copy.deepcopy(value["resource_changes"][0]))
        with self.assertRaises(RuntimeError): TOOL.review(value)

    def test_reject_broader_permissions_public_access_or_wrong_identity(self):
        mutations=[("google_project_iam_custom_role.writer","permissions",["storage.objects.create","storage.objects.get","storage.objects.delete"]),
                   ("google_storage_bucket.journal","project","custoking-prod"),
                   ("google_storage_bucket.journal","public_access_prevention","inherited"),
                   ("google_storage_bucket.journal","lifecycle_rule",[{"action":"Delete"}]),
                   ("google_storage_bucket_iam_member.writer","role","roles/storage.objectAdmin"),
                   ("google_storage_bucket_iam_member.writer","member","allUsers"),
                   ("google_storage_bucket_iam_member.control_reader","condition",[{"expression":"true"}])]
        for address,key,value in mutations:
            with self.subTest(address=address,key=key):
                data=plan();next(r for r in data["resource_changes"] if r["address"]==address)["change"]["after"][key]=value
                with self.assertRaises(RuntimeError): TOOL.review(data)


if __name__ == "__main__": unittest.main()
