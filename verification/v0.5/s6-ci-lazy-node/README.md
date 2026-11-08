# Narrow lazy-node CI diagnostics

CI42508996635:336unit PASS,119/120UI PASS. The sole failed assertion is before completion of the stale response, between two scroll operations after second search. A late IME onShown is recorded, but causality and production fault are not established. Earlier failure history is retained

The existing first-failure collector did not cover this assertion. This correction wraps the failing test body before its finally cleanup, records phase/input viewport and both Compose trees, early screenshot and OS data, and rethrows the identical Throwable. Test actions/assertions/scroll helpers/timeouts and all production files remain unchanged

Root and independent explicit Astra preflight/review ACCEPT. assembleDebugAndroidTest successful. The30s deadline limits read waits, not every filesystem/Binder operation. Timed-out workers cannot write late artifacts, but cancellation does not prove a read has terminated. Trees may synchronize Compose and are labeled post-failure, not atomic snapshots

The local test class and one full CI remain pending. No retry or recovery action is added. Native signed candidate06001192 remains byte-identical and its existing verification is separately scoped
