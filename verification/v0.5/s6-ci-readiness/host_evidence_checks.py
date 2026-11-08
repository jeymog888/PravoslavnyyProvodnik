from pathlib import Path
import importlib.util, json, tempfile, subprocess, hashlib, re
base=Path(__file__).parent
p=base/'after/scripts/ci/run_android_ui_tests.py'; sp=importlib.util.spec_from_file_location('ci',p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
fixtures=Path('/private/tmp/feoria-ci-v05-run42511850558-attempt1/artifact/ci-diagnostics')
report={'real_window_fixture_checks':[], 'shell_mirror_checks':[], 'limits':'Host controls only; no Android shell, adb, Gradle, or instrumentation executed'}
for phase in ['preflight','after-suite']:
 data={n:(fixtures/(phase+'-'+n+'.stdout')).read_text() for n in ['windows','displays','power','policy']}
 hashes={n:hashlib.sha256((fixtures/(phase+'-'+n+'.stdout')).read_bytes()).hexdigest() for n in data}
 # The old wrapper did not capture these three fields. Supply explicit fixture metadata only.
 data.update(boot='1',qemu='1',home=m.HOME_COMPONENT)
 result=m.readiness(data);assert result['verdict']=='KNOWN_LAUNCHER_ANR'
 report['real_window_fixture_checks'].append({'phase':phase,'result':result,'sha256':hashes,'synthetic_metadata':['boot','qemu','home']})
 for name in ['boot','qemu','home']:
  partial=dict(data);partial.pop(name);assert m.readiness(partial)['verdict']=='UNKNOWN'
green=Path('/private/tmp/feoria-ci-v05-run42507137388-attempt1/artifact/ci-diagnostics')
green_data={}
for phase in ['preflight','after-suite']:
 data={n:(green/(phase+'-'+n+'.stdout')).read_text() for n in ['windows','displays','power','policy']}
 hashes={n:hashlib.sha256((green/(phase+'-'+n+'.stdout')).read_bytes()).hexdigest() for n in data}
 data.update(boot='1',qemu='1',home=m.HOME_COMPONENT)
 expected='BOOT_TRANSITION' if phase=='preflight' else 'READY'
 assert m.readiness(data)['verdict']==expected
 green_data[phase]=data
 report['real_window_fixture_checks'].append({'phase':'green-'+phase,'result':m.readiness(data),'sha256':hashes,'synthetic_metadata':['boot','qemu','home']})
sp=importlib.util.spec_from_file_location('ci_tests',base/'after/scripts/ci/test_run_android_ui_tests.py');t=importlib.util.module_from_spec(sp);sp.loader.exec_module(t)
checker=t.WrapperChecks(methodName='runTest')
code,result,calls=checker.run_fake([green_data['preflight'],green_data['after-suite']])
assert code==0 and result['recovery_count']==0 and calls.count(['test-suite'])==1
report['real_boot_transition_controls']=[{'case':'real-green-preflight-to-real-green-after-suite','status':'PASS','test_calls':1,'recovery_count':0}]
from unittest.mock import patch
ticks=iter(range(1000))
with patch.object(t.ci.time,'monotonic',lambda:next(ticks)),patch.object(t.ci.time,'sleep',lambda _:None):
 code,result,calls=checker.run_fake([green_data['preflight']])
assert code==3 and not result['tests_started'] and result['recovery_count']==0 and 'budget exhausted' in result['environment_error']
report['real_boot_transition_controls'].append({'case':'real-green-FallbackHome-stuck','status':'PASS','test_calls':0,'recovery_count':0,'reason':result['environment_error']})
unknown=dict(green_data['after-suite']);unknown['displays']='  mCurrentFocus=null\n'
anr={n:(fixtures/('preflight-'+n+'.stdout')).read_text() for n in ['windows','displays','power','policy']};anr.update(boot='1',qemu='1',home=m.HOME_COMPONENT)
for label,state in [('ANR',anr),('UNKNOWN',unknown)]:
 code,result,calls=checker.run_fake([green_data['preflight'],state])
 assert code==3 and not result['tests_started'] and result['recovery_count']==0 and not any('force-stop' in call for call in calls)
 report['real_boot_transition_controls'].append({'case':'real-green-FallbackHome-to-'+label,'status':'PASS','test_calls':0,'recovery_count':0})
# Exercise the actual embedded export script, replacing only Kotlin template placeholders with temp paths.
kotlin=(base/'after/app/src/androidTest/java/ru/liturgyguide/ui/CiFailureDiagnostics.kt').read_text()
template=kotlin.split('script.writeText("""',1)[1].split('""".trimIndent()',1)[0]
for case in ['success','collision','copy-failure']:
 with tempfile.TemporaryDirectory() as temp:
  root=Path(temp); source=root/'app'/ 'first-failure-123';source.mkdir(parents=True);parent=root/'shell-run';parent.mkdir()
  destination=parent/source.name
  (source/'failure.txt').write_text('original failure\n'); (source/'capture.json').write_text('{"verdict":"DIAGNOSTIC_ONLY"}')
  if case=='collision': destination.mkdir();(destination/'keep').write_text('existing packet')
  command=template.replace('${directory.name}',source.name).replace('$destination',str(destination)).replace('$parent',str(parent)).replace('$source',str(source)).replace("${'$'}",'$')
  if case=='copy-failure': command=command.replace('cp -R ', 'false ')
  script=source/'mirror.sh';script.write_text(command)
  result=subprocess.run(['/bin/sh',str(script)],capture_output=True,text=True)
  assert result.returncode==(0 if case=='success' else 1)
  marker=destination/'.complete'
  assert marker.exists()==(case=='success')
  log=parent/(source.name+'.export.txt');assert log.is_file()
  assert ('MIRROR_EXIT=0' in log.read_text())==(case=='success')
  if case=='success':
   assert (destination/'failure.txt').read_bytes()==(source/'failure.txt').read_bytes()
   import shutil;shutil.rmtree(source)
   assert marker.read_text()=='COMPLETE\n' and (destination/'capture.json').is_file() and log.is_file()
  if case=='collision': assert (destination/'keep').read_text()=='existing packet'
  report['shell_mirror_checks'].append({'case':case,'exit_code':result.returncode,'complete_marker':marker.exists(),'export_log_retained':log.is_file(),'status':'PASS'})
(base/'host-evidence-results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'actual_window_fixtures':4,'real_boot_transition_controls':4,'required_missing_metadata_controls':6,'shell_mirror_controls':3,'status':'PASS'}))
