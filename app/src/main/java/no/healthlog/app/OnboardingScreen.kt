package no.healthlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class OnboardingPage(val titleRes: Int, val bodyRes: Int)

private val onboardingPages = listOf(
    OnboardingPage(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    OnboardingPage(R.string.onboarding_health_connect_title, R.string.onboarding_health_connect_body),
    OnboardingPage(R.string.onboarding_baseline_title, R.string.onboarding_baseline_body)
)

/**
 * Vises kun én gang, første gang appen åpnes (se OnboardingPreferences). Enkel
 * side-for-side-navigasjon med Neste/Tilbake i stedet for en swipe-pager, for å
 * holde det robust og enkelt å vedlikeholde.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val page = onboardingPages[currentPage]
    val isLastPage = currentPage == onboardingPages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            onboardingPages.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentPage > 0) {
                TextButton(onClick = { currentPage-- }) {
                    Text(stringResource(R.string.onboarding_back))
                }
            } else {
                TextButton(onClick = onFinished) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }

            Button(onClick = {
                if (isLastPage) onFinished() else currentPage++
            }) {
                Text(stringResource(if (isLastPage) R.string.onboarding_get_started else R.string.onboarding_next))
            }
        }
    }
}
