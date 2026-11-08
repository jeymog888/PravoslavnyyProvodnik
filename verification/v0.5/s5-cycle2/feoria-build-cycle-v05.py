from pathlib import Path
import importlib.util, sys, json
repo=Path('/Users/dmitriymoguschiy/Documents/Spectrion Projects/Новый-чат-21AE5054/PravoslavnyyProvodnik')
spec=importlib.util.spec_from_file_location('h',repo/'scripts/harness/feoria_harness.py')
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
phase=sys.argv[1]
classes=sys.argv[2] if len(sys.argv)>2 else 'ReaderAccessibilityTest,UnifiedPlayerFlowTest,GuideNavigationTest,ManualMomentFlowTest'
args=['--no-daemon','testDebugUnitTest','assembleDebug','connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+','.join('ru.liturgyguide.ui.'+x for x in classes.split(','))]
extra=list((repo/'app/src/androidTest').rglob('*.kt'))+list((repo/'app/src/test').rglob('*.kt'))
r=h.build_receipt(repo,repo/'app/build/outputs/apk/debug/app-debug.apk',repo/'app/build/verification/v0.5'/('design-'+phase+'-build'),'debug',args,'/private/tmp/liturgy-android-toolchain/sdk/build-tools/34.0.0/aapt','/private/tmp/liturgy-android-toolchain/sdk/build-tools/34.0.0/apksigner',extra_inputs=extra)
print(json.dumps({'status':r['status'],'apk_sha256':r['apk']['sha256']}))
