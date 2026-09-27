package com.example.tethr.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.tethr.data.Supabase
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.statement.bodyAsText

class BillingManager(
    private val context: Context,
    private val supporterStore: SupporterStore
) : PurchasesUpdatedListener {

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Only exposes the new SUBSCRIPTION product details for the UI
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    companion object {
        const val SUBSCRIPTION_ID = "tethr_premium"
        const val LEGACY_INAPP_ID = "supporter_pack"
        private const val TAG = "BillingManager"
    }

    init {
        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup successful")
                    queryProductDetails()
                    queryPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected, retrying...")
            }
        })
    }

    private fun queryProductDetails() {
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        coroutineScope.launch {
            val result = billingClient.queryProductDetails(queryProductDetailsParams)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val list = result.productDetailsList
                if (!list.isNullOrEmpty()) {
                    _productDetails.value = list[0]
                } else {
                    Log.e(TAG, "No product details found for $SUBSCRIPTION_ID")
                }
            } else {
                Log.e(TAG, "Failed to query product details: ${result.billingResult.debugMessage}")
            }
        }
    }

    private fun queryPurchases() {
        coroutineScope.launch {
            var isPremium = false

            // 1. Check Legacy In-App Purchases (Grandfathered users)
            val inappParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            
            billingClient.queryPurchasesAsync(inappParams) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in purchases) {
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            isPremium = true
                            coroutineScope.launch {
                                if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                            }
                        }
                    }
                }

                // 2. Check New Subscription Purchases
                val subsParams = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                
                billingClient.queryPurchasesAsync(subsParams) { subResult, subPurchases ->
                    if (subResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        for (purchase in subPurchases) {
                            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                                isPremium = true
                                coroutineScope.launch {
                                    if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                                }
                            }
                        }
                    }
                    // Update UI State
                    coroutineScope.launch {
                        supporterStore.setSupporter(isPremium)
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    verifyPurchaseWithBackend(purchase)
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User canceled the purchase")
        } else {
            Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
        }
    }

    @Serializable
    data class VerifyRequest(val purchaseToken: String, val subscriptionId: String?, val packageName: String)
    @Serializable
    data class VerifyResponse(val isPremium: Boolean)

    private fun verifyPurchaseWithBackend(purchase: Purchase) {
        coroutineScope.launch {
            try {
                val data = VerifyRequest(
                    purchaseToken = purchase.purchaseToken,
                    subscriptionId = purchase.products.firstOrNull(),
                    packageName = context.packageName
                )

                val response = Supabase.client.functions.invoke(
                    function = "verify-subscription",
                    body = data
                )
                
                val resultData = Json { ignoreUnknownKeys = true }.decodeFromString<VerifyResponse>(response.bodyAsText())
                
                if (resultData.isPremium) {
                    supporterStore.setSupporter(true)
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                } else {
                    Log.e(TAG, "Backend verification failed: isPremium is false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend verification network/server error", e)
            }
        }
    }

    private suspend fun acknowledgePurchase(purchase: Purchase) {
        withContext(Dispatchers.IO) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged")
                }
            }
        }
    }

    fun launchBillingFlow(activity: Activity) {
        val details = _productDetails.value
        if (details != null) {
            // For subscriptions, we must select an offer token (e.g. Free Trial or Base Plan)
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            
            if (offerToken == null) {
                Log.e(TAG, "No offer token available for subscription.")
                return
            }

            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offerToken)
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            Log.e(TAG, "Product details not loaded yet.")
        }
    }
}
