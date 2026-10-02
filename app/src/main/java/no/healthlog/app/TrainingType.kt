package no.healthlog.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object TrainingType {
    const val RUNNING = "running"
    const val WALKING = "walking"
    const val BIKING = "biking"
    const val STRENGTH_TRAINING = "strength_training"
    const val SWIMMING = "swimming"
    const val YOGA = "yoga"
    const val HIKING = "hiking"
    const val HIIT = "hiit"
    const val ELLIPTICAL = "elliptical"
    const val ROWING = "rowing"
    const val SKIING = "skiing"
    const val OTHER = "other"

    val all = listOf(
        RUNNING, WALKING, BIKING, STRENGTH_TRAINING, SWIMMING, YOGA,
        HIKING, HIIT, ELLIPTICAL, ROWING, SKIING, OTHER
    )
}

@Composable
fun trainingTypeLabel(type: String): String = when (type) {
    TrainingType.RUNNING -> stringResource(R.string.training_type_running)
    TrainingType.WALKING -> stringResource(R.string.training_type_walking)
    TrainingType.BIKING -> stringResource(R.string.training_type_biking)
    TrainingType.STRENGTH_TRAINING -> stringResource(R.string.training_type_strength_training)
    TrainingType.SWIMMING -> stringResource(R.string.training_type_swimming)
    TrainingType.YOGA -> stringResource(R.string.training_type_yoga)
    TrainingType.HIKING -> stringResource(R.string.training_type_hiking)
    TrainingType.HIIT -> stringResource(R.string.training_type_hiit)
    TrainingType.ELLIPTICAL -> stringResource(R.string.training_type_elliptical)
    TrainingType.ROWING -> stringResource(R.string.training_type_rowing)
    TrainingType.SKIING -> stringResource(R.string.training_type_skiing)
    else -> stringResource(R.string.training_type_other)
}
