"""Evaluate actual CI selection without git history or cloud calls."""
import json
from pathlib import Path
import subprocess
import unittest

ROOT=Path(__file__).resolve().parents[2]

class MigrationReleaseTriggerTest(unittest.TestCase):
    def resolve(self,file,environment='dev'):
        result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/resolve-affected-ci-targets.ps1'),'-Environment',environment,'-ChangedFilesOverride',file],capture_output=True,text=True)
        self.assertEqual(0,result.returncode,result.stderr)
        return json.loads(result.stdout)

    def test_migration_and_clouddeploy_helpers_select_all_service_tests(self):
        for file in ['scripts/invoke-service-migration-job.ps1','scripts/invoke-clouddeploy-release.ps1']:
            with self.subTest(file=file):
                evidence=self.resolve(file)
                self.assertTrue(evidence['has_service_changes'])
                self.assertGreaterEqual(len(evidence['service_matrix']['include']),7)
                self.assertFalse(evidence['deployment_reconciliation_required'])

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
