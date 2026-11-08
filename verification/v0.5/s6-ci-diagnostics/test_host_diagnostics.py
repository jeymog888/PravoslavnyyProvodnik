"""Host controls: all subprocesses mocked, no device or Gradle execution."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('ci_driver',Path(__file__).parent/'proposed/scripts/ci/run_android_ui_tests.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Controls(unittest.TestCase):
    def run_fake(self, test_code, diagnostic_error=False, write_error=False):
        with tempfile.TemporaryDirectory() as temp:
            out=Path(temp)/'evidence';calls=[]
            def fake(argv,**kwargs):
                calls.append(argv)
                if argv[0]=='./gradlew':return subprocess.CompletedProcess(argv,test_code)
                if diagnostic_error:raise subprocess.TimeoutExpired(argv,5)
                return subprocess.CompletedProcess(argv,0,b'\x89PNG\r\n\x1a\nfixture' if argv[-2:]==['screencap','-p'] else b'observed',b'')
            original=Path.write_text
            def write(path,*args,**kwargs):
                if write_error and any(c[0]=='./gradlew' for c in calls):raise OSError('diagnostic disk failure')
                return original(path,*args,**kwargs)
            args=['ci','--serial','fixture-serial','--run-id','42-2','--out',str(out),'--','./gradlew','connectedDebugAndroidTest']
            with patch.object(sys,'argv',args),patch.object(subprocess,'run',side_effect=fake),patch.object(Path,'write_text',write):
                result=m.main()
            return result,calls
    def test_original_failure_code_and_pull_after_test(self):
        result,calls=self.run_fake(17)
        self.assertEqual(result,17)
        index=next(i for i,a in enumerate(calls) if a[0]=='./gradlew')
        self.assertGreater(next(i for i,a in enumerate(calls) if 'pull' in a),index)
        self.assertTrue(all(a[1:3]==['-s','fixture-serial'] for a in calls if a[0]=='adb'))
    def test_read_timeouts_do_not_replace_test_failure(self):
        result,calls=self.run_fake(23,diagnostic_error=True)
        self.assertEqual(result,23)
        self.assertEqual(sum(c[0]=='./gradlew' for c in calls),1)
    def test_post_capture_io_error_preserves_original_exit(self):
        result,_=self.run_fake(19,write_error=True)
        self.assertEqual(result,19)
    def test_preflight_never_normalizes_screen_or_retries(self):
        result,calls=self.run_fake(0)
        self.assertEqual(result,0)
        self.assertFalse(any(any(arg in ('stayon','dismiss-keyguard','keyevent','put') for arg in c) for c in calls))
        self.assertEqual(sum(c[0]=='./gradlew' for c in calls),1)
if __name__=='__main__':unittest.main()
