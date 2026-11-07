package ru.liturgyguide.model

data class ParishDirectory(val defaultChurchId: String, val verifiedAt: String, val churches: List<Parish>)
data class Parish(
    val id: String, val name: String, val shortName: String, val city: String,
    val address: String, val phone: String, val website: String, val timezone: String,
    val sourceUrl: String, val scheduleSource: String, val scheduleValidFrom: String,
    val scheduleValidUntil: String, val schedule: List<ParishService>,
    val scheduleCoverage: String, val scheduleNote: String,
    val latitude: Double? = null, val longitude: Double? = null
)
data class ParishService(val date: String, val time: String, val title: String, val kind: String)
