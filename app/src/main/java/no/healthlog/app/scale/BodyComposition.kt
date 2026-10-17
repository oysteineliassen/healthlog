package no.healthlog.app.scale

/**
 * Kroppssammensetning regnet ut fra vekt og fettprosent (det AH100-vekta faktisk sender).
 * Huawei Health viser flere tall, men de regnes ut i appen deres, ikke på vekta.
 *
 * Eksakt: BMI, fettmasse, fettfri masse (FFM).
 * Beregnet (omtrentlig): FFM deles i vann, bein og protein med faste andeler hentet fra
 * Huawei sin egen oppdeling (vann 69 %, bein 5,4 %, protein resten), og muskelmasse =
 * FFM - bein, slik Huawei regner det. Basalmetabolisme med Katch-McArdle (370 + 21,6 x FFM).
 * Skjelettmuskel og visceralt fett tas ikke med - de krever Huaweis lukkede modell.
 */
data class BodyComposition(
    val weightKg: Double,
    val bmi: Double?,
    val fatPercent: Double?,
    val fatMassKg: Double?,
    val leanMassKg: Double?,
    val muscleMassKg: Double?,
    val waterKg: Double?,
    val waterPercent: Double?,
    val proteinKg: Double?,
    val boneMassKg: Double?,
    val bmrKcal: Int?
)

private const val WATER_SHARE_OF_LEAN = 0.69
private const val BONE_SHARE_OF_LEAN = 0.0542

fun bodyComposition(weightKg: Double, fatPercent: Double?, heightCm: Int?): BodyComposition {
    val bmi = heightCm?.takeIf { it in 100..250 }?.let { weightKg / ((it / 100.0) * (it / 100.0)) }
    if (fatPercent == null || fatPercent <= 0 || fatPercent >= 100) {
        return BodyComposition(weightKg, bmi, null, null, null, null, null, null, null, null, null)
    }
    val fatMass = weightKg * fatPercent / 100.0
    val lean = weightKg - fatMass
    val water = lean * WATER_SHARE_OF_LEAN
    val bone = lean * BONE_SHARE_OF_LEAN
    return BodyComposition(
        weightKg = weightKg,
        bmi = bmi,
        fatPercent = fatPercent,
        fatMassKg = fatMass,
        leanMassKg = lean,
        muscleMassKg = lean - bone,
        waterKg = water,
        waterPercent = water / weightKg * 100.0,
        proteinKg = lean - water - bone,
        boneMassKg = bone,
        bmrKcal = kotlin.math.round(370 + 21.6 * lean).toInt()
    )
}
