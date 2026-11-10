package no.healthlog.app

import java.time.format.DateTimeFormatter
import java.util.Locale

val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

val norwegianLocale: Locale = Locale.Builder().setLanguage("nb").setRegion("NO").build()

/** Varighet vist likt overalt: "7 t 5 min". */
fun formatDuration(minutes: Int): String = "${minutes / 60} t ${minutes % 60} min"
