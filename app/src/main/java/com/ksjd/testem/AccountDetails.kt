package com.ksjd.testem

data class AccountDetails(
    val cardTypeName: String = "",
    val organizationName: String = "",
    val cardValidFrom: Long = 0,
    val cardValidTo: Long = 0,
    val ticketValidFrom: Long = 0,
    val ticketValidTo: Long = 0,
    val discountValidFrom: Long = 0,
    val discountValidTo: Long = 0,
    val creditLastBalance: Double? = null,
    val currencySymbol: String = "",
    val cardTemplateBase64: String = ""
)
