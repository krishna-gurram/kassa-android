package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonFormTest {

    @Test
    fun aNameIsRequiredAndUniqueIgnoringCase() {
        val empty = PersonForm().problems(emptyList())
        assertEquals("Give the person a name.", empty[PersonField.NAME])
        assertEquals("sam is already used.", PersonForm(name = " sam ").problems(listOf("SAM"))[PersonField.NAME])
        assertTrue(PersonForm(name = "Sam").problems(listOf("Alex")).isEmpty())
        assertEquals("Keep the name to 40 characters.", PersonForm(name = "x".repeat(41)).problems(emptyList())[PersonField.NAME])
    }

    @Test
    fun theOpeningAmountIsOptionalBoundedAndSigned() {
        assertTrue(PersonForm(name = "Sam", amount = "").problems(emptyList()).isEmpty())
        assertEquals(0L, PersonForm(name = "Sam").opening())
        assertEquals(1250L, PersonForm(name = "Sam", amount = "12.50").opening())
        assertEquals(-1250L, PersonForm(name = "Sam", amount = "12.50", theyOwe = false).opening())
        assertEquals("Enter an amount.", PersonForm(name = "Sam", amount = "abc").problems(emptyList())[PersonField.AMOUNT])
        assertEquals("That's more than $100,000,000.", PersonForm(name = "Sam", amount = "100000000").problems(emptyList())[PersonField.AMOUNT])
    }

    @Test
    fun theNameIsStoredCleaned() {
        assertEquals("Sam Lee", PersonForm(name = "  Sam   Lee ").cleanName)
    }
}
