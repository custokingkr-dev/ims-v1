"""Evaluate actual CI selection without git history or cloud calls."""
import json
from pathlib import Path
import subprocess
import unittest
import shutil

POWERSHELL = shutil.which("pwsh") or shutil.which("powershell.exe") or shutil.which("powershell")
if not POWERSHELL:
    raise RuntimeError("PowerShell is required for deployment security regression tests")

ROOT=Path(__file__).resolve().parents[2]

class MigrationReleaseTriggerTest(unittest.TestCase):
    def resolve(self,file,environment='dev'):
        result=subprocess.run([POWERSHELL,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/resolve-affected-ci-targets.ps1'),'-Environment',environment,'-ChangedFilesOverride',file],capture_output=True,text=True)
        self.assertEqual(0,result.returncode,result.stderr)
        return json.loads(result.stdout)

    def test_migration_and_clouddeploy_helpers_select_all_service_tests(self):
        for file in ['scripts/invoke-service-migration-job.ps1','scripts/invoke-clouddeploy-release.ps1']:
            with self.subTest(file=file):
                evidence=self.resolve(file)
                self.assertTrue(evidence['has_service_changes'])
                self.assertGreaterEqual(len(evidence['service_matrix']['include']),7)
                self.assertFalse(evidence['deployment_reconciliation_required'])

    def test_committed_runtime_patch_epoch_selects_all_tests_and_images(self):
        expected={'identity-service','platform-service','operations-service','billing-service','school-core-service','api-gateway','frontend'}
        for environment in ['dev','prod']:
            with self.subTest(environment=environment):
                evidence=self.resolve('deploy/runtime-patch-epoch.txt',environment)
                self.assertTrue(evidence['has_service_changes'])
                self.assertEqual(expected,{row['name'] for row in evidence['service_matrix']['include']})
                self.assertEqual(expected,{row['name'] for row in evidence['docker_matrix']['include']})
                self.assertFalse(evidence['deployment_reconciliation_required'])
                self.assertFalse(evidence['deployment_config_changed'])

    def test_migration_iam_change_requires_pre_release_reconciliation(self):
        evidence=self.resolve('infra/terraform/cicd/migration_jobs.tf')
        self.assertTrue(evidence['deployment_reconciliation_required'])
        self.assertTrue(evidence['deployment_config_changed'])
        self.assertFalse(evidence['has_service_changes'])
        self.assertEqual([],evidence['service_matrix']['include'])

    def test_target_changes_require_reconciliation_only_in_matching_environment(self):
        self.assertTrue(self.resolve('deploy/clouddeploy/targets-dev.yaml')['deployment_reconciliation_required'])
        self.assertFalse(self.resolve('deploy/clouddeploy/targets-dev.yaml','prod')['deployment_reconciliation_required'])

if __name__=='__main__':unittest.main()
