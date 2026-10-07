package com.ksjd.testem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IDS BBSK e-shop pages (trimmed copies of their HTML, made-up card numbers) and the ticket switching rule. */
class IdsTest {
    @Test
    fun walletRule() {
        assertEquals(Wallet.SADZV, WalletRule.forLine("34", "MHD Banská Bystrica"))
        assertEquals(Wallet.SADZV, WalletRule.forLine("7"))
        assertEquals(Wallet.IDS, WalletRule.forLine("60101", "S.A.D. Zvolen a.s."))
        assertEquals(Wallet.IDS, WalletRule.forLine("Bus 060400"))
        assertNull(WalletRule.forLine("609503"))  // long-distance: the default ticket
    }

    @Test
    fun name() {
        val html = """<input class="input__control" type="text" name="firstname"
            placeholder="Va&scaron;e meno"
            value="Jana" />
            <input class="input__control" type="text" name="lastname"
            placeholder="Va&scaron;e priezvisko " value="Nov&aacute;kov&aacute;" />"""
        assertEquals("Jana Nováková", IdsParser.name(html))
    }

    @Test
    fun credit() {
        val html = """<div class="my-credit__credit-amount">
                0,15
            </div><div class="my-credit__header_note"><strong>Kredit je viazan&yacute; na identifik&aacute;tor č. 1 (ISIC, 0400000000AA00)</strong></div>
            <tr class="my-credit__table-row" data-id-order-detail="416909">
                <td class="my-credit__table-column"> 21. 8. 2024 </td>
                <td class="my-credit__table-column"> Dobitie kreditu </td>
                <td class="my-credit__table-column"><div class="my-credit__msg"> Platba bola prijat&aacute; </div></td>
                <td class="my-credit__table-column"> 10,00 € <td>
            </tr>"""
        assertEquals(0.15, IdsParser.credit(html)!!, 0.001)
        assertEquals("Kredit je viazaný na identifikátor č. 1 (ISIC, 0400000000AA00)", IdsParser.creditNote(html))
        val top = IdsParser.topUps(html).single()
        assertEquals("Platba bola prijatá", top.status)
        assertEquals(1000L, top.amountCents)
    }

    @Test
    fun identifiers() {
        val html = """<tr class="my-tokens__table-row" data-token="AAA">
            <td class="my-tokens__table-column"><div>Identifik&aacute;tor č.  2</div></td>
            <td><input class="my-tokens__card-info token-alias-input"
                    value="Virtuálna IDS BBSK karta" disabled />
              <a href="https://eshop.idsbbsk.sk/moje-identifikatory?id_token=830&action=qr">(zobraziť)</a></td>
            <td><div class="my-tokens__msg-alert"> Žiadne produkty </div></td>
            <td><div class="my-tokens__msg-success"> Platn&yacute; </div></td></tr>
            <tr class="my-tokens__table-row" data-token="BBB">
            <td><div>Identifik&aacute;tor č.  5</div></td>
            <td><div class="my-tokens__card-header">Č&iacute;slo karty:</div><div class="my-tokens__card-info">01000000</div>
              <input class="my-tokens__card-info token-alias-input" value="NFC Karta" disabled /></td>
            <td><div class="my-tokens__msg-success"> Platné lístky: 1 </div></td>
            <td><div class="my-tokens__msg-success"> Platn&yacute; </div></td></tr>"""
        val (virtual, nfc) = IdsParser.identifiers(html)
        assertEquals("830", virtual.qrId)
        assertEquals("Virtuálna IDS BBSK karta", virtual.alias)
        assertTrue(!virtual.hasProducts && virtual.valid)
        assertEquals("01000000", nfc.cardNumber)
        assertTrue(nfc.hasProducts && nfc.qrId == null)
    }

    @Test
    fun tickets() {
        val html = """<tr class="my-tickets__table-row" data-id-order-detail="1">
            <td><div class="my-tickets__table-highlight breakable">
                Zľavnené cestovné, žiak a študent 18-26 r., IDS Sieťovka 365 dní </div>
              <div class="my-tickets__msg-success"> Platn&yacute; </div></td>
            <td><div class="my-tickets__table-highlight">od:</div><div class="my-tickets__table-text nobreak"> 2. 9. 2026 </div>
              <div class="my-tickets__table-text nobreak"> 12:28 </div>
              <div class="my-tickets__table-highlight">do:</div><div class="my-tickets__table-text nobreak"> 1. 9. 2027 </div>
              <div class="my-tickets__table-text nobreak"> 23:59 </div></td>
            <td> identifikátor č. 5 <br>(NFC Karta) <br>01000000 <button>Preniesť na iný identifikátor</button></td>
            <td> 482,40 € </td></tr>"""
        val t = IdsParser.tickets(html).single()
        assertTrue(t.valid)
        assertEquals(5, t.identifierNumber)
        assertEquals("identifikátor č. 5 (NFC Karta) 01000000", t.identifier)
        assertEquals("482,40 €", t.price)
        assertTrue(t.validToMs > t.validFromMs)
    }

    @Test
    fun expiredTicketPrice() {
        // No transfer button on an expired ticket: the card number stands right before the price.
        val html = """<tr class="my-tickets__table-row" data-id-order-detail="2">
            <td><div class="my-tickets__table-highlight breakable"> IDS Sieťovka 365 dní </div>
              <div class="my-tickets__msg-alert"> Platnosť l&iacute;stka vypr&scaron;ala </div></td>
            <td> od: 29. 8. 2025 13:28 do: 28. 8. 2026 23:59 </td>
            <td> identifikátor č. 5 <br>(NFC Karta) <br>01C55703 </td>
            <td> 399,00 € </td></tr>"""
        val t = IdsParser.tickets(html).single()
        assertEquals("399,00 €", t.price)
        assertTrue(!t.valid)
        assertEquals(-39900L, IdsAccount(tickets = listOf(t)).historyItems().single().amountCents)
    }
}
