package app.kassa.money.ui

import app.kassa.money.domain.CategoryIcons
import app.kassa.money.ui.categories.IconCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The icon list iOS uses (`domain/CategoryIcons`) must stay the same names, search words and emoji as Android's catalog. */
class IconCatalogParityTest {
    @Test fun namesAndWordsMatch() {
        assertEquals(IconCatalog.all.map { it.name to it.words }, CategoryIcons.all.map { it.name to it.words })
    }

    @Test fun emojiMatch() {
        assertEquals(IconCatalog.emoji, CategoryIcons.emoji)
    }
}
