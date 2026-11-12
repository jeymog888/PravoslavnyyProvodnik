package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookSupplementStatus
import ru.liturgyguide.model.ServiceBook

/** The same warning belongs to a downloaded, cached or explicitly pinned book. */
object BookWarnings {
    fun supplementWarning(book: ServiceBook): String? {
        val missing = buildList {
            if (book.enrichment.russian == BookSupplementStatus.RETRY_NEEDED) add("русский перевод молитв")
            if (book.enrichment.scripture == BookSupplementStatus.RETRY_NEEDED) add("русские чтения Писания")
        }
        return missing.takeIf { it.isNotEmpty() }?.let {
            "Оригинал службы загружен целиком. Пока не удалось получить полностью ${it.joinToString(" и ")}. Можно попробовать обновить книгу"
        }
    }
}
