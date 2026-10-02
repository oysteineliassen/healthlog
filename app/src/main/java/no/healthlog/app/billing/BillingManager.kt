package no.healthlog.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** ID-en må matche nøyaktig det "in-app product" du oppretter i Play Console. */
const val PRO_PRODUCT_ID = "healthlog_pro"

/**
 * Ett engangskjøp ("HealthLog Pro") som låser opp AI-trener-sammenhengene, Rekorder-
 * siden, årsoversikten, periode-sammenligning, konfigurerbar widget, klinisk PDF, og
 * egne mål-terskler. Alt annet (registrering, Health Connect-synk, grunnleggende
 * trender, sikkerhetskopi/eksport) forblir gratis uansett - se ProGate.kt.
 *
 * Ingen egen serververifisering av kjøpet (appen har ingen backend) - en akseptert,
 * vanlig avveining for en liten soloutvikler-app uten serverdrift. Play Billing sin
 * egen kvittering er god nok beskyttelse for denne skalaen.
 */
class BillingManager(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro

    private var proProductDetails: ProductDetails? = null

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    restorePurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Kobler til på nytt neste gang appen faktisk trenger å sjekke status
                // (f.eks. neste gang launchPurchaseFlow eller restorePurchases kalles) -
                // egen bakgrunns-gjenoppkoblingslogikk er overkill for et engangskjøp.
            }
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { _, productDetailsList ->
            proProductDetails = productDetailsList.firstOrNull()
        }
    }

    /**
     * Sjekker eksisterende kjøp - kalles ved oppstart (og kan kalles på nytt manuelt,
     * f.eks. fra en "Gjenopprett kjøp"-knapp), slik at Pro-status gjenopprettes selv
     * etter en reinstallasjon, så lenge det er samme Google-konto.
     */
    fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { _, purchases ->
            val hasPro = purchases.any {
                it.products.contains(PRO_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            _isPro.value = hasPro
            purchases.forEach { acknowledgeIfNeeded(it) }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val details = proProductDetails ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { purchase ->
                if (purchase.products.contains(PRO_PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                ) {
                    _isPro.value = true
                    acknowledgeIfNeeded(purchase)
                }
            }
        }
    }

    /** Ubekreftede kjøp blir automatisk refundert av Google etter 3 dager - MÅ bekreftes for at kjøpet skal bli stående. */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { }
        }
    }

    /** Formatert pris (f.eks. "kr 49,00") til bruk i "Bli Pro"-knappen, null helt til Play Console-produktet er hentet. */
    fun getFormattedPrice(): String? =
        proProductDetails?.oneTimePurchaseOfferDetails?.formattedPrice
}
