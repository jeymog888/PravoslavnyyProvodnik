# Recents release defect - before fix

Candidate APK SHA256 `ad44d9b7f20a88672c4099e6329327216366d2114fc2189a73549f8497c785a3`, code5/name0.5.0-guide, API35 owned emulator5554. This candidate is rejected for release pending correction

Root enabled actual Play at full Creed phrase8, then opened Recents. The actual launcher screenshot shows phrase9. Returning through the visible Feoria card after about64seconds shows the end of Creed/Wait with Stop still visible. Root saw both PNGs. No next/seek/clock/preferences injection was used in this interval

The follow-up system diagnosis captured `wm_on_top_resumed_lost_called` for MainActivity at1794160392.547 and gained at1794160456.857, with no MainActivity pause/stop between. A second Recents departure at1794160592.813 repeats top-resumed loss. MainActivity remains RESUMED, while Launcher is focused. Existing player only watches lifecycle pause/stop/destroy, so this user-visible drift is not covered by the previous local119UI tests

[Android Activity contract](https://developer.android.com/reference/android/app/Activity#onTopResumedActivityChanged(boolean)) supports using top-resumed ownership on API29+ separately from Lifecycle. Ordinary window focus would also pause inside the app's own tempo dialog, which must continue to support speed changes while playing

Root and independent Astra preflight accepted a synchronous top-resumed-loss pause plus live ticker gate; gain never resumes automatically. Existing Lifecycle fallback remains on API26-28. A regression should coalesce loss and gain inside one main-thread call, verify the saved exact offset before any Compose idle, and preserve the existing tempo-dialog expectations. Actual signed-APK Recents retest remains required

The parent exploratory native run also contains unrelated wrong test expectations, retained in DIAGNOSTIC.md. Those are not the cause or evidence of this product defect. No clean release aggregate is claimed here

## Accepted correction and targeted verification

ActivityForegroundState synchronously pauses on API29+ top-resumed loss. A binding token prevents stale disposal from removing a replacement callback. Lifecycle fallback remains on API26-28. Regaining the top position never calls Play. The ticker also checks live lifecycle/top ownership

Root and independent Astra source review ACCEPT, patch SHA256 03f1edeb6924152f6b5cf5a2338bd3794df719ea183f93f0ead1bab80e48786f. Targeted verification:336 unit,38 UI, failures/errors/skips=0; lint and debug assemblies successful. The new test verifies exact saved offset before recomposition when loss/gain coalesce; existing speed-dialog expectations remain unchanged and pass. Self-review fixed stale-disposer ownership and avoided relying only on a Compose effect

These debug checks do not replace the pending new signed APK Recents retest. The previous ad44 candidate remains rejected
