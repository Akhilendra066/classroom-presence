package com.classroompresence.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.classroompresence.core.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudException(val status: Int, message: String) : Exception(message)

/** Supabase Auth and PostgREST. Only the public publishable/anon key belongs in the APK. */
class SupabaseCloud(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("supabase-auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_ANON_KEY
    val configured: Boolean get() = base.matches(Regex("https://[a-z0-9-]+\\.supabase\\.co")) && apiKey.isNotBlank() && !apiKey.startsWith("sb_secret_")
    companion object { private val lock = Mutex(); private const val alias = "classroom-presence-supabase-auth" }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun store(session: JsonObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString("session", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()
    }
    private fun session(): JsonObject? = preferences.getString("session", null)?.let { saved -> runCatching {
        val bytes = Base64.decode(saved, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        json.parseToJsonElement(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).jsonObject
    }.getOrNull() }
    fun clear() { preferences.edit().remove("session").commit() }

    private fun request(path: String, body: JsonElement, token: String? = null, method: String = "POST"): JsonElement {
        check(configured) { "Supabase setup is pending. See docs/supabase-setup.md." }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonNull }
            if (status !in 200..299) {
                val fields = response as? JsonObject
                throw CloudException(status, fields?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: fields?.get("msg")?.jsonPrimitive?.contentOrNull ?: fields?.get("error_description")?.jsonPrimitive?.contentOrNull ?: "Server request failed ($status)")
            }
            return response
        } finally { connection.disconnect() }
    }
    private fun token(): String {
        var saved = session() ?: throw CloudException(401, "Sign in again to synchronize this account.")
        val expires = saved["expires_at"]?.jsonPrimitive?.longOrNull ?: 0
        if (expires <= System.currentTimeMillis() / 1000 + 60) {
            val refresh = saved["refresh_token"]?.jsonPrimitive?.content ?: throw CloudException(401, "Sign in again.")
            saved = request("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refresh) }).jsonObject
            store(saved)
        }
        return saved["access_token"]?.jsonPrimitive?.content ?: throw CloudException(401, "Sign in again.")
    }
    private fun rpc(operation: String, payload: Map<String, Any?>): JsonElement {
        val data = buildJsonObject { payload.forEach { (key, value) -> when(value) {
            null -> put(key, JsonNull); is String -> put(key, value); is Boolean -> put(key, value)
            is Number -> put(key, value); else -> error("Unsupported API value")
        } } }
        return request("/rest/v1/rpc/presence_api", buildJsonObject { put("operation", operation); put("payload", data) }, token())
    }
    suspend fun login(email: String, password: String, role: String): Account = withContext(Dispatchers.IO) { lock.withLock {
        clear()
        val saved = request("/auth/v1/token?grant_type=password", buildJsonObject { put("email", email.trim()); put("password", password) }).jsonObject
        store(saved)
        try {
            val identity = json.decodeFromJsonElement<Account>(rpc("identity", emptyMap()))
            check(identity.role == role) { "This account is not assigned the $role role. Contact your administrator." }
            identity
        } catch (e: Exception) { clear(); throw e }
    } }
    suspend fun call(uid: String, operation: String, payload: Map<String, Any?>): String = withContext(Dispatchers.IO) { lock.withLock {
        check(session()?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.content == uid) { "Sign in again to synchronize this account." }
        rpc(operation, payload).toString()
    } }
    suspend fun resetPassword(email: String) = withContext(Dispatchers.IO) { lock.withLock {
        request("/auth/v1/recover", buildJsonObject { put("email", email.trim()) }); Unit
    } }
    suspend fun setPasswordFromLink(link: String, password: String) = withContext(Dispatchers.IO) { lock.withLock {
        check(password.length >= 12) { "Use a password with at least 12 characters." }
        val uri = Uri.parse(link.trim())
        check(uri.scheme == "https" && uri.host == Uri.parse(base).host && uri.path == "/auth/v1/verify") { "Use the administrator's password setup link for this Supabase project." }
        val hash = uri.getQueryParameter("token_hash") ?: uri.getQueryParameter("token") ?: error("Password setup token missing.")
        check(uri.getQueryParameter("type") == "recovery") { "A recovery link is required." }
        val verified = request("/auth/v1/verify", buildJsonObject { put("token_hash", hash); put("type", "recovery") }).jsonObject
        try { request("/auth/v1/user", buildJsonObject { put("password", password) }, verified["access_token"]!!.jsonPrimitive.content, "PUT") }
        finally { clear() }
        Unit
    } }
}
