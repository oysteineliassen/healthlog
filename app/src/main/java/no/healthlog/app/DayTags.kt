package no.healthlog.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object DayTags {
    const val SICK = "sick"
    const val TRAVEL = "travel"
    const val REST = "rest"

    val all = listOf(SICK, TRAVEL, REST)
}

@Composable
fun dayTagLabel(tag: String): String = when (tag) {
    DayTags.SICK -> stringResource(R.string.day_tag_sick)
    DayTags.TRAVEL -> stringResource(R.string.day_tag_travel)
    DayTags.REST -> stringResource(R.string.day_tag_rest)
    else -> tag
}
