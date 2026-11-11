package ru.liturgyguide.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.location.LocationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt in on the emulator only. The caller sets permissions/location and injects a synthetic GPS fix. */
@RunWith(AndroidJUnit4::class)
class ActualChurchLocatorTest {
    // Foreground-only location permission requires a resumed application window.
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun actualPermissionAndSystemProviderPathCompletesWithinItsDeadline() {
        val expectation = InstrumentationRegistry.getArguments().getString("locationExpectation")
        assumeTrue("Explicit emulator scenario required", expectation in setOf("coarse", "fine", "disabled", "denied"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        when (expectation) {
            "coarse" -> { assertTrue("Grant coarse permission before starting the test", coarse); assertFalse("Revoke fine before starting the test", fine) }
            "fine" -> assertTrue("Grant fine permission before starting the test", fine)
            "denied" -> { assertFalse("Revoke coarse before starting the test", coarse); assertFalse("Revoke fine before starting the test", fine) }
            "disabled" -> { assertTrue("Keep foreground permission granted for the disabled scenario", coarse || fine)
                assertFalse("Disable system location before starting the test", LocationManagerCompat.isLocationEnabled(manager)) }
        }
        if (expectation == "coarse" || expectation == "fine")
            assertTrue("Enable system location and inject a fresh synthetic GPS fix", LocationManagerCompat.isLocationEnabled(manager))
        val result = AtomicReference<ForegroundLocationResult>()
        val callbacks = AtomicInteger()
        val completed = CountDownLatch(1)
        var locator: ChurchLocator? = null
        val started = SystemClock.elapsedRealtime()
        try {
            instrumentation.runOnMainSync {
                val current = ChurchLocator(context).also { locator = it }
                current.locateResult(maxAccuracyMeters = 5_000f) {
                    callbacks.incrementAndGet(); result.set(it); completed.countDown()
                }
            }
            assertTrue("System location request did not finish within 25 seconds", completed.await(25, TimeUnit.SECONDS))
            assertTrue("Foreground deadline exceeded", SystemClock.elapsedRealtime() - started <= 25_000L)
            when (expectation) {
                "coarse", "fine" -> {
                    val located = result.get() as? ForegroundLocationResult.Located
                    assertNotNull("Expected a synthetic system fix; failure=" + (result.get() as? ForegroundLocationResult.Failed)?.reason, located)
                    val fix = requireNotNull(located).fix
                    assertTrue("Returned fix did not meet the same age/accuracy policy used by nearby search",
                        LocationFixPolicy.accepts(fix.latitude, fix.longitude, fix.accuracyMeters ?: Float.NaN,
                            fix.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(), 5_000f, 120_000L))
                }
                "denied" -> assertEquals(LocationFailure.PERMISSION, (result.get() as? ForegroundLocationResult.Failed)?.reason)
                "disabled" -> assertEquals(LocationFailure.DISABLED, (result.get() as? ForegroundLocationResult.Failed)?.reason)
            }
            assertEquals("Exactly one result per request", 1, callbacks.get())
        } finally {
            instrumentation.runOnMainSync { locator?.stop() }
        }
        // No coordinates are printed, persisted, or sent to discovery/network by this test.
    }
}
