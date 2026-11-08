import importlib.util, json, sys
spec = importlib.util.spec_from_file_location('d', '/private/tmp/feoria-native-design-v05.py')
d = importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
phase = sys.argv[1]
run = d.BASE / ('schedule-' + phase)
manifest = {'schema': 1, 'package': d.PACKAGE, 'build_type': 'debug',
    'conditions': {'data': 'UI-created Temple_QA fixture, two personal starts, not a parish schedule',
                   'clock': 'device wall clock, expected active window verified by visible current state',
                   'location': 'not used', 'network': 'no provider assertion'},
    'cases': []}
for case_id, expected in [
    ('current', [{'text': 'Возможно, уже идет'}, {'text': '8 ноября · 18:00'}, {'text': 'Temple_QA'}]),
    ('next', [{'text': 'Следующая по вашему времени'}, {'text': '9 ноября · 18:00'}, {'text': 'Начало указано вами'}])]:
    manifest['cases'].append({'id': case_id, 'mode': 'static', 'conditions': {'data': '2026-11-08 and 2026-11-09 18:00 Europe/Moscow',
         'appearance': 'dark, internal font1.3, 1080x2400 at420dpi'}, 'expected': expected,
         'allowed_windows': d.ALLOW, 'observed_requirements': {'sdk': '35', 'font_scale': '1.0', 'timezone': 'Europe/Moscow',
             'wm_size': 'Physical size: 1080x2400', 'density': 'Physical density: 420'}, 'human_review_required': True})
path = d.BASE / ('schedule-' + phase + '-manifest.json')
d.h.write_json(path, manifest)
build = d.BASE / ('harness-accepted-build' if phase == 'baseline' else 'design-' + phase + '-build') / 'build.json'
d.h.begin_run(path, build, run, d.adb)
d.setup(run, 'shell', 'am', 'force-stop', d.PACKAGE)
d.setup(run, 'shell', 'run-as', d.PACKAGE, 'mkdir', '-p', 'shared_prefs')
d.setup(run, 'shell', 'run-as', d.PACKAGE, 'sh', '-c', "'cat > shared_prefs/guide_preferences.xml'", input_bytes=(d.BASE/'design-schedule-fixture.xml').read_bytes())
d.setup(run, 'shell', 'am', 'start', '-W', '-n', d.PACKAGE+'/.ui.MainActivity')
d.h.capture_case(run, d.adb, 'current', timeout=40)
print('CAPTURE current PASS', flush=True)
d.setup(run, 'shell', 'input', 'swipe', '540', '1800', '540', '1200', '600')
d.h.capture_case(run, d.adb, 'next', timeout=40)
print('CAPTURE next PASS', flush=True)
