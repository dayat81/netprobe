package com.telcoagent.udpclient

object OperatorNames {
    private val names = mapOf(
        "51010" to "Telkomsel",
        "51011" to "XL Axiata",
        "51001" to "Indosat Ooredoo",
        "51021" to "Indosat",
        "51089" to "Smartfren",
        "51009" to "Smartfren",
        "51028" to "Ceria",
    )

    fun format(code: String?): String {
        if (code.isNullOrBlank()) return "—"
        val name = names[code]
        return if (name != null) "$name ($code)" else code
    }
}
