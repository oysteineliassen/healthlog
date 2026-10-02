package no.healthlog.app.billing

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.healthlog.app.R

/**
 * Vises rundt hver Pro-funksjon i appen. Viser selve innholdet direkte hvis brukeren
 * allerede har kjøpt Pro, ellers en liten, nøytral oppfordring i stedet - aldri en
 * blokkerende dialog, siden gratis-versjonen skal føles fullverdig i seg selv, ikke
 * som en "prøveperiode".
 */
@Composable
fun ProGate(
    billingManager: BillingManager,
    featureName: String,
    content: @Composable () -> Unit
) {
    val isPro by billingManager.isPro.collectAsState()

    if (isPro) {
        content()
    } else {
        ProUpsellCard(billingManager, featureName)
    }
}

@Composable
private fun ProUpsellCard(billingManager: BillingManager, featureName: String) {
    val context = LocalContext.current
    val price = billingManager.getFormattedPrice()

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.pro_upsell_title, featureName),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pro_upsell_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            Button(onClick = {
                (context as? Activity)?.let { billingManager.launchPurchaseFlow(it) }
            }) {
                Text(
                    if (price != null) {
                        stringResource(R.string.action_upgrade_to_pro_priced, price)
                    } else {
                        stringResource(R.string.action_upgrade_to_pro)
                    }
                )
            }
        }
    }
}
