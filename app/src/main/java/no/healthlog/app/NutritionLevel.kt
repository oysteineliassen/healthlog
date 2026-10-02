package no.healthlog.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object NutritionLevel {
    const val LIGHT = "light"
    const val MODERATE = "moderate"
    const val HEAVY = "heavy"
    // Faste eller fasteimiterende diett (f.eks. 5-dagers FMD). Holdes utenfor "din
    // normal" i coachen, se isAtypical().
    const val FASTING = "fasting"

    val all = listOf(LIGHT, MODERATE, HEAVY, FASTING)
}

@Composable
fun nutritionLevelLabel(level: String): String = when (level) {
    NutritionLevel.LIGHT -> stringResource(R.string.nutrition_level_light)
    NutritionLevel.MODERATE -> stringResource(R.string.nutrition_level_moderate)
    NutritionLevel.HEAVY -> stringResource(R.string.nutrition_level_heavy)
    NutritionLevel.FASTING -> stringResource(R.string.nutrition_level_fasting)
    else -> level
}
