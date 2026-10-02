package no.healthlog.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object TrainingIntensity {
    const val LIGHT = "light"
    const val MODERATE = "moderate"
    const val VIGOROUS = "vigorous"
}

@Composable
fun trainingIntensityLabel(intensity: String): String = when (intensity) {
    TrainingIntensity.LIGHT -> stringResource(R.string.training_intensity_light)
    TrainingIntensity.MODERATE -> stringResource(R.string.training_intensity_moderate)
    TrainingIntensity.VIGOROUS -> stringResource(R.string.training_intensity_vigorous)
    else -> intensity
}
