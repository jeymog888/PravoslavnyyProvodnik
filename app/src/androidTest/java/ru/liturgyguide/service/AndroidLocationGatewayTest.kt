package ru.liturgyguide.service

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocationGatewayTest {
    @Test fun platformPermissionAdapterAcceptsApproximateAndPreciseAccessWithoutReadingLocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(false to false, true to false, true to true, false to true).forEach { (coarse, fine) ->
            val permissionsOnly = object : ContextWrapper(context) {
                override fun checkSelfPermission(permission: String): Int = when (permission) {
                    Manifest.permission.ACCESS_COARSE_LOCATION -> coarse
                    Manifest.permission.ACCESS_FINE_LOCATION -> fine
                    else -> false
                }.let { if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
            }
            // Constructing the adapter only obtains the Android service; no providers/fixes are read.
            assertEquals(coarse || fine, AndroidLocationGateway(permissionsOnly).hasPermission())
        }
    }

    @Test fun realMainLooperDeadlineCanBeCancelledWithoutDeliveringItsCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gateway = AndroidLocationGateway(instrumentation.targetContext)
        var callbacks = 0
        instrumentation.runOnMainSync {
            val cancellation = gateway.after(0) { callbacks++ }
            cancellation.cancel()
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks)
    }
}
