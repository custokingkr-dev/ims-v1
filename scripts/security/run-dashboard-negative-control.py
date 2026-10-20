import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import pathlib,runpy,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
try:
 cloud(['firestore','databases','create','--database=ims-dashboard-acceptance-denied','--location=asia-south2','--type=firestore-native','--quiet','--format=json'])
 result=subprocess.run(['python',str(pathlib.Path(__file__).resolve().with_name('run-dashboard-firestore-acceptance.py')),'--apply-dev'])
 if result.returncode: raise RuntimeError('Dashboard scoped acceptance failed')
finally: cloud(['firestore','databases','delete','--database=ims-dashboard-acceptance-denied','--quiet','--format=json'])
