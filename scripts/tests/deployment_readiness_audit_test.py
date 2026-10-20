"""Exercise the actual offline audit validator with controlled source tampering; no cloud calls."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
PS=shutil.which('pwsh') or shutil.which('powershell.exe')
class DeploymentReadinessAuditTest(unittest.TestCase):
    def validate(self,direct,cloud):
        with tempfile.TemporaryDirectory(prefix='ims-readiness-audit-') as folder:
            directory=Path(folder)
            (directory/'direct.ps1').write_text(direct)
            (directory/'cloud.ps1').write_text(cloud)
            script=directory/'check.ps1'
            source=str(ROOT/'scripts/audit-deployment-boundaries.ps1').replace("'","''")
            script.write_text(f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
                "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-ReleaseReadinessSource'},$true)\n"
                "Invoke-Expression $node.Extent.Text\n"
                "$direct=Get-Content -Raw (Join-Path $PSScriptRoot 'direct.ps1')\n$cloud=Get-Content -Raw (Join-Path $PSScriptRoot 'cloud.ps1')\n"
                "$failures=@(Test-ReleaseReadinessSource $direct $cloud)\n$failures|ForEach-Object {Write-Output $_}\nif($failures.Count -gt 0){exit 1}\n")
            return subprocess.run([PS,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(script)],capture_output=True,text=True)
    def test_current_actual_sources_pass(self):
        result=self.validate(*self.sources())
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
    def test_service_global_scale_rejects_revision_only_or_wrong_parameter(self):
        with tempfile.TemporaryDirectory(prefix='ims-global-scale-') as folder:
            directory=Path(folder)
            script=directory/'scale.ps1'
            source=str(ROOT/'scripts/audit-deployment-boundaries.ps1').replace("'","''")
            script.write_text(f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
                "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-DomainServiceGlobalScale'},$true)\n"
                "Invoke-Expression $node.Extent.Text\n"
                "$text=Get-Content -Raw (Join-Path $PSScriptRoot 'service.yaml')\nif(-not (Test-DomainServiceGlobalScale $text)){exit 1}\n")
            for service in ['identity-service','school-core-service','operations-service','billing-service','platform-service']:
                manifest=(ROOT/'deploy/cloudrun'/f'{service}.yaml').read_text()
                for label,text,expected in [('current',manifest,0),('revision-only',manifest.replace('    run.googleapis.com/maxScale: "2" # from-param: ${domain_max_instances}\n','',1),1),('wrong-bound',manifest.replace('run.googleapis.com/maxScale: "2"','run.googleapis.com/maxScale: "4"',1),1),('wrong-parameter',manifest.replace('run.googleapis.com/maxScale: "2" # from-param: ${domain_max_instances}','run.googleapis.com/maxScale: "2" # from-param: ${other_max_instances}',1),1)]:
                    with self.subTest(service=service,label=label):
                        (directory/'service.yaml').write_text(text)
                        result=subprocess.run([PS,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(script)],capture_output=True,text=True)
                        self.assertEqual(expected,result.returncode,result.stderr)
    def sources(self):
        return tuple((ROOT/'scripts'/name).read_text(encoding='utf-8-sig') for name in ['invoke-direct-cloudrun-release.ps1','invoke-clouddeploy-release.ps1'])
    def test_direct_safeguard_tampering_fails(self):
        direct,cloud=self.sources()
        cases=[('async',direct.replace('Invoke-DirectCloudRunDeployment -Arguments $deployArguments','$deployArguments += "--async"\n  Invoke-DirectCloudRunDeployment -Arguments $deployArguments')),
            ('deadline',direct.replace('AddMinutes(10)','AddMinutes(100)')),
            ('exitcode',direct.replace('$result.ExitCode -ne 0','$false')),
            ('preflight',direct.replace('Assert-DirectReleaseProfile -Service $preflightService -Data $profile','# Assert-DirectReleaseProfile -Service $preflightService -Data $profile')),
            ('deploycall',direct.replace('Invoke-DirectCloudRunDeployment -Arguments $deployArguments','# Invoke-DirectCloudRunDeployment -Arguments $deployArguments')),
            ('migrationprofile',direct.replace("$settings['APP_MIGRATIONS_ENABLED']","$settings['REMOVED']")),
            ('gatewayfirst',direct.replace('"api-gateway",\n  "identity-service"','"identity-service",\n  "api-gateway"'))]
        for label,modified in cases:
            with self.subTest(label=label):
                self.assertNotEqual(direct,modified)
                result=self.validate(modified,cloud)
                self.assertNotEqual(0,result.returncode,result.stdout+result.stderr)
                self.assertIn('Release readiness:',result.stdout)
    def test_clouddeploy_order_and_required_wait_tampering_fail(self):
        direct,cloud=self.sources()
        for changed in [cloud.replace('"api-gateway",\n  "identity-service"','"identity-service",\n  "api-gateway"'),cloud.replace('if (-not $WaitForRollout -and','if ($false -and')]:
            with self.subTest(source=changed[:80]):
                result=self.validate(direct,changed)
                self.assertNotEqual(0,result.returncode,result.stdout+result.stderr)
if __name__=='__main__':unittest.main()
