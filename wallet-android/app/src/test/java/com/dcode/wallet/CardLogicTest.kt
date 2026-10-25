package com.dcode.wallet

import com.dcode.wallet.autofill.CardField
import com.dcode.wallet.autofill.CardFields
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardBrand
import com.dcode.wallet.data.CardNumbers
import com.dcode.wallet.data.WalletState
import com.dcode.wallet.ui.components.GroupingMapping
import com.dcode.wallet.ui.screens.validateCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CardLogicTest {
    private val visa = Card(id = "v", number = "4242424242424242", holder = "ADA LOVELACE", expMonth = 3, expYear = 2029, cvv = "123")

    @Test fun detectsBrands() {
        assertEquals(CardBrand.VISA, CardBrand.detect("4242 4242 4242 4242"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("5555555555554444"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("2223003122003222"))
        assertEquals(CardBrand.AMEX, CardBrand.detect("378282246310005"))
        assertEquals(CardBrand.DISCOVER, CardBrand.detect("6011111111111117"))
        assertEquals(CardBrand.VERVE, CardBrand.detect("5061020000000000094"))
        assertEquals(CardBrand.JCB, CardBrand.detect("3530111333300000"))
        assertEquals(CardBrand.DINERS, CardBrand.detect("30569309025904"))
        assertEquals(CardBrand.UNIONPAY, CardBrand.detect("6200000000000005"))
        assertEquals(CardBrand.UNKNOWN, CardBrand.detect(""))
    }

    @Test fun luhn() {
        assertTrue(CardNumbers.luhnValid("4242424242424242"))
        assertTrue(CardNumbers.luhnValid("378282246310005"))
        assertFalse(CardNumbers.luhnValid("4242424242424241"))
        assertFalse(CardNumbers.luhnValid("1234"))
    }

    @Test fun formatting() {
        assertEquals("4242 4242 4242 4242", CardNumbers.format("4242424242424242"))
        assertEquals("3782 822463 10005", CardNumbers.format("378282246310005"))
        assertEquals("03/29", visa.expiry)
        assertEquals("4242", visa.last4)
    }

    @Test fun validation() {
        val now = YearMonth.of(2026, 10)
        assertTrue(validateCard("4242424242424242", "ADA", "1028", "123", now).isEmpty())
        assertEquals(setOf("number"), validateCard("4242424242424241", "ADA", "1028", "123", now).keys)
        assertEquals("This card has expired", validateCard("4242424242424242", "ADA", "0926", "123", now)["expiry"])
        assertEquals("Month must be 01–12", validateCard("4242424242424242", "ADA", "1328", "123", now)["expiry"])
        assertTrue("cvv" in validateCard("378282246310005", "ADA", "1028", "123", now))
        assertTrue(validateCard("378282246310005", "ADA", "1028", "1234", now).isEmpty())
        assertTrue("holder" in validateCard("4242424242424242", " ", "1028", "123", now))
    }

    @Test fun classifiesCheckoutFields() {
        assertEquals(CardField.NUMBER, CardFields.classify(listOf("creditCardNumber")))
        assertEquals(CardField.NUMBER, CardFields.classify(listOf("cc-number")))
        assertEquals(CardField.NUMBER, CardFields.classify(listOf("card_number_input", "Card number")))
        assertEquals(CardField.CVV, CardFields.classify(listOf("cc-csc")))
        assertEquals(CardField.CVV, CardFields.classify(listOf("et_cvv", "CVV")))
        assertEquals(CardField.EXP_DATE, CardFields.classify(listOf("cc-exp")))
        assertEquals(CardField.EXP_DATE, CardFields.classify(listOf("expiry", "MM / YY")))
        assertEquals(CardField.EXP_MONTH, CardFields.classify(listOf("cc-exp-month")))
        assertEquals(CardField.EXP_MONTH, CardFields.classify(listOf("creditCardExpirationMonth")))
        assertEquals(CardField.EXP_YEAR, CardFields.classify(listOf("cc-exp-year")))
        assertEquals(CardField.HOLDER, CardFields.classify(listOf("cc-name")))
        assertEquals(CardField.HOLDER, CardFields.classify(listOf("cardholder_name", "Name on card")))
        assertNull(CardFields.classify(listOf("email", "Email address")))
        assertNull(CardFields.classify(listOf("company", "Company")))
        assertNull(CardFields.classify(listOf("cc-type")))
        assertNull(CardFields.classify(emptyList()))
    }

    @Test fun formatsFillValues() {
        assertEquals("4242424242424242", CardFields.text(CardField.NUMBER, visa))
        assertEquals("03/29", CardFields.text(CardField.EXP_DATE, visa))
        assertEquals("03/2029", CardFields.text(CardField.EXP_DATE, visa, maxLength = 7))
        assertEquals("0329", CardFields.text(CardField.EXP_DATE, visa, maxLength = 4))
        assertEquals("03", CardFields.text(CardField.EXP_MONTH, visa))
        assertEquals("2029", CardFields.text(CardField.EXP_YEAR, visa))
        assertEquals("29", CardFields.text(CardField.EXP_YEAR, visa, maxLength = 2))
    }

    @Test fun picksDropdownOptions() {
        assertEquals(3, CardFields.listIndex(CardField.EXP_MONTH, visa, listOf("Month", "01", "02", "03", "04")))
        assertEquals(3, CardFields.listIndex(CardField.EXP_MONTH, visa, listOf("Month", "January", "February", "March")))
        assertEquals(2, CardFields.listIndex(CardField.EXP_YEAR, visa, listOf("Year", "2028", "2029")))
        assertEquals(2, CardFields.listIndex(CardField.EXP_YEAR, visa, listOf("YY", "28", "29")))
        assertEquals(-1, CardFields.listIndex(CardField.EXP_YEAR, visa, listOf("2025", "2026")))
    }

    @Test fun groupingOffsets() {
        val m = GroupingMapping.build("42424242", setOf(4, 8, 12, 16), ' ')
        assertEquals("4242 4242", m.output)
        assertEquals(0, m.toTransformed(0))
        assertEquals(5, m.toTransformed(4))
        assertEquals(9, m.toTransformed(8))
        assertEquals(4, m.toOriginal(4))
        assertEquals(4, m.toOriginal(5))
        assertEquals(8, m.toOriginal(9))
        val exp = GroupingMapping.build("12", setOf(2), '/')
        assertEquals("12", exp.output)
        assertEquals(2, exp.toTransformed(2))
    }

    @Test fun defaultCardFallsBackToFirst() {
        val mc = visa.copy(id = "m", number = "5555555555554444")
        assertEquals("v", WalletState(listOf(visa, mc)).defaultCard?.id)
        assertEquals("m", WalletState(listOf(visa, mc), defaultCardId = "m").defaultCard?.id)
        assertEquals(listOf("m", "v"), WalletState(listOf(visa, mc), defaultCardId = "m").cardsDefaultFirst.map { it.id })
    }
}
