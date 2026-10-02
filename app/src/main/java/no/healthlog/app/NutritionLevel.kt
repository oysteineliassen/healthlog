package no.healthlog.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object NutritionLevel {
    const val LIGHT = "light"
    const val MODERATE = "moderate"
    const val HEAVY = "heavy"

    val all = listOf(LIGHT, MODERATE, HEAVY)
}

@Composable
fun nutritionLevelLabel(level: String): String = when (level) {
    NutritionLevel.LIGHT -> stringResource(R.string.nutrition_level_light)
    NutritionLevel.MODERATE -> stringResource(R.string.nutrition_level_moderate)
    NutritionLevel.HEAVY -> stringResource(R.string.nutrition_level_heavy)
    else -> level
}
