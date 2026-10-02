package no.healthlog.app.garmin

import java.time.LocalDate
import java.time.LocalDateTime

/** Klokkeslettet der en måling begynner å høre til NESTE dags natt (kveld). */
private const val EVENING_START_HOUR = 18

/**
 * Datoen en nattlig måling (HRV) hører til: dagen natten slutter, slik søvn lagres i
 * appen. 23:30 mandag og 04:00 tirsdag er samme natt og havner begge på tirsdag.
 */
fun nightDate(time: LocalDateTime): LocalDate =
    if (time.hour >= EVENING_START_HOUR) time.toLocalDate().plusDays(1) else time.toLocalDate()
