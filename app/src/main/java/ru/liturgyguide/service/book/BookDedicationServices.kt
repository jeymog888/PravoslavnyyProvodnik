package ru.liturgyguide.service.book

/** The local catalog is metadata; only the resolver proves a published calendar event. */
class BookDedicationServices(
    val choices: PatronalChoiceStore,
    val resolver: PatronalCalendarResolver,
    val loadCatalog: suspend () -> PatronalCatalog?
)
