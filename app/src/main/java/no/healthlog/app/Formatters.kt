package no.healthlog.app

import java.time.format.DateTimeFormatter
import java.util.Locale

val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

val norwegianLocale: Locale = Locale.Builder().setLanguage("nb").setRegion("NO").build()
