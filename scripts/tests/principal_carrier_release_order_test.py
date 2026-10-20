"""Evaluate the actual PowerShell order assignments without invoking release actions."""
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
EXPECTED=['api-gateway','identity-service','frontend','school-core-service','operations-service','billing-service','platform-service']
POWERSHELL=shutil.which('pwsh') or shutil.which('powershell.exe')

class PrincipalCarrierReleaseOrderTest(unittest.TestCase):
    def test_both_real_release_scripts_install_carrier_and_step_up_dependencies_first(self):
        for filename in ['invoke-clouddeploy-release.ps1','invoke-direct-cloudrun-release.ps1']:
            with self.subTest(filename=filename),tempfile.TemporaryDirectory(prefix='ims-release-order-') as folder:
                source=ROOT/'scripts'/filename
                fixture=Path(folder)/'order.ps1'
                fixture.write_text(f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
                    "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$releaseOrder'},$true)\n"
                    "Invoke-Expression $node.Extent.Text\n$releaseOrder|ConvertTo-Json -Compress\n")
                result=subprocess.run([POWERSHELL,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture)],capture_output=True,text=True)
                self.assertEqual(0,result.returncode,result.stderr)
                order=json.loads(result.stdout)
                self.assertEqual(EXPECTED,order)
                self.assertEqual(7,len(set(order)))

    def test_clouddeploy_backend_without_serial_wait_fails_before_cloud_call(self):
        with tempfile.TemporaryDirectory(prefix='ims-release-serial-') as folder:
            images=Path(folder)/'images.json'
            images.write_text(json.dumps({'services':[{'service':'api-gateway'}]}))
            result=subprocess.run([POWERSHELL,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/invoke-clouddeploy-release.ps1'),'-ProjectId','custoking-dev','-Region','asia-south2','-Environment','dev','-CommitSha','a'*40,'-RunAttempt','1','-ImagesJson',str(images),'-SourceStagingDir','gs://controlled-fixture/source'],capture_output=True,text=True)
            self.assertNotEqual(0,result.returncode)
            self.assertIn('require -WaitForRollout',result.stderr)

    def test_direct_native_wait_returns_only_after_readiness_and_blocks_failure(self):
        with tempfile.TemporaryDirectory(prefix='ims-release-native-') as folder:
            source=ROOT/'scripts/invoke-direct-cloudrun-release.ps1'
            fixture=Path(folder)/'wait.ps1'
            fixture.write_text(f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
                "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-DirectCloudRunDeployment'},$true)\n"
                "Invoke-Expression $node.Extent.Text\n"
                "$GcloudCommand=(Get-Process -Id $PID).Path\n"
                "Invoke-DirectCloudRunDeployment -Arguments @('-NoProfile','-Command','Start-Sleep -Milliseconds 200; exit 0')\n"
                "Write-Output 'READY_BEFORE_NEXT_SERVICE'\n"
                "try { Invoke-DirectCloudRunDeployment -Arguments @('-NoProfile','-Command','exit 7'); throw 'failure was ignored' } catch { if($_.Exception.Message -notlike '*subsequent services remain blocked*'){throw};Write-Output 'FAILURE_BLOCKED_NEXT_SERVICE' }\n")
            result=subprocess.run([POWERSHELL,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual(['READY_BEFORE_NEXT_SERVICE','FAILURE_BLOCKED_NEXT_SERVICE'],result.stdout.splitlines())
            text=source.read_text(encoding='utf-8-sig')
            self.assertNotIn('"--async"',text)
            self.assertIn('AddMinutes(10)',text)

if __name__=='__main__':unittest.main()
