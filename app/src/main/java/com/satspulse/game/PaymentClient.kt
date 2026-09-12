package com.satspulse.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PaymentClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    suspend fun createCheckout(sku: String, deviceId: String): CheckoutSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("sku", sku).put("deviceId", deviceId).toString()
        val json = request("POST", "/api/checkout", body)
        CheckoutSession(
            orderToken = json.getString("orderToken"),
            sku = json.getString("sku"),
            priceSats = json.getInt("priceSats"),
            checkoutUrl = json.getString("checkoutUrl"),
            provider = json.optString("provider", "lightning")
        )
    }

    suspend fun status(orderToken: String): PurchaseStatus = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/status?orderToken=${encode(orderToken)}", null)
        PurchaseStatus(
            paid = json.optBoolean("paid", false),
            sku = json.optString("sku").ifBlank { null },
            status = json.optString("status", "unknown")
        )
    }

    private fun request(method: String, path: String, body: String?): JSONObject {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://10.0.2.2")) {
            "API_BASE_URL must use HTTPS in production"
        }
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SatsPulse-Android/2")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write(body) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException(message?.ifBlank { null } ?: "HTTP $code")
        }
        return JSONObject(text)
    }

    private fun encode(raw: String): String = java.net.URLEncoder.encode(raw, Charsets.UTF_8.name())
}
