# CI lazy-node diagnostics - frozen for source review

Base: `57e48a5f9af879e196ac17ee517fcee0108d0800`
Patch SHA256: `ca4ab6703347e1d16a122ec77da66e41a726ecf87579e69d50a089d9826bebe2`

Only two authorized androidTest files changed. No production, workflow, dependency, test-input, assertion, scroll or wait-timeout changes

The cancellation test keeps its original body inside withCiFailureDiagnostics, before the outer finally cancels oldResult. Local phase assignments distinguish first search, cancel, second search, first Kazan check, late result completion, second Kazan check and manual navigation

The existing opt-in first-failure collector accepts an optional two-phase callback before the trailing block. BEFORE_SCREENSHOT saves phase, target, actual query/center copies, deferred status and clock values; a separate bounded scenario.onActivity read samples IME visibility/inset, decor geometry, visible frame, lifecycle and window focus. Early screen.png precedes merged/unmerged tree reads. Existing OS reads follow. Each part has start/finish timestamps and outcome; tree files explicitly state that Compose may synchronize before reading

One deadline of 30 seconds starts when capture is accepted. Each additional read uses at most 3 seconds of the remaining budget. FutureTask workers return strings only; the test thread writes artifacts after a successful get. A timed-out worker cannot overwrite evidence later. Errors in any ordinary read are recorded separately; no retry or UI action is added. The outer wrapper throws the same original Throwable

Self-review risks addressed:

- Direct scenario/tree calls could block diagnostic completion past the shared deadline, so each read is bounded independently
- Writing files from a worker could produce late artifacts after Activity cleanup, so only the waiting test thread writes them
- Evidence is not atomic with the assertion or other captures; timing metadata and the tree note prevent treating a later synchronized tree as the exact failure instant
- Existing nested IME waits can still claim the first packet before the outer catch. This patch targets the uncovered body/scroll/assertion path; first-failure-only semantics remain unchanged

Static verification: git diff --check passed. After removing diagnostic phase assignments, every original statement in the failing test body is unchanged; keyboard waits and scrollTo helper are byte-identical to HEAD. All 71 non-external build inputs and the existing signed APK remain unchanged (`06001192794dc7ddfaf7bcf950265e761e8a9d06758eb2f280d0b1a70be3b592`)

No adb, Gradle, test execution, CI launch or commit. Root and Astra must review before compilation; this is diagnostic coverage, not a claimed product or CI root-cause fix
