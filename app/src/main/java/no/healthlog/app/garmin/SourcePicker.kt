package no.healthlog.app.garmin

/**
 * Beholder bare postene fra den kilden (app) som har høyest sum av [amount] - brukes
 * for skritt, der flere apper kan ha skrevet de samme skrittene. Ved likhet vinner
 * kilden som kommer først alfabetisk, så resultatet er stabilt mellom synker.
 */
fun <T> recordsFromMainSource(records: List<T>, source: (T) -> String, amount: (T) -> Long): List<T> {
    if (records.isEmpty()) return records
    val bySource = records.groupBy(source)
    if (bySource.size == 1) return records
    val main = bySource.entries
        .sortedBy { it.key }
        .maxBy { (_, list) -> list.sumOf(amount) }
    return main.value
}
