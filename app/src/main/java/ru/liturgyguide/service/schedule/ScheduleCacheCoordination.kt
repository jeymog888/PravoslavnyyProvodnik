package ru.liturgyguide.service.schedule

/** Every production instance accesses the same preference file under this process lock. No network holds it. */
internal object ScheduleCacheCoordination { val productionLock = Any() }

/** Old stores remain compatible; all repository reads/writes share the store's coordination lock. */
internal class CoordinatedScheduleCacheStore(private val delegate: ScheduleCacheStore) : ScheduleCacheStore {
    override val coordinationLock: Any get() = delegate.coordinationLock
    override fun read(parishId: String): String? = synchronized(coordinationLock) { delegate.read(parishId) }
    override fun write(parishId: String, value: String) = synchronized(coordinationLock) { delegate.write(parishId, value) }
    override fun writeSafety(parishId: String, value: String) = synchronized(coordinationLock) { delegate.writeSafety(parishId, value) }
}
