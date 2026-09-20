package com.rieltor.domain.model

/** Single labelled value of a landing form, ready for delivery to an operator. */
data class LandingLeadField(val label: String, val value: String)

/** Landing form submission that passed validation. */
data class LandingLead(
    val title: String,
    val fields: List<LandingLeadField>,
    val pageUrl: String,
)

/** Description of a landing form: which fields exist, how they are labelled and what is mandatory. */
data class LandingFormDefinition(
    val title: String,
    val labels: LinkedHashMap<String, String>,
    val required: Set<String>,
) {
    fun label(field: String): String = labels.getValue(field)
}

/** Catalogue of the forms published on the landing site. */
object LandingForms {
    const val PHONE_FIELD = "phone"

    val definitions: Map<String, LandingFormDefinition> = mapOf(
        "selection" to LandingFormDefinition(
            "Новий запит: підбір нерухомості",
            linkedMapOf("category" to "Що хочете купити", "location" to "Де шукаєте", "phone" to "Телефон"),
            setOf("category", "location", "phone"),
        ),
        "valuation" to LandingFormDefinition(
            "Нова заявка: оцінка нерухомості",
            linkedMapOf(
                "name" to "Ім’я", "phone" to "Телефон", "address" to "Локація об’єкта",
                "type" to "Тип нерухомості", "note" to "Коротко про об’єкт",
            ),
            setOf("name", "phone", "address"),
        ),
        "question" to LandingFormDefinition(
            "Нове питання із сайту",
            linkedMapOf("name" to "Ім’я", "phone" to "Телефон", "topic" to "Тема", "message" to "Повідомлення"),
            setOf("name", "phone"),
        ),
    )

    operator fun get(formType: String): LandingFormDefinition? = definitions[formType]
}
