package com.ksjd.testem

/** The two accounts whose ticket the app can show. */
enum class Wallet { SADZV, IDS }

object WalletRule {
    private val idsLine = Regex("^0?60\\d{3}$")
    private val cityLine = Regex("^\\d{1,3}[A-Z]?$")

    /**
     * Which ticket a bus takes. SAD Zvolen's city buses in Banská Bystrica and Zvolen (not in IDS BBSK) take the
     * sadzv.qrbus.me card; IDS BBSK lines, licence 060xxx (regional buses, and since 2026 the city buses of
     * Žiar nad Hronom, Brezno and Nová Baňa), take the IDS BBSK one. Null: neither for sure (long-distance
     * lines 6xx5xx, other regions), so the rider's default is shown.
     */
    fun forLine(line: String, operator: String = ""): Wallet? {
        val number = line.trim().substringAfterLast(' ')
        return when {
            operator.startsWith("MHD Banská Bystrica") || operator.startsWith("MHD Zvolen") -> Wallet.SADZV
            idsLine.matches(number) -> Wallet.IDS
            cityLine.matches(number) -> Wallet.SADZV  // the only city lines the app knows are sadzv's (BB, Zvolen)
            else -> null
        }
    }
}
