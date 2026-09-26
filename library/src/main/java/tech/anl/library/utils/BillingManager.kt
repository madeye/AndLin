package tech.anl.library.utils

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * When using this class:
 * - Call `queryPurchases()` in your Activity's onResume() method
 * - Call `startPurchaseFlow()` when one of your in-app products is clicked on
 * - Call `destroy()` in your Activity's onDestroy() method
 */
class BillingManager(
    private val activity: Activity,
    private val onEntitledSubPurchases: (List<Purchase>) -> Unit,
    private val onEntitledInAppPurchases: (List<Purchase>) -> Unit,
    private val onPurchase: (Purchase) -> Unit,
    private val onSubscriptionSupportedChecked: (Boolean) -> Unit
) {

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingResponseCode.OK -> {
                purchases?.let {
                    for (purchase in purchases) {
                        when (purchase.purchaseState) {
                            Purchase.PurchaseState.PURCHASED -> {
                                onPurchase(purchase)
                                if (!purchase.isAcknowledged) {
                                    val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                                        .setPurchaseToken(purchase.purchaseToken)
                                        .build()
                                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                                        log("acknowledgePurchase(), billingResult=$billingResult")
                                    }
                                }
                            }
                            Purchase.PurchaseState.PENDING -> {
                                // Here you can confirm to the user that they've started the pending
                                // purchase, and to complete it, they should follow instructions that
                                // are given to them. You can also choose to remind the user in the
                                // future to complete the purchase if you detect that it is still
                                // pending.
                            }
                        }
                    }
                }
                log("onPurchasesUpdated(), $purchases")
            }
            BillingResponseCode.USER_CANCELED -> log("onPurchasesUpdated() - user cancelled the purchase flow - skipping")
            else -> log("onPurchasesUpdated() got unknown resultCode: ${billingResult.responseCode}")
        }
    }

    private val productDetailsMap = HashMap<String, ProductDetails>()

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener(purchasesUpdatedListener)
        .build()

    private var isBillingServiceConnected = false

    private val populateProducts: (List<ProductDetails>) -> Unit = {
        it.forEach { details -> productDetailsMap[details.productId] = details }
    }
    private fun handlePopulateProductsError(code: Int, message: String) {
        log("Error trying to populate products.  code: $code message: $message")
    }

    init {
        startServiceConnection {
            onSubscriptionSupportedChecked(isSubscriptionPurchaseSupported())
            querySubPurchases()
            queryInAppPurchases()
            queryProductDetails(listOf(Sku.US1_MONTHLY, Sku.US5_MONTHLY, Sku.US10_MONTHLY, Sku.US20_MONTHLY, Sku.US1_YEARLY, Sku.US5_YEARLY, Sku.US10_YEARLY, Sku.US20_YEARLY), BillingClient.ProductType.SUBS)
            queryProductDetails(listOf(Sku.US1_ONETIME, Sku.US5_ONETIME, Sku.US10_ONETIME, Sku.US20_ONETIME), BillingClient.ProductType.INAPP)
        }
    }

    fun querySubPurchases() {
        startServiceConnection {
            if (isSubscriptionPurchaseSupported()) {
                queryPurchases(BillingClient.ProductType.SUBS, onEntitledSubPurchases)
            }
        }
    }

    fun queryInAppPurchases() {
        startServiceConnection {
            queryPurchases(BillingClient.ProductType.INAPP, onEntitledInAppPurchases)
        }
    }

    private fun queryPurchases(productType: String, onResult: (List<Purchase>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                activity.runOnUiThread { onResult(purchases) }
            } else {
                log("Error trying to query purchases: $billingResult")
            }
        }
    }

    fun startPurchaseFlow(productId: String) {
        val details = productDetailsMap[productId] ?: return
        startServiceConnection {
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
            // Subscriptions must name an offer; one-time products must not.
            details.subscriptionOfferDetails?.firstOrNull()?.let { offer ->
                productParams.setOfferToken(offer.offerToken)
            }
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams.build()))
                .build()
            val billingResult = billingClient.launchBillingFlow(activity, flowParams)
            log("startPurchaseFlow(...), billingResult=$billingResult")
        }
    }

    fun destroy() {
        log("destroy()")
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    private fun startServiceConnection(task: () -> Unit) {
        if (isBillingServiceConnected) {
            task()
        } else {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    log("onBillingSetupFinished(...), billingResult=$billingResult")
                    if (billingResult.responseCode == BillingResponseCode.OK) {
                        isBillingServiceConnected = true
                        task()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    log("onBillingServiceDisconnected()")
                    isBillingServiceConnected = false
                    // Try to restart the connection on the next request to
                    // Google Play by calling the startConnection() method.
                }
            })
        }
    }

    private fun queryProductDetails(productIds: List<String>, productType: String) {
        val products = productIds.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(productType).build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                populateProducts(productDetailsList)
            } else {
                handlePopulateProductsError(billingResult.responseCode, billingResult.debugMessage)
            }
        }
    }

    private fun isSubscriptionPurchaseSupported(): Boolean {
        val response = billingClient.isFeatureSupported(FeatureType.SUBSCRIPTIONS)
        if (response.responseCode != BillingResponseCode.OK) {
            log("isSubscriptionPurchaseSupported(), not supported, error response: $response")
        }
        return response.responseCode == BillingResponseCode.OK
    }

    private fun log(message: String) {
        Log.d("BillingManager", message)
    }

    /** The format of SKUs must start with number or lowercase letter and can contain only numbers (0-9),
     * lowercase letters (a-z), underscores (_) & periods (.).*/
    object Sku {
        const val US1_ONETIME = "1us_onetime"
        const val US5_ONETIME = "5us_onetime"
        const val US10_ONETIME = "10us_onetime"
        const val US20_ONETIME = "20us_onetime"
        const val US1_MONTHLY = "1us_monthly"
        const val US5_MONTHLY = "5us_monthly"
        const val US10_MONTHLY = "10us_monthly"
        const val US20_MONTHLY = "20us_monthly"
        const val US1_YEARLY = "1us_yearly"
        const val US5_YEARLY = "5us_yearly"
        const val US10_YEARLY = "10us_yearly"
        const val US20_YEARLY = "20us_yearly"
        // Testing
        // const val TEST_PURCHASED = "android.test.purchased"
        // const val TEST_CANCELED = "android.test.canceled"
        // const val TEST_UNAVAILABLE = "android.test.item_unavailable"
    }
}
