package no.healthlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Felles byggeklosser for skjermene, så overskrifter, lister og fliser ser like ut
 * overalt: seksjonsoverskrift, liste-kort med rader som åpner en underside, og
 * tall-flis.
 */

/** Overskrift over en gruppe fliser eller rader. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

/** Ett kort som samler flere [NavRow] med skillelinjer mellom. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) { Column(content = content) }
}

/**
 * Rad som åpner en underside: tittel, valgfri undertekst og pil. [showDivider] av på
 * siste rad i et [ListCard].
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showDivider: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Flis med navn, stort tall og valgfri hjelpetekst - brukt i "Dagens tall" og Trender. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null
) {
    Card(if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (extra != null) {
                Spacer(Modifier.padding(top = 6.dp))
                extra()
            }
            if (detail != null) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(detail, style = MaterialTheme.typography.labelMedium, color = detailColor)
            }
        }
    }
}

/** Fargene for status: bra/normal og "følg med". Lesbare i både lyst og mørkt tema. */
object StatusColors {
    @Composable
    fun good(): Color = MaterialTheme.colorScheme.primary

    @Composable
    fun warn(): Color = if (MaterialTheme.colorScheme.background.red < 0.5f) Color(0xFFF2B263) else Color(0xFF9A5B00)
}
