import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetailsResponseListener
fun main() {
    val methods = ProductDetailsResponseListener::class.java.methods
    for (m in methods) {
        if (m.name == "onProductDetailsResponse") {
            println(m.parameterTypes.map { it.name })
        }
    }
}
