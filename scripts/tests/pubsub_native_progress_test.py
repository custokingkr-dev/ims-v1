"""Extract actual native wrappers; normal stderr cannot hide true native failure."""
from pathlib import Path
import subprocess,tempfile,shutil,unittest
ROOT=Path(__file__).resolve().parents[2]
PS=shutil.which('powershell.exe') or shutil.which('pwsh')
class PubSubNativeProgressTest(unittest.TestCase):
 def test_both_real_wrappers_preserve_success_failure_and_error_preference(self):
  for name in ['configure-reporting-pubsub-resilience.ps1','configure-notification-pubsub-push-oidc.ps1']:
   with self.subTest(script=name),tempfile.TemporaryDirectory(prefix='ims-pubsub-native-') as folder:
    success=Path(folder)/'success.ps1';success.write_text('[Console]::Error.WriteLine("Normal CLI progress"); Write-Output "SAFE_RESULT"; exit 0')
    failure=Path(folder)/'failure.ps1';failure.write_text('[Console]::Error.WriteLine("Normal CLI progress"); exit 7')
    script=Path(folder)/'check.ps1';source=str(ROOT/'scripts'/name).replace("'","''")
    script.write_text(f"$ErrorActionPreference='Stop'\n$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
      "$node=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Gcloud'},$true)\nInvoke-Expression $node.Extent.Text\n"
      f"$gcloud=(Get-Process -Id $PID).Path\n$result=@(Invoke-Gcloud '-NoProfile' '-File' '{success}')\n"
      "if(($result -join '') -ne 'SAFE_RESULT' -or $ErrorActionPreference -ne 'Stop'){throw 'success/preference regression'}\n"
      f"try {{Invoke-Gcloud '-NoProfile' '-File' '{failure}';throw 'ignored failure'}} catch {{if($_.Exception.Message -notlike '*Pub/Sub configuration cloud command failed*'){{throw}}}}\n"
      "if($ErrorActionPreference -ne 'Stop'){throw 'failure preference regression'}\nWrite-Output 'PASS'\n")
    result=subprocess.run([PS,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(script)],capture_output=True,text=True,timeout=30)
    self.assertEqual(0,result.returncode,result.stderr)
    self.assertEqual('PASS',result.stdout.strip())
    self.assertNotIn('Normal CLI progress',result.stdout+result.stderr)
 def test_notification_grants_oidc_only_on_exact_push_account_without_policy_replacement(self):
  source=(ROOT/'scripts/configure-notification-pubsub-push-oidc.ps1').read_text()
  binding=source.split('Invoke-Gcloud iam service-accounts add-iam-policy-binding $pushServiceAccount',1)[1].split('Invoke-Gcloud run services add-iam-policy-binding',1)[0]
  self.assertIn('--role=roles/iam.serviceAccountOpenIdTokenCreator',binding)
  self.assertIn('"--member=serviceAccount:$pubsubServiceAgent"',binding)
  self.assertIn('"--project=$ProjectId"',binding)
  self.assertNotIn('--role=roles/iam.serviceAccountTokenCreator',source)
  self.assertNotIn('set-iam-policy',source)
  self.assertNotIn('remove-iam-policy-binding',source)
 def test_reporting_configures_ack_deadline_it_verifies(self):
  source=(ROOT/'scripts/configure-reporting-pubsub-resilience.ps1').read_text()
  update=source.split('Invoke-Gcloud pubsub subscriptions update $subscription',1)[1].split('$updated =',1)[0]
  self.assertIn('--ack-deadline=10',update)
  self.assertIn('$updated.ackDeadlineSeconds -ne 10',source)
if __name__=='__main__':unittest.main()
