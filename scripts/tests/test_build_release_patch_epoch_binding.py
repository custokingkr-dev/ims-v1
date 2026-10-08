"""Execute actual release workflow bodies offline to verify committed patch-epoch binding."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/build-release.yml"
SELECTED_SHA = "f" * 40
DIGEST = "sha256:" + "a" * 64
ABSENT = object()


def step_body(name):
    source = WORKFLOW.read_text(encoding="utf-8-sig")
    match = re.search(
        r"^      - name: " + re.escape(name) + r"\n(.*?)(?=^      - name: |\Z)",
        source, re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Missing workflow step: {name}")
    step = match.group(1)
    script = re.search(r"^        run: \|\n((?:^          .*\n|^\n)*)", step, re.MULTILINE)
    if script is None:
        raise AssertionError(f"Missing PowerShell run body: {name}")
    return textwrap.dedent(script.group(1))


def read_shell_text(path):
    raw = path.read_bytes()
    return raw.decode("utf-16" if raw.startswith(b"\xff\xfe") else "utf-8-sig")


class BuildReleasePatchEpochBindingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = shutil.which("pwsh") or shutil.which("powershell")
        if cls.shell is None:
            raise RuntimeError("PowerShell is required to execute the actual workflow epoch boundary")

    def run_step(self, step, epoch=ABSENT, *, exists=True, environment="dev"):
        body = step_body(step)
        substitutions = {
            "${{ needs.resolve-target.outputs.commit_sha }}": SELECTED_SHA,
            "${{ needs.resolve-target.outputs.target_env }}": environment,
            "${{ matrix.context }}": "./frontend",
            "${{ matrix.source_paths }}": "frontend",
            "${{ matrix.image }}": "custoking-frontend",
        }
        for key, value in substitutions.items():
            body = body.replace(key, value)
        self.assertNotIn("${{", body, "New workflow expression requires a deliberate test binding")
        with tempfile.TemporaryDirectory(prefix="ims-workflow-epoch-") as directory:
            fixture = Path(directory).resolve()
            # Keep Windows recursive cleanup confined to the exact fixture directory.
            self.assertEqual(fixture.parent, Path(tempfile.gettempdir()).resolve())
            scripts = fixture / "scripts"
            scripts.mkdir()
            metadata = {"sourceId": "b" * 64, "sourceTag": "src-" + "b" * 64}
            if epoch is not ABSENT:
                metadata["securityPatchEpoch"] = epoch
            (fixture / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            (scripts / "resolve-image-source-id.ps1").write_text(
                'param([string]$CommitSha, [string]$Context, [string]$SourcePaths, [string]$BuildArgs)\n'
                '[ordered]@{commit=$CommitSha; context=$Context; paths=$SourcePaths; args=$BuildArgs} | '
                'ConvertTo-Json -Compress | Set-Content -Encoding UTF8 $env:TEST_EPOCH_ARGUMENTS\n'
                'Get-Content -Raw $env:TEST_EPOCH_METADATA\n', encoding="utf-8",
            )
            prefix = r'''
function gcloud {
  if ($env:TEST_EPOCH_IMAGE_EXISTS -eq "true") {
    $global:LASTEXITCODE = 0
    $env:TEST_EPOCH_DIGEST
  } else { $global:LASTEXITCODE = 1 }
}
function docker {
  $global:LASTEXITCODE = 0
  if ($args -contains "inspect") {
    [ordered]@{schemaVersion=2; manifests=@(@{platform=@{os="linux"; architecture="amd64"}; digest=$env:TEST_EPOCH_DIGEST})} | ConvertTo-Json -Depth 5 -Compress
  }
}
function cosign { $global:LASTEXITCODE = 0 }
'''
            script = fixture / "workflow.ps1"
            script.write_text(prefix + '\ntry {\n' + body + '\n} catch { Write-Error $_; exit 1 }\n', encoding="utf-8")
            matrix = {"include": [{"name": "frontend", "image": "custoking-frontend",
                "context": "./frontend", "source_paths": "frontend", "build_args": ""}]}
            env = os.environ.copy()
            env.update({
                "TEST_EPOCH_IMAGE_EXISTS": str(exists).lower(), "TEST_EPOCH_DIGEST": DIGEST,
                "TEST_EPOCH_METADATA": str(fixture / "metadata.json"),
                "TEST_EPOCH_ARGUMENTS": str(fixture / "arguments.json"),
                "GITHUB_OUTPUT": str(fixture / "output.txt"),
                "GCP_REGION": "asia-south1", "ARTIFACT_REGISTRY_PROJECT_ID": "custoking-dev",
                "GCP_PROJECT_ID": "custoking-dev" if environment == "dev" else "isolated-promotion-fixture",
                "ARTIFACT_REGISTRY_REPOSITORY": "ims", "BUILD_ARGS": "",
                "AFFECTED_MATRIX_JSON": json.dumps(matrix), "GITHUB_REPOSITORY": "fixture/ims",
            })
            result = subprocess.run([self.shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", str(script)], cwd=fixture, env=env, capture_output=True, timeout=12)
            outputs = {}
            output_path = fixture / "output.txt"
            if output_path.exists():
                outputs = dict(line.split("=", 1) for line in read_shell_text(output_path).splitlines())
            arguments_path = fixture / "arguments.json"
            arguments = json.loads(read_shell_text(arguments_path)) if arguments_path.exists() else None
            manifest_path = fixture / "release-evidence/images.json"
            manifest = json.loads(read_shell_text(manifest_path)) if manifest_path.exists() else None
            return result, outputs, arguments, manifest

    def assert_succeeded(self, result):
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace")[:2000])

    def test_fresh_build_binds_exact_selected_epoch_and_full_cycle(self):
        for epoch in ["2024-02-29", "2026-10-08-r2", "2026-10-08-r999999"]:
            with self.subTest(epoch=epoch):
                result, output, arguments, _ = self.run_step("Resolve content-addressed image", epoch, exists=False)
                self.assert_succeeded(result)
                self.assertEqual(SELECTED_SHA, arguments["commit"])
                self.assertEqual(epoch, output["security_patch_epoch"])
                self.assertEqual("true", output["should_build"])

    def test_missing_epoch_fresh_build_fails_without_clock_fallback(self):
        for epoch in [ABSENT, ""]:
            with self.subTest(epoch=repr(epoch)):
                result, output, _, _ = self.run_step("Resolve content-addressed image", epoch, exists=False)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("should_build", output)
                self.assertIn(b"Fresh image builds require", result.stderr)

    def test_invalid_metadata_rejected_even_for_existing_image(self):
        for epoch in ["2026-02-29", "2026-10-08-r0", "2026-10-08-r01", "2026-10-08-r1000000",
                "2026-10-08\n", " 2026-10-08", "2026-10-08-R2"]:
            with self.subTest(epoch=epoch):
                result, output, _, _ = self.run_step("Resolve content-addressed image", epoch)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("should_build", output)

    def test_legacy_image_reuse_has_no_invented_epoch(self):
        result, output, _, _ = self.run_step("Resolve content-addressed image")
        self.assert_succeeded(result)
        self.assertEqual("false", output["should_build"])
        self.assertEqual("", output["security_patch_epoch"])

    def test_dev_and_prod_manifest_preserve_full_epoch_or_legacy_null(self):
        for environment in ["dev", "prod"]:
            for epoch in [ABSENT, "2026-10-08-r2"]:
                with self.subTest(environment=environment, epoch=repr(epoch)):
                    result, _, arguments, manifest = self.run_step("Resolve immutable release images", epoch,
                        environment=environment)
                    self.assert_succeeded(result)
                    self.assertEqual(SELECTED_SHA, arguments["commit"])
                    self.assertEqual(SELECTED_SHA, manifest["commit"])
                    self.assertEqual(None if epoch is ABSENT else epoch, manifest["services"][0]["securityPatchEpoch"])
                    self.assertEqual(DIGEST, manifest["services"][0]["digest"])
                    tag = manifest["services"][0]["resolvedTag"]
                    self.assertEqual(environment == "prod", tag.startswith("dev-approved-"))

    def test_release_invalid_epoch_rejected_before_manifest_or_promotion(self):
        result, _, _, manifest = self.run_step("Resolve immutable release images", "2026-02-30", environment="prod")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(manifest)

    def test_docker_argument_uses_resolver_output_and_preserves_stage_cache(self):
        source = WORKFLOW.read_text(encoding="utf-8-sig")
        block = source.split("- name: Build and push image", 1)[1].split("- name: Write image summary", 1)[0]
        self.assertIn("SECURITY_PATCH_EPOCH=${{ steps.source.outputs.security_patch_epoch }}", block)
        self.assertNotIn("${{ env.SECURITY_PATCH_EPOCH }}", block)
        self.assertNotIn('SECURITY_PATCH_EPOCH=$(date', source)
        self.assertIn("cache-from: type=gha,scope=${{ matrix.image }}", block)
        self.assertIn("cache-to: type=gha,mode=max,scope=${{ matrix.image }}", block)
        self.assertIn("if: ${{ steps.source.outputs.should_build == 'true' }}", block)


if __name__ == "__main__":
    unittest.main()
