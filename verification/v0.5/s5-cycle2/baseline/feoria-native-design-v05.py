from pathlib import Path
import argparse, importlib.util, json, subprocess, sys, time

REPO = Path('/Users/dmitriymoguschiy/Documents/Spectrion Projects/Новый-чат-21AE5054/PravoslavnyyProvodnik')
spec = importlib.util.spec_from_file_location('h', REPO / 'scripts/harness/feoria_harness.py')
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
adb = h.Adb('/private/tmp/liturgy-android-toolchain/sdk/platform-tools/adb', 'emulator-5554')
PACKAGE = 'ru.liturgyguide'
ALLOW = [{'package': 'com.google.android.apps.nexuslauncher', 'title': 'Taskbar'}]
BASE = REPO / 'app/build/verification/v0.5'

def label(value, kind='text'):
    return {kind: value}

def selected(value):
    return {'text': value, 'ancestor': {'class': 'android.view.View', 'selected': True}}

def paused(enabled=True):
    return {'content-desc': 'Включить плеер', 'ancestor': {'class': 'android.view.View', 'enabled': enabled, 'clickable': True}}

def view():
    return adb.view(PACKAGE, ALLOW)

def show():
    v = view()
    for n in v.nodes:
        value = n.get('text') or n.get('content-desc')
        if value:
            print(value, n.get('bounds'))

def setup(run, *args, input_bytes=None):
    command = [adb.executable, '-s', adb.serial, *args]
    started = time.time_ns()
    result = subprocess.run(command, input=input_bytes, capture_output=True, timeout=40)
    entry = {'started_ns': started, 'command': command, 'exit': result.returncode,
             'stdout': result.stdout.decode(errors='replace')[:2000], 'stderr': result.stderr.decode(errors='replace')[:2000],
             'input_sha256': h.digest(input_bytes) if input_bytes is not None else None,
             'note': 'explicit fixture/navigation setup; not an automatically accepted scenario'}
    with (run / 'setup.jsonl').open('a') as stream:
        stream.write(json.dumps(entry, ensure_ascii=False) + '\n')
    h.require(result.returncode == 0, 'setup command failed')
    return result.stdout

def initialize(phase):
    run = BASE / ('design-' + phase)
    after = phase != 'baseline'
    cases = [
        ('fresh', [label('Выбрать храм'), label('Начните с выбора храма')], 'fresh install, light theme, internal scale 1.0'),
        ('current-service', [label('Temple_QA'), label('Начало указано вами'), label('Смотреть службу')], 'UI-created cycle1 fixture: Kazan Temple_QA, personal start 2026-11-08 18:00 Europe/Moscow, light theme'),
        ('creed-original', [label('Символ веры'), selected('Оригинал'), paused(),
                            label('Иже от Отца рожденнаго прежде всех век.'), label('На паузе · фраза 4 из 20')],
                            '2026-11-08, creed full text, phrase index3, original, dark, internal scale1.3; paused observed'),
        ('creed-meaning', [label('Символ веры'), selected('Смысл'), label('Что происходит' if after else 'Об этом тексте')],
                            'same creed phrase3, dark internal scale1.3, Meaning pauses by separately tested runtime contract'),
        ('missing-reading', [paused(False), selected('По-русски'), label('Текста чтения пока нет' if after else 'Чтение нужно пройти вручную')],
                            '2026-11-09 epistle missing reading, dark internal scale1.3, disabled Play observed'),
    ]
    manifest = {'schema': 1, 'package': PACKAGE, 'build_type': 'debug',
        'conditions': {'data': 'explicit local fixtures, not live parish schedule', 'clock': 'device clock, no injected clock',
                       'location': 'not used', 'network': 'device settings recorded, no provider availability claim'},
        'cases': [{'id': name, 'mode': 'static', 'conditions': {'scene': description},
                   'expected': expected, 'human_review_required': True, 'allowed_windows': ALLOW,
                   'observed_requirements': {'sdk': '35', 'font_scale': '1.0', 'timezone': 'Europe/Moscow',
                                             'wm_size': 'Physical size: 1080x2400', 'density': 'Physical density: 420'}}
                  for name, expected, description in cases]}
    manifest_path = BASE / ('design-' + phase + '-manifest.json')
    h.write_json(manifest_path, manifest)
    build = BASE / ('harness-accepted-build' if phase == 'baseline' else 'design-' + phase + '-build') / 'build.json'
    h.begin_run(manifest_path, build, run, adb)
    if phase == 'baseline' and not (BASE / 'design-cycle1-fixture.xml').exists():
        saved = adb.run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/guide_preferences.xml')
        (BASE / 'design-cycle1-fixture.xml').write_bytes(saved)
    setup(run, 'shell', 'pm', 'clear', PACKAGE)
    setup(run, 'shell', 'am', 'start', '-W', '-n', PACKAGE + '/.ui.MainActivity')
    print(run)

if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('--phase', default='baseline'); p.add_argument('action'); p.add_argument('args', nargs='*'); a = p.parse_args()
    run = BASE / ('design-' + a.phase)
    if a.action == 'init': initialize(a.phase)
    elif a.action == 'show': show()
    elif a.action == 'capture': print(json.dumps(h.capture_case(run, adb, a.args[0], timeout=40), ensure_ascii=False))
    elif a.action == 'tap':
        case, field, value, expected_field, expected_value = a.args
        print(json.dumps(h.tap(run, adb, case, label(value, field), [label(expected_value, expected_field)], timeout=30), ensure_ascii=False))
    elif a.action == 'swipe':
        v = view(); width, height = v.size
        y1, y2 = ((.75, .35) if a.args[0] == 'up' else (.35, .75))
        setup(run, 'shell', 'input', 'swipe', str(width//2), str(int(height*y1)), str(width//2), str(int(height*y2)), '300')
    elif a.action == 'restore-cycle1-fixture':
        setup(run, 'shell', 'am', 'force-stop', PACKAGE)
        setup(run, 'shell', 'run-as', PACKAGE, 'mkdir', '-p', 'shared_prefs')
        setup(run, 'shell', 'run-as', PACKAGE, 'sh', '-c', "'cat > shared_prefs/guide_preferences.xml'", input_bytes=(BASE / 'design-cycle1-fixture.xml').read_bytes())
        setup(run, 'shell', 'am', 'start', '-W', '-n', PACKAGE + '/.ui.MainActivity')
    elif a.action == 'raw': print(setup(run, *a.args).decode(errors='replace'))
    else: raise ValueError(a.action)
