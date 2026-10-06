"""Read the actual PowerShell matrix without cloud access and check production prerequisites."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'scripts/configure-runtime-service-accounts.ps1'

class RuntimeSecretMatrixTest(unittest.TestCase):
    def test_actual_matrix_has_only_dedicated_database_credentials(self):
        with tempfile.TemporaryDirectory(prefix='ims-secret-matrix-') as folder:
            fixture=Path(folder)/'matrix.ps1'
            fixture.write_text("$Environment='dev'\n"
                f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{SOURCE}',[ref]$null,[ref]$null)\n"
                "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$secretMatrix'},$true)\n"
                "Invoke-Expression $node.Extent.Text\n$secretMatrix|ConvertTo-Json -Depth 5\n")
            result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            matrix=json.loads(result.stdout)
            for service,prefix in [('identity-service','identity'),('school-core-service','school-core'),('operations-service','operations'),('platform-service','platform'),('billing-service','billing')]:
                self.assertIn(prefix+'-runtime-db-password-dev',matrix[service])
                self.assertNotIn('db-password-dev',matrix[service])
                self.assertNotIn('app-rt-password-dev',matrix[service])
            self.assertNotIn('jwt-secret-dev',matrix['api-gateway'])
            self.assertIn('jwt-secret-dev',matrix['identity-service'])

    def test_production_requires_explicit_verified_role_preparation(self):
        result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(SOURCE),'-ProjectId','custoking-prod','-Environment','prod','-Apply','-AllowProduction'],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('separately prepared and verified',result.stderr)

if __name__=='__main__':unittest.main()
