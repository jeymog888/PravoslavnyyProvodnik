package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test

class ForegroundLocationRequestTest {
    @Test fun noPermissionNeverReadsCoordinatesOrRegistersProviders() {
        val gateway = Gateway().apply { fine = false; coarse = false }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(listOf(ForegroundLocationResult.Failed(LocationFailure.PERMISSION)), results)
        assertEquals(0, gateway.providerReads)
        assertTrue(gateway.cachedReads.isEmpty()); assertTrue(gateway.subscriptions.isEmpty())
    }

    @Test fun coarseOnlyGpsAndFusedOnlyDevicesBothProduceAUsableResult() {
        listOf("gps", "fused", "network").forEach { provider ->
            val gateway = Gateway().apply { fine = false; coarse = true; available = listOf(provider) }
            val results = mutableListOf<ForegroundLocationResult>()
            ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
            assertEquals(listOf(provider), gateway.subscriptions.map { it.provider })
            gateway.emit(provider, gateway.fix(2_000f))
            assertEquals(listOf(ForegroundLocationResult.Located(gateway.fix(2_000f))), results)
            gateway.assertStopped()
        }
    }

    @Test fun preciseAccessWorksAndKnownProviderPriorityDoesNotDependOnListOrder() {
        val gateway = Gateway().apply { available = listOf("gps", "passive", "network", "fused") }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(listOf("fused", "network", "gps"), gateway.subscriptions.map { it.provider })
        gateway.emit("gps", gateway.fix(12f))
        assertEquals(1, results.size); gateway.assertStopped()
    }

    @Test fun disabledIsReportedOnlyWhenTheSystemConfirmsIt() {
        val off = Gateway().apply { enabled = false }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(off).locateResult(5_000f, 120_000L, results::add)
        assertEquals(LocationFailure.DISABLED, (results.single() as ForegroundLocationResult.Failed).reason)
        assertEquals(0, off.providerReads)
        listOf(true, null).forEach { enabled ->
            val gateway = Gateway().apply { this.enabled = enabled; available = emptyList() }
            val failures = mutableListOf<ForegroundLocationResult>()
            ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, failures::add)
            assertEquals(LocationFailure.UNAVAILABLE, (failures.single() as ForegroundLocationResult.Failed).reason)
        }
    }

    @Test fun oneProviderFailureDuringEnumerationCacheOrSubscriptionDoesNotAbortTheOther() {
        val gateway = Gateway().apply {
            available = listOf("fused", "network", "gps")
            enabledFailures += "fused"
            cachedFailures += "network"
            subscriptionFailures += "network"
        }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(listOf("network", "gps"), gateway.subscriptionAttempts)
        gateway.emit("gps", gateway.fix())
        assertEquals(1, results.size); gateway.assertStopped()
    }

    @Test fun unacceptableFirstFixDoesNotConsumeSubscriptionAndAnotherProviderMayStillWin() {
        val gateway = Gateway()
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        gateway.emit("network", gateway.fix(6_000f))
        gateway.emit("gps", gateway.fix().copy(elapsedRealtimeNanos = 1))
        gateway.emit("gps", gateway.fix().copy(accuracyMeters = null))
        assertTrue(results.isEmpty()); assertFalse(gateway.subscriptions.any { it.cancelled })
        gateway.emit("gps", gateway.fix(20f))
        assertEquals(listOf(ForegroundLocationResult.Located(gateway.fix(20f))), results)
        gateway.emit("network", gateway.fix(3f)) // Deliberately non-cooperative late callback.
        gateway.fireDeadline()
        assertEquals(1, results.size); gateway.assertStopped()
    }

    @Test fun cachedFixUsesSameThresholdAndDoesNotStartSensorsWhenUsable() {
        val gateway = Gateway().apply { cached["network"] = fix(6_000f); cached["gps"] = fix(20f) }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(listOf(ForegroundLocationResult.Located(gateway.fix(20f))), results)
        assertTrue(gateway.subscriptions.isEmpty()); assertNull(gateway.timer)
    }

    @Test fun timeoutAndCancelRemoveAllSubscriptionsAndNeverDeliverLateResults() {
        listOf(false, true).forEach { cancel ->
            val gateway = Gateway()
            val results = mutableListOf<ForegroundLocationResult>()
            val request = ForegroundLocationRequest(gateway)
            request.locateResult(5_000f, 120_000L, results::add)
            if (cancel) request.stop() else gateway.fireDeadline()
            gateway.emit("gps", gateway.fix())
            gateway.fireDeadline()
            assertEquals(if (cancel) emptyList() else listOf(ForegroundLocationResult.Failed(LocationFailure.TIMEOUT)), results)
            gateway.assertStopped()
        }
    }

    @Test fun inaccurateResultHasAnHonestDistinctFailureAtTheSameBoundedDeadline() {
        val gateway = Gateway()
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        gateway.emit("network", gateway.fix(8_000f))
        gateway.fireDeadline()
        assertEquals(listOf(ForegroundLocationResult.Failed(LocationFailure.INACCURATE)), results)
        gateway.assertStopped()
    }

    @Test fun staleOrInvalidImpreciseFixDoesNotClaimThatACurrentLocationWasFound() {
        for (invalid in listOf(ForegroundLocationFix(55.77, 37.63, 8_000f, 1),
            ForegroundLocationFix(91.0, 37.63, 8_000f, 300_000_000_000L))) {
            val gateway = Gateway().apply { cached["network"] = invalid }
            val results = mutableListOf<ForegroundLocationResult>()
            ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
            gateway.emit("gps", invalid)
            gateway.fireDeadline()
            assertEquals(listOf(ForegroundLocationResult.Failed(LocationFailure.TIMEOUT)), results)
            gateway.assertStopped()
        }
    }

    @Test fun restartingCannotDeliverOldFixOrOldTimeoutIntoNewRequest() {
        val gateway = Gateway()
        val old = mutableListOf<ForegroundLocationResult>(); val current = mutableListOf<ForegroundLocationResult>()
        val request = ForegroundLocationRequest(gateway)
        request.locateResult(5_000f, 120_000L, old::add)
        val firstSubscription = gateway.subscriptions.first(); val firstTimer = requireNotNull(gateway.timer)
        request.locateResult(5_000f, 120_000L, current::add)
        assertTrue(firstSubscription.cancelled); assertTrue(firstTimer.cancelled)
        firstSubscription.callback(gateway.fix()); firstTimer.action()
        assertTrue(old.isEmpty()); assertTrue(current.isEmpty())
        gateway.subscriptions.last().callback(gateway.fix())
        assertEquals(1, current.size); gateway.assertStopped()
    }

    @Test fun synchronousProviderCallbackAndCleanupExceptionsCannotLeakOtherSubscriptions() {
        val gateway = Gateway().apply { synchronous = "gps"; throwOnCancel = "network" }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(1, results.size); gateway.assertStopped()
    }

    @Test fun permissionRevokedWhileRegisteringFailsImmediatelyAndReleasesDeadline() {
        val gateway = Gateway().apply { revokeOnSubscribe = true }
        val results = mutableListOf<ForegroundLocationResult>()
        ForegroundLocationRequest(gateway).locateResult(5_000f, 120_000L, results::add)
        assertEquals(listOf(ForegroundLocationResult.Failed(LocationFailure.PERMISSION)), results)
        gateway.assertStopped()
    }

    private class Gateway : ForegroundLocationGateway {
        var fine = true; var coarse = true; var enabled: Boolean? = true
        var available = listOf("network", "gps")
        var providerReads = 0
        var synchronous: String? = null; var throwOnCancel: String? = null; var revokeOnSubscribe = false
        val enabledFailures = mutableSetOf<String>(); val cachedFailures = mutableSetOf<String>(); val subscriptionFailures = mutableSetOf<String>()
        val cached = mutableMapOf<String, ForegroundLocationFix>()
        val cachedReads = mutableListOf<String>(); val subscriptionAttempts = mutableListOf<String>()
        val subscriptions = mutableListOf<Subscription>()
        var timer: Timer? = null
        class Subscription(val provider: String, val callback: (ForegroundLocationFix) -> Unit) { var cancelled = false }
        class Timer(val action: () -> Unit) { var cancelled = false }
        override fun hasPermission() = fine || coarse
        override fun isLocationEnabled() = enabled
        override fun providers(): List<String> { providerReads++; return available }
        override fun isProviderEnabled(provider: String): Boolean {
            if (provider in enabledFailures) throw IllegalArgumentException("Provider disappeared")
            return true
        }
        override fun lastKnown(provider: String): ForegroundLocationFix? {
            cachedReads += provider
            if (provider in cachedFailures) throw SecurityException("Unavailable for this permission")
            return cached[provider]
        }
        override fun subscribe(provider: String, onFix: (ForegroundLocationFix) -> Unit): LocationCancellation {
            subscriptionAttempts += provider
            if (revokeOnSubscribe) { fine = false; coarse = false; throw SecurityException("Revoked") }
            if (provider in subscriptionFailures) throw SecurityException("Unavailable provider")
            val subscription = Subscription(provider, onFix).also { subscriptions += it }
            if (synchronous == provider) onFix(fix())
            return LocationCancellation { subscription.cancelled = true; if (throwOnCancel == provider) error("Already removed") }
        }
        override fun elapsedRealtimeNanos() = 300_000_000_000L
        override fun after(delayMillis: Long, action: () -> Unit): LocationCancellation {
            assertEquals(20_000L, delayMillis)
            val newTimer = Timer(action).also { timer = it }
            return LocationCancellation { newTimer.cancelled = true }
        }
        fun fix(accuracy: Float = 30f) = ForegroundLocationFix(55.77, 37.63, accuracy, elapsedRealtimeNanos())
        fun emit(provider: String, fix: ForegroundLocationFix) = subscriptions.last { it.provider == provider }.callback(fix)
        fun fireDeadline() = requireNotNull(timer).action()
        fun assertStopped() { assertTrue(subscriptions.all { it.cancelled }); assertTrue(timer == null || timer!!.cancelled) }
    }
}
