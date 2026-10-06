"""Evaluate actual deployment step expressions across dev/prod, manifest and full-release inputs."""
from pathlib import Path
import itertools
import re
import unittest
ROOT=Path(__file__).resolve().parents[2]
class FullReleaseRoutingTest(unittest.TestCase):
    def expression(self,name):
        source=(ROOT/'.github/workflows/build-release.yml').read_text(encoding='utf-8-sig')
        match=re.search(r'- name: '+re.escape(name)+r'\n\s+if: \$\{\{ (.*?) \}\}',source)
        self.assertIsNotNone(match,name)
        return match.group(1)
    def evaluate(self,name,environment,changed,force):
        expression=self.expression(name)
        substitutions={'needs.resolve-target.outputs.target_env':repr(environment),'needs.detect.outputs.deployment_config_changed':repr('true' if changed else 'false'),'inputs.force_full_deploy':repr(force)}
        for key,value in substitutions.items():expression=expression.replace(key,value)
        expression=expression.replace('&&',' and ').replace('||',' or ')
        expression=re.sub(r'\btrue\b(?=\s|$)','True',expression)
        # Restricted workflow grammar: only comparisons, literal booleans/strings and conjunctions.
        self.assertRegex(expression,r"^[a-zA-Z0-9_' !=()]+$")
        return bool(eval(expression,{'__builtins__':{}},{}))
    def test_actual_route_truth_table_keeps_full_dev_rendered_and_prod_serial(self):
        for environment,changed,force in itertools.product(['dev','prod'],[False,True],[None,False,True]):
            with self.subTest(environment=environment,changed=changed,force=force):
                serial=environment=='prod' or changed or force is True
                self.assertEqual(not serial,self.evaluate('Fast dev deployment',environment,changed,force))
                self.assertEqual(serial,self.evaluate('Create and serially promote Cloud Deploy releases',environment,changed,force))
                self.assertEqual(serial,self.evaluate('Confirm Cloud Deploy rollouts',environment,changed,force))
    def test_serial_step_preserves_wait_and_readiness_verification(self):
        source=(ROOT/'.github/workflows/build-release.yml').read_text()
        block=source.split('- name: Create and serially promote Cloud Deploy releases',1)[1].split('- name: Confirm Cloud Deploy rollouts',1)[0]
        self.assertIn('WaitForRollout = $true',block)
        self.assertIn('./scripts/invoke-clouddeploy-release.ps1 @arguments',block)
        self.assertIn('- name: Verify changed Cloud Run services',source)
        self.assertIn('./scripts/verify-cloudrun-release.ps1',source)
if __name__=='__main__':unittest.main()
