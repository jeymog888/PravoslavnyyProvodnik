#!/usr/bin/env python3
"""Run three approved mutation controls in an isolated copy, only with --run.

No adb, APK install, signing files, or writes to repository source inputs.
Requires an idle Gradle toolchain and frozen repository sources.
"""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


DEFAULT_REPO = Path('/Users/dmitriymoguschiy/Documents/Spectrion Projects/Новый-чат-21AE5054/PravoslavnyyProvodnik')
TOOLCHAIN = Path('/private/tmp/liturgy-android-toolchain')
EXCLUDED_PARTS = {'.git', '.local', '.gradle', 'build', '.idea', '__pycache__'}
ROOT_FILES = {'gradlew', 'gradlew.bat', 'build.gradle', 'build.gradle.kts',
              'settings.gradle', 'settings.gradle.kts', 'gradle.properties'}
APP_FILES = {'build.gradle', 'build.gradle.kts', 'proguard-rules.pro'}
SOURCE_ROOTS = ('app/src', 'gradle', 'buildSrc', 'build-logic')

CONTROLS = [
    {
        'id': 'stale-generation',
        'source': 'app/src/main/java/ru/liturgyguide/ui/ServicePlayerState.kt',
        'before': '    fun tick(expectedGeneration: Int) {\n        if (expectedGeneration != generation) return\n',
        'after': '    fun tick(expectedGeneration: Int) {\n',
        'class': 'ru.liturgyguide.ui.ServicePlayerStateTest',
        'method': 'a delayed tick from a replaced reading cannot touch its successor',
        'assertion': 'assertEquals(accepted, h.current)',
    },
    {
        'id': 'missing-reading-barrier',
        'source': 'app/src/main/java/ru/liturgyguide/service/ServicePlaybackEngine.kt',
        'before': '            val duration = plan.durationQuarterMillis(index)\n                ?: return state.copy(segmentIndex = index, elapsedQuarterMillis = 0, anchorMonotonicMillis = null)',
        'after': '            val duration = plan.durationQuarterMillis(index)\n                ?: if (plan.segments[index] is PlaybackSegment.MissingReading) 0L\n                else return state.copy(segmentIndex = index, elapsedQuarterMillis = 0, anchorMonotonicMillis = null)',
        'class': 'ru.liturgyguide.service.ServicePlaybackEngineTest',
        'method': 'large tick cannot jump a missing reading and leftover time is discarded',
        'assertion': 'assertTrue(engine.plan.segments[missing.segmentIndex] is PlaybackSegment.MissingReading)',
    },
    {
        'id': 'implicit-fresh-parish',
        'source': 'app/src/main/java/ru/liturgyguide/service/ChurchSelection.kt',
        'before': '        if (hasPlaybackSnapshot || !hasLegacyReading) return null',
        'after': '        if (hasPlaybackSnapshot) return null',
        'class': 'ru.liturgyguide.service.ChurchSelectionTest',
        'method': 'fresh installation has no parish and cannot receive a default schedule',
        'assertion': 'assertNull(resolve())',
    },
]
EXPECTED_TESTS = {(c['class'], c['method']) for c in CONTROLS}


class GateError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    return digest(Path(path).read_bytes())


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def git(repo, *args):
    p = subprocess.run(['git', *args], cwd=repo, capture_output=True, timeout=30)
    require(p.returncode == 0, 'Git inspection failed: ' + p.stderr.decode(errors='replace')[:500])
    return p.stdout


def allowed(relative):
    parts = relative.parts
    if any(part in EXCLUDED_PARTS for part in parts):
        return False
    if relative.name in {'local.properties', '.env'} or relative.suffix in {'.jks', '.keystore', '.pem', '.key'}:
        return False
    return (len(parts) == 1 and relative.name in ROOT_FILES or
            len(parts) == 2 and parts[0] == 'app' and relative.name in APP_FILES or
            any(relative == Path(root) or Path(root) in relative.parents for root in SOURCE_ROOTS))


def source_files(repo):
    # Tracked files use their current working-tree contents, including local edits.
    candidates = {Path(os.fsdecode(p)) for p in git(repo, 'ls-files', '-z').split(b'\0') if p}
    # Include new source files and assets which are not committed yet.
    for root in SOURCE_ROOTS:
        base = repo / root
        if base.exists():
            candidates.update(p.relative_to(repo) for p in base.rglob('*') if p.is_file() or p.is_symlink())
    files = []
    for relative in sorted(candidates):
        if not allowed(relative):
            continue
        require(not relative.is_absolute() and '..' not in relative.parts, 'Unsafe source path')
        path = repo / relative
        require(not path.is_symlink() and not any((repo / p).is_symlink() for p in relative.parents),
                'Symlink source is not supported: ' + str(relative))
        if path.is_file():
            files.append(relative)
    require(Path('gradlew') in files and Path('app/build.gradle.kts') in files, 'Incomplete build inputs')
    return files


def fingerprint(root, files):
    entries = [{'path': str(p), 'sha256': file_hash(root / p)} for p in files]
    return {'sha256': digest(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()), 'files': entries}


def root_fingerprint(repo):
    return fingerprint(repo, source_files(repo))


def assertion_location(repo, control):
    relative = Path('app/src/test/java') / (control['class'].replace('.', '/') + '.kt')
    text = (repo / relative).read_text(encoding='utf-8')
    marker = 'fun `' + control['method'] + '`'
    require(text.count(marker) == 1, 'Expected test method must exist exactly once: ' + control['method'])
    start = text.index(marker)
    end = text.find('@Test', start + len(marker))
    segment = text[start:] if end < 0 else text[start:end]
    require(segment.count(control['assertion']) == 1, 'Expected assertion changed: ' + control['id'])
    offset = start + segment.index(control['assertion'])
    return f'{relative.name}:{text[:offset].count(chr(10)) + 1}'


def run_gradle(project, environment, command, log_path, timeout):
    started_ns = time.time_ns()
    result = {'started_ns': started_ns, 'command': command, 'cwd': str(project), 'timed_out': False}
    with log_path.open('wb') as log:
        try:
            proc = subprocess.Popen(command, cwd=project, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, start_new_session=True)
            try:
                result['exit_code'] = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                result['timed_out'] = True
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    proc.wait(timeout=5)
                result['exit_code'] = proc.returncode
            except BaseException:
                # Never leave this runner's Gradle process group behind on interruption.
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                proc.wait(timeout=5)
                raise
        except OSError as error:
            result['exit_code'] = None
            result['process_error'] = str(error)
    result['finished_ns'] = time.time_ns()
    result['log_sha256'] = file_hash(log_path)
    return result


def check_junit(xml_dir, expected_failure=None):
    xml_files = sorted(xml_dir.glob('TEST-*.xml'))
    require(xml_files, 'No fresh JUnit XML: compile/environment/no-tests failure is not a mutation kill')
    cases = []
    for path in xml_files:
        suite = ET.parse(path).getroot()
        require(suite.tag == 'testsuite', 'Unexpected JUnit root: ' + path.name)
        tests = suite.findall('testcase')
        require(int(suite.get('tests', '-1')) == len(tests), 'JUnit test count disagrees with cases')
        counts = {tag: sum(len(t.findall(tag)) for t in tests) for tag in ('failure', 'error', 'skipped')}
        for tag, attr in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
            require(int(suite.get(attr, '-1')) == counts[tag], 'JUnit aggregate disagrees: ' + attr)
        for test in tests:
            failure = test.find('failure')
            require(len(test.findall('failure')) <= 1, 'Multiple failures on one test')
            require(test.find('error') is None and test.find('skipped') is None, 'JUnit error/skip is not accepted')
            cases.append({'class': test.get('classname'), 'method': test.get('name'),
                          'status': 'FAIL' if failure is not None else 'PASS',
                          'failure_type': failure.get('type') if failure is not None else None,
                          'failure_trace': failure.text or '' if failure is not None else '',
                          'xml': path.name})
    actual = [(c['class'], c['method']) for c in cases]
    require(len(actual) == 3 and set(actual) == EXPECTED_TESTS, 'Expected exactly the three selected JUnit tests')
    failed = [c for c in cases if c['status'] == 'FAIL']
    if expected_failure is None:
        require(not failed, 'Clean baseline must pass all three tests')
    else:
        require(len(failed) == 1, 'A mutant must fail exactly one expected test and pass the other two')
        failure = failed[0]
        require((failure['class'], failure['method']) == (expected_failure['class'], expected_failure['method']),
                'A different test failed')
        require(failure['failure_type'] == 'java.lang.AssertionError', 'Mutation did not fail by the expected AssertionError')
        require(expected_failure['assertion_location'] in failure['failure_trace'],
                'Failure did not reach the predeclared assertion line')
    return cases


def run_stage(name, repo, project, files, original, output, environment, timeout, expected=None):
    stage = output / name
    stage.mkdir()
    result = {'id': name, 'status': 'FAIL', 'expected_failure': expected}
    try:
        require(root_fingerprint(repo) == original, 'Repository inputs changed before ' + name)
        result['inputs_before'] = fingerprint(project, files)
        results_dir = project / 'app/build/test-results/testDebugUnitTest'
        reports_dir = project / 'app/build/reports/tests/testDebugUnitTest'
        # Only generated results in this fresh isolated copy are removed.
        for path in (results_dir, reports_dir):
            if path.exists():
                shutil.rmtree(path)
        command = [str(project / 'gradlew'), '--no-daemon', ':app:testDebugUnitTest',
                   '--no-build-cache', '--rerun-tasks', '--console=plain']
        for control in CONTROLS:
            command += ['--tests', control['class'] + '.' + control['method']]
        result.update(run_gradle(project, environment, command, stage / 'gradle.log', timeout))
        result['inputs_after'] = fingerprint(project, files)
        result['repository_inputs_after'] = root_fingerprint(repo)
        junit = stage / 'junit'
        junit.mkdir()
        if results_dir.exists():
            for xml in results_dir.glob('TEST-*.xml'):
                shutil.copy2(xml, junit / xml.name)
        result['junit_hashes'] = [{'file': p.name, 'sha256': file_hash(p)} for p in sorted(junit.glob('*.xml'))]
        require(not result['timed_out'] and not result.get('process_error'), 'Gradle timeout/process failure')
        require(result['inputs_before'] == result['inputs_after'], 'Isolated inputs changed during Gradle')
        require(result['repository_inputs_after'] == original, 'Repository inputs changed during Gradle')
        require(result['exit_code'] == (1 if expected else 0), 'Unexpected Gradle exit code')
        result['tests'] = check_junit(junit, expected)
        result['status'] = 'PASS'
    except (GateError, ET.ParseError, ValueError, OSError, subprocess.SubprocessError) as error:
        result['error'] = str(error)
    write_json(stage / 'result.json', result)
    print(name + ': ' + result['status'], flush=True)
    require(result['status'] == 'PASS', name + ': ' + result.get('error', 'failed'))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=DEFAULT_REPO)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--timeout', type=int, default=900, help='Per Gradle invocation, seconds')
    parser.add_argument('--run', action='store_true', help='Execute only after root review; no execution by default')
    args = parser.parse_args()
    require(args.run, 'Execution requires explicit --run after root review')
    require(60 <= args.timeout <= 1800, 'Timeout must be 60..1800 seconds per invocation')
    repo = args.repo.resolve()
    output = (args.output or repo / 'app/build/verification/v0.5/mutations').resolve()
    require(repo / 'app/build/verification/v0.5' in output.parents, 'Output must be a new child of app/build/verification/v0.5')
    require(not output.exists(), 'Output directory already exists; prior evidence will not be overwritten')
    files = source_files(repo)
    original = fingerprint(repo, files)
    prepared = []
    for control in CONTROLS:
        content = (repo / control['source']).read_text(encoding='utf-8')
        require(content.count(control['before']) == 1, 'Mutation anchor changed: ' + control['id'])
        prepared.append({**control, 'assertion_location': assertion_location(repo, control)})
    output.mkdir(parents=True)
    project = Path(tempfile.mkdtemp(prefix='feoria-v05-mutations-', dir='/private/tmp')) / 'project'
    project.mkdir()
    report = {'schema': 1, 'status': 'FAIL', 'started_ns': time.time_ns(), 'repo': str(repo),
              'isolated_project': str(project), 'runner_sha256': file_hash(__file__),
              'source_commit': git(repo, 'rev-parse', 'HEAD').decode().strip(),
              'source_inputs': original, 'controls': prepared, 'stages': []}
    environment = os.environ.copy()
    environment.update(JAVA_HOME=str(TOOLCHAIN / 'jdk-17.0.21+9/Contents/Home'),
                       GRADLE_USER_HOME=str(TOOLCHAIN / 'gradle-home'),
                       ANDROID_HOME=str(TOOLCHAIN / 'sdk'),
                       ANDROID_SDK_ROOT=str(TOOLCHAIN / 'sdk'),
                       ANDROID_USER_HOME=str(TOOLCHAIN / 'android-user'))
    try:
        for relative in files:
            target = project / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(repo / relative, target)
        require(fingerprint(project, files) == original and root_fingerprint(repo) == original, 'Copy does not match frozen inputs')
        shutil.copy2(__file__, output / 'runner.py')
        write_json(output / 'manifest.json', report)
        report['stages'].append(run_stage('baseline', repo, project, files, original, output, environment, args.timeout))
        for control in prepared:
            target = project / control['source']
            clean = target.read_text(encoding='utf-8')
            mutated = clean.replace(control['before'], control['after'], 1)
            difference = ''.join(difflib.unified_diff(clean.splitlines(keepends=True), mutated.splitlines(keepends=True),
                                                     fromfile='a/' + control['source'], tofile='b/' + control['source']))
            (output / (control['id'] + '.patch')).write_text(difference, encoding='utf-8')
            try:
                target.write_text(mutated, encoding='utf-8')
                report['stages'].append(run_stage(control['id'], repo, project, files, original, output,
                                                 environment, args.timeout, expected=control))
            finally:
                target.write_text(clean, encoding='utf-8')
                require(fingerprint(project, files) == original, 'Failed to restore isolated inputs')
        report['stages'].append(run_stage('restored', repo, project, files, original, output, environment, args.timeout))
        require(len(report['stages']) == 5, 'Incomplete mutation sequence')
        report['status'] = 'PASS'
    except Exception as error:
        report['error'] = str(error)
    finally:
        report['finished_ns'] = time.time_ns()
        integrity_errors = []
        for label, collect in [('repository_inputs_after', lambda: root_fingerprint(repo)),
                               ('isolated_inputs_after', lambda: fingerprint(project, files))]:
            try:
                report[label] = collect()
                if report[label] != original:
                    integrity_errors.append(label + ' differs from the clean baseline')
            except Exception as error:
                integrity_errors.append(label + ': ' + str(error))
        if integrity_errors:
            report['status'] = 'FAIL'
            report['integrity_errors'] = integrity_errors
        write_json(output / 'result.json', report)
    print(json.dumps({'status': report['status'], 'evidence': str(output),
                      'isolated_project': str(project), 'error': report.get('error')}, ensure_ascii=False), flush=True)
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (GateError, OSError, subprocess.SubprocessError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
