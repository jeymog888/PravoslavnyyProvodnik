package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test

class PalomnikStreetAddressTest {
    private fun address(value: String) = requireNotNull(PalomnikStreetAddress.parse(value))

    @Test fun `Listi real address retains building identity independently of postal city prefix`() {
        val selected = address("улица Сретенка, 27 с3")
        val source = address("103051, Россия, Москва, ул. Сретенка, 27, стр. 3")
        assertTrue(selected.sameBuilding(source))
        assertEquals("27", selected.house)
        assertEquals("3", selected.building)
        assertNull(selected.corpus)
        assertEquals("сретенка", selected.street)
    }

    @Test fun `corpus building fraction letter and street kind cannot be discarded`() {
        val selected = address("улица Сретенка, 27 с3")
        for (wrong in listOf("27к3", "27/3", "27", "3", "27 с4", "27а с3", "27 к1 с3")) {
            assertFalse(wrong, selected.sameBuilding(address("улица Сретенка, $wrong")))
        }
        assertFalse(selected.sameBuilding(address("переулок Сретенка, 27 стр.3")))
        assertFalse(address("улица 9 Мая, 1").sameBuilding(address("улица 1 Мая, 1")))
        assertTrue(address("улица Сретенка, д. 27 корп. 2 стр. 3").sameBuilding(address("ул. Сретенка, 27к2с3")))
        assertTrue(address("улица Мира, 25-А").sameBuilding(address("ул. Мира, 25а")))
        assertTrue(address("улица Немировича-Данченко, 120/1").sameBuilding(address("Россия, г. Новосибирск, ул. Немировича-Данченко 120/1")))
        assertFalse(address("улица Немировича-Данченко, 120/1").sameBuilding(address("улица Немировича-Данченко, 120/2")))
    }

    @Test fun `unsupported incomplete and multiple building details are rejected rather than normalized away`() {
        for (value in listOf("Сретенка 27", "улица Сретенка", "улица Сретенка 27 стр.", "улица Сретенка 27 стр. 3 стр. 4",
            "улица Сретенка 27 к1 корпус2", "улица Сретенка 27, квартира 3", "улица Сретенка, 27 / 3", "улица Сретенка, 27 стр. 30000")) {
            assertNull(value, PalomnikStreetAddress.parse(value))
        }
    }
}
