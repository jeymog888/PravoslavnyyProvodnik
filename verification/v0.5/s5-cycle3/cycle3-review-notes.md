# Cycle3 review notes - not release acceptance

Runtime preflight and compiled bytecode review accepted by Astra. Four local Kotlin FunctionReferenceImpl callbacks compared equal despite captured generation; nested Compose button kept an obsolete callback after accepting the same reading. Replaced with lambdas retaining generation and current stage captures. Real 18-stage landscape interaction and two regression tests now pass

Attempt history remains preserved:
- cycle3 initial: 69/72 UI, wrong notice action expectation, IME cancellation timing, real stale tab callback
- diagnostic: 1/3, notice updated; cancellation assertion passed, result viewport and stale tab still failed
- callback diagnostic: semantic action also failed at current owner generation; not a physical touch issue, failure was not converted to PASS
- fixed: 72/74 UI, all9 matrix pass, remaining IME city-selection and startup diagnostic semantics race
- ready: 14/15 UI, Reader all8 pass; loading viewport failed because a hidden-IME observation could precede queued IME show
- ready2: 15/15 UI and325unit PASS, observes IME visible before submitting, then hidden/bottom0, bounded platform idle. Real touch and assertions retained

Reader startup readiness checks RESUMED, focus and nonzero decor, then bounded native idle and Compose idle. It does not compare a synthetic font/layout viewport to physical decor dimensions. Compose1.7.3 fetchOneOrThrow unconditionally stores printToString after a successful node lookup; observed SnapshotStateObserver stack was inside that diagnostic traversal, not proof of application worker-thread layout

Deferred Stage6 P2: UserServiceStartStore and ChurchDirectoryStore lack rollback after SharedPreferences commit false changes memory. Separate patch in preparation, not mixed with cycle3 build


Native iteration:
- run1 stopped on a real app-owned popup because old harness assumed a package in the focus title. Focus now resolves exact OS inventory identity and owner; 52 Python checks pass; separate commit eda0762
- run2 accepted six visual screens by Astra. Wait/End runtime worked, including 265.38s real-clock transition at1.25x. Their visual result REJECT: default fully rounded TextButton with zero horizontal padding cut first glyphs in the two-line header. Last-stage Wait also incorrectly promised a next stage
- minimal RectangleShape fixes only the invisible text-button clipping mask. Last-stage Wait wording now asks to keep listening to the final prayers. Existing End UI test additionally traverses Wait and checks correct wording before retaining all End assertions
- final build:325unit +28UI PASS,0fail/error/skip. APK ef1ad12a47130e4ef526fbfb31b968734d9680053d69e3033d4d3a6efd650adb
- run3 fresh full eight-screen corpus completed8/8 with root and Astra review, aggregatePASS. End uses a declared snapshot produced by real-clock run2, cold-restored in the corrected candidate; it is not claimed as a second real-time transition. Wait enters through real UI and4s Play again
