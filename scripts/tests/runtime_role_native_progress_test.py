"""Exercise the production native wrapper using Windows PowerShell 5 and a local CLI fixture."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class NativeProgressTest(unittest.TestCase):
    def invoke(self, code):
        with tempfile.TemporaryDirectory(prefix="ims-native-progress-") as directory:
            folder = Path(directory)
            (folder / "gcloud.cmd").write_text(f"@echo off\necho Normal deployment progress 1>&2\necho fixture-result\nexit /b {code}\n")
            script = folder / "fixture.ps1"
            source = str(ROOT / "scripts/security/prepare-dev-runtime-roles.ps1").replace("'", "''")
            script.write_text("$ErrorActionPreference='Stop'\n"
                f"$ast=[System.Management.Automation.Language.Parser]::ParseFile('{source}',[ref]$null,[ref]$null)\n"
                "$function=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-PreparationGcloud'},$true)\n"
                "Invoke-Expression $function.Extent.Text\n"
                f"$env:PATH='{directory};'+$env:PATH\n"
                "$result=Invoke-PreparationGcloud -Arguments @('run','jobs','replace') -CaptureOutput\n"
                "@{exitCode=$result.ExitCode;output=$result.Output;restored=[string]$ErrorActionPreference}|ConvertTo-Json -Compress\nexit 0\n")
            result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn("Normal deployment progress", result.stdout + result.stderr)
            return json.loads(result.stdout)

    def test_stderr_progress_does_not_abort_success_and_preference_restored(self):
        self.assertEqual({"exitCode": 0, "output": "fixture-result", "restored": "Stop"}, self.invoke(0))

    def test_real_failure_exit_status_preserved_despite_stderr_progress(self):
        result = self.invoke(7)
        self.assertEqual(7, result["exitCode"])
        self.assertEqual("Stop", result["restored"])

if __name__ == "__main__":
    unittest.main()
