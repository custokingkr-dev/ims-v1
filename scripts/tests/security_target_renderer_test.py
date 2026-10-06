"""Render reviewed dev/prod sources and reject adversarial role/secret/authority tampering offline."""
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]
POWERSHELL=shutil.which("pwsh") or shutil.which("powershell.exe") or shutil.which("powershell")
if not POWERSHELL: raise RuntimeError("PowerShell is required for renderer security tests")
ROLES={"identity-service":"ims_identity_rt","school-core-service":"ims_school_core_rt","operations-service":"ims_operations_rt","platform-service":"ims_platform_rt","billing-service":"ims_billing_rt"}

class SecurityTargetRendererTest(unittest.TestCase):
    def render(self,environment="dev",tamper=None):
        text=(ROOT/f"deploy/clouddeploy/targets-{environment}.yaml").read_text()
        if tamper: text=tamper(text)
        with tempfile.TemporaryDirectory(prefix="ims-security-render-") as folder:
            template=pathlib.Path(folder)/"targets.yaml";template.write_text(text)
            output=pathlib.Path(folder)/"rendered.yaml"
            env=os.environ.copy()
            values={"GCP_PROJECT_ID":"custoking-"+environment,"GCP_PROJECT_NUMBER":"1234567890","GCP_REGION":"asia-south2","DB_HOST":"10.0.0.2","DB_NAME":"fixture","STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID":"reviewed-fixture-folder","STUDENT_PHOTO_BUCKET":"reviewed-fixture-bucket"}
            for key,value in values.items(): env[key]=value;env[environment.upper()+"_"+key]=value
            result=subprocess.run([POWERSHELL,"-NoProfile","-ExecutionPolicy","Bypass","-File",str(ROOT/"scripts/render-clouddeploy-targets.ps1"),"-Environment",environment,"-TemplatePath",str(template),"-OutputPath",str(output)],env=env,capture_output=True,text=True,timeout=30)
            rendered=output.read_text(encoding="utf-8-sig") if output.exists() else None
            return result,rendered
    @staticmethod
    def change(text,service,key,value,duplicate=False,remove=False):
        blocks=re.split(r"(?m)^---\s*$",text)
        found=0
        for index,block in enumerate(blocks):
            if re.search(r"(?m)^  name: "+re.escape(service)+r"-(dev|prod)\s*$",block):
                found+=1
                pattern=r"(?m)^  "+re.escape(key)+r":[^\r\n]*"
                replacement="" if remove else "  "+key+": "+value
                if duplicate:
                    original=re.search(pattern,block).group(0);replacement=original+"\n"+replacement
                blocks[index],count=re.subn(pattern,lambda _:replacement,block)
                if count!=1: raise AssertionError("Fixture must modify exactly one declared parameter")
        if found!=1: raise AssertionError("Fixture must select exactly one service target")
        return "---".join(blocks)
    def test_reviewed_dev_and_prod_render_exact_roles_secrets_audiences_and_carriers(self):
        for environment in ["dev","prod"]:
            with self.subTest(environment=environment):
                result,text=self.render(environment)
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertIsNotNone(text)
                for service,role in ROLES.items():
                    block=next(b for b in re.split(r"(?m)^---\s*$",text) if re.search(r"(?m)^  name: "+service+"-"+environment+r"\s*$",b))
                    self.assertIn("  runtime_db_role: "+role,block)
                    self.assertIn(service.removesuffix("-service")+"-runtime-db-password-"+environment,block)
                    self.assertIn(f"https://custoking-{service}-{environment}-1234567890.asia-south2.run.app",block)
                    hash="hd4wfwk7mq" if environment=="dev" else "yter7sugpa"
                    self.assertIn(f"https://custoking-{service}-{environment}-{hash}-em.a.run.app",block)
                    carrier=re.search(r"(?m)^  user_context_caller_service_accounts: (.+)$",block).group(1).strip()
                    expected=f"ims-api-gateway-{environment}@custoking-{environment}.iam.gserviceaccount.com"
                    if service=="school-core-service": expected+=f",ims-identity-{environment}@custoking-{environment}.iam.gserviceaccount.com"
                    self.assertEqual(expected,carrier)
    def test_each_service_rejects_shared_role_and_another_services_dedicated_secret(self):
        for service in ROLES:
            for key,value in [("runtime_db_role","app_rt"),("runtime_db_password_secret","app-rt-password-dev"),("runtime_db_password_secret",("billing" if service=="identity-service" else "identity")+"-runtime-db-password-dev")]:
                with self.subTest(service=service,key=key,value=value):
                    result,text=self.render(tamper=lambda t:self.change(t,service,key,value))
                    self.assertNotEqual(0,result.returncode);self.assertIsNone(text)
                    self.assertIn("security target parameter: "+key,result.stderr)
    def test_wrong_service_project_and_wildcard_audiences_are_rejected(self):
        for value in ["https://attacker.example","https://custoking-billing-service-dev-__PROJECT_NUMBER__.__REGION__.run.app","*","https://custoking-identity-service-dev-__PROJECT_NUMBER__.__REGION__.run.app,https://foreign-project.run.app"]:
            with self.subTest(value=value):
                result,text=self.render(tamper=lambda t:self.change(t,"identity-service","service_oidc_audiences",value))
                self.assertNotEqual(0,result.returncode);self.assertIsNone(text);self.assertIn("service_oidc_audiences",result.stderr)
    def test_foreign_and_expanded_principal_carriers_are_rejected(self):
        for service,value in [("identity-service","ims-api-gateway-dev@__PROJECT_ID__.iam.gserviceaccount.com,ims-school-core-dev@__PROJECT_ID__.iam.gserviceaccount.com"),("school-core-service","ims-api-gateway-dev@attacker.iam.gserviceaccount.com"),("platform-service","*")]:
            result,text=self.render(tamper=lambda t:self.change(t,service,"user_context_caller_service_accounts",value))
            self.assertNotEqual(0,result.returncode);self.assertIsNone(text);self.assertIn("user_context_caller_service_accounts",result.stderr)
    def test_missing_and_duplicate_security_declarations_are_rejected(self):
        for key in ["runtime_db_role","runtime_db_password_secret","service_oidc_audiences","user_context_caller_service_accounts"]:
            for mode in ["remove","duplicate"]:
                with self.subTest(key=key,mode=mode):
                    result,text=self.render(tamper=lambda t:self.change(t,"operations-service",key,"attacker",remove=mode=="remove",duplicate=mode=="duplicate"))
                    self.assertNotEqual(0,result.returncode);self.assertIsNone(text);self.assertIn(key,result.stderr)

if __name__=="__main__": unittest.main()
