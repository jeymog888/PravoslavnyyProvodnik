import importlib.util, sys
spec = importlib.util.spec_from_file_location('d', '/private/tmp/feoria-native-design-v05.py')
d = importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
phase = sys.argv[1]
run = d.BASE / ('design-' + phase)

def restore(name):
    d.setup(run, 'shell', 'am', 'force-stop', d.PACKAGE)
    d.setup(run, 'shell', 'run-as', d.PACKAGE, 'mkdir', '-p', 'shared_prefs')
    d.setup(run, 'shell', 'run-as', d.PACKAGE, 'sh', '-c', "'cat > shared_prefs/guide_preferences.xml'", input_bytes=(d.BASE/name).read_bytes())
    d.setup(run, 'shell', 'am', 'start', '-W', '-n', d.PACKAGE+'/.ui.MainActivity')

def resume(case_id, expected):
    selector = {'text': 'Сохраненное чтение'}
    for attempt in range(7):
        v = d.view()
        try:
            v.target(selector)
        except d.h.GateError:
            if attempt == 6: raise
            width, height = v.size
            d.setup(run, 'shell', 'input', 'swipe', str(width//2), str(int(height*.75)), str(width//2), str(int(height*.45)), '600')
        else:
            d.h.tap(run, d.adb, case_id, selector, expected, timeout=30)
            return

d.initialize(phase)
if not phase.startswith('cycle2b'):
    d.h.capture_case(run, d.adb, 'fresh', timeout=40)
    print('CAPTURE fresh PASS', flush=True)
    restore('design-cycle1-fixture.xml')
    d.h.capture_case(run, d.adb, 'current-service', timeout=40)
    print('CAPTURE personal-service PASS', flush=True)
restore('design-creed-fixture.xml')
resume('creed-original', [d.selected('Оригинал'), d.paused()])
d.h.capture_case(run, d.adb, 'creed-original', timeout=40)
print('CAPTURE original PASS', flush=True)
d.h.tap(run, d.adb, 'creed-meaning', {'text':'Смысл'}, [d.selected('Смысл'), {'text':'Что происходит'}], timeout=30)
d.h.capture_case(run, d.adb, 'creed-meaning', timeout=40)
print('CAPTURE meaning PASS', flush=True)
restore('design-missing-fixture.xml')
resume('missing-reading', [d.paused(False), {'text':'Тексты на 9 ноября 2026'}])
d.h.capture_case(run, d.adb, 'missing-reading', timeout=40)
print('CAPTURE missing PASS', flush=True)
