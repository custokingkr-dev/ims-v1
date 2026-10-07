import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / "security/adopt-dev-dashboard-state.py"
SPEC = importlib.util.spec_from_file_location("dashboard_adoption", PATH)
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class DashboardStateAdoptionTest(unittest.TestCase):
    def test_import_set_is_exact_existing_dev_state_only(self):
        self.assertEqual(len(TOOL.IMPORTS), 6)
        self.assertTrue(all("custoking-dev" in identity for identity in TOOL.IMPORTS.values()))
        self.assertFalse(any("cloud_run" in address for address in TOOL.IMPORTS))
        self.assertIn("-var=enable_shared_dashboard=false", TOOL.VARIABLES)

    def test_existing_binding_must_retain_exact_account_role_and_condition(self):
        address = "google_project_iam_member.dashboard_security_state[0]"
        original = {"project": TOOL.PROJECT, "role": "projects/custoking-dev/roles/dashboardSecurityState_dev",
                    "member": "serviceAccount:" + TOOL.ACCOUNT,
                    "condition": [{"title": "dashboard-named-database-only", "expression": f"resource.name == '{TOOL.DATABASE}'"}]}
        self.assertTrue(TOOL.existing_identity(address, original))
        for key, value in [("project", "custoking-prod"), ("member", "allUsers"), ("role", "roles/owner"),
                           ("condition", [{"title": "another-condition"}])]:
            self.assertFalse(TOOL.existing_identity(address, {**original, key: value}))
        self.assertFalse(TOOL.existing_identity(address, {**original, "condition": [{"title": "dashboard-named-database-only", "expression": "true"}]}))

    def test_data_sources_and_module_resources_cannot_mask_import_addresses(self):
        state = {"resources": [
            {"mode": "data", "type": "google_service_account", "name": "dashboard", "instances": [{"index_key": 0, "attributes": {"id": "data"}}]},
            {"mode": "managed", "type": "google_service_account", "name": "dashboard", "instances": [{"index_key": 0, "attributes": {"id": "root"}}]},
            {"mode": "managed", "module": "module.other", "type": "google_service_account", "name": "dashboard", "instances": [{"index_key": 0, "attributes": {"id": "module"}}]},
        ]}
        rows = TOOL.state_resources(state)
        self.assertEqual(rows["google_service_account.dashboard[0]"]["id"], "root")
        self.assertEqual(rows["module.other.google_service_account.dashboard[0]"]["id"], "module")
        self.assertEqual(len(rows), 2)


if __name__ == "__main__":
    unittest.main()
