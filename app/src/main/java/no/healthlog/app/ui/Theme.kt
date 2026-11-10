package no.healthlog.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Hjørneradius for alle kort i appen. */
val CardCorner = 20.dp

// Hele fargeskalaen er definert (ikke bare primary/background): ellers fyller Material 3
// inn resten med standard-lilla, og knappetekst, brytere og kort får en farge som ikke
// hører hjemme i det grønne temaet.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6B5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4EFDF),
    onPrimaryContainer = Color(0xFF00201A),
    inversePrimary = Color(0xFF8FCFC0),
    secondary = Color(0xFF4B635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFF426278),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = Color(0xFF2F6B5F),
    inverseSurface = Color(0xFF2E3130),
    inverseOnSurface = Color(0xFFEFF1EF),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6F4),
    surfaceContainer = Color(0xFFEDF1EF),
    surfaceContainerHigh = Color(0xFFE8ECEA),
    surfaceContainerHighest = Color(0xFFE2E7E4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCFC0),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF125146),
    onPrimaryContainer = Color(0xFFB4EFDF),
    inversePrimary = Color(0xFF2F6B5F),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1D352E),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCDE8DF),
    tertiary = Color(0xFFAACBE4),
    onTertiary = Color(0xFF113447),
    tertiaryContainer = Color(0xFF2A4A5F),
    onTertiaryContainer = Color(0xFFC7E7FF),
    background = Color(0xFF12191A),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF1B2422),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceTint = Color(0xFF8FCFC0),
    inverseSurface = Color(0xFFE1E3E1),
    inverseOnSurface = Color(0xFF2E3130),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4945),
    surfaceBright = Color(0xFF37403E),
    surfaceDim = Color(0xFF12191A),
    surfaceContainerLowest = Color(0xFF0D1313),
    surfaceContainerLow = Color(0xFF1A2221),
    surfaceContainer = Color(0xFF1F2826),
    surfaceContainerHigh = Color(0xFF293331),
    surfaceContainerHighest = Color(0xFF333E3B)
)

private val AppShapes = Shapes(medium = RoundedCornerShape(CardCorner))

@Composable
fun HealthLogTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
