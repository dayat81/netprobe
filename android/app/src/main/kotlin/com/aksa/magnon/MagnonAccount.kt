package com.aksa.magnon

import android.content.Context
import android.content.MutableContextWrapper
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

data class MagnonProfile(
    val name: String,
    val level: String,
    val role: String,
    val stayOnLite: Boolean,
    val email: String = "",
    val sub: String = "",
)

data class GoogleSignIn(
    val idToken: String,
    val nonce: String,
    val sub: String,
    val email: String,
    val name: String,
)

/** Gmail session for Magnon. The server assigns lite or premium; the app only sends its flavor. */
object MagnonAccount {
    private const val TAG = "MagnonAccount"
    private const val PREFS = "magnon-account"
    private const val SESSION = "session"
    private const val SUB = "sub"
    private const val EMAIL = "email"

    fun configured(): Boolean = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

    fun hasSession(context: Context): Boolean = session(context) != null

    /**
     * Sign in with Google button flow from Credential Manager.
     * https://developer.android.com/identity/sign-in/credential-manager-siwg
     */
    /** Shows the Google account picker. The network call stays in [completeSignIn]. */
    suspend fun requestGoogle(activityContext: Context): GoogleSignIn {
        if (!configured()) throw IllegalStateException("login_not_configured")
        val nonce = generateSecureRandomNonce()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val manager = CredentialManager.create(activityContext)
        val result = try {
            manager.getCredential(MutableContextWrapper(activityContext), request)
        } catch (_: GetCredentialCancellationException) {
            throw IllegalStateException("sign_in_cancelled")
        } catch (_: NoCredentialException) {
            throw IllegalStateException("no_google_account")
        } catch (error: GetCredentialException) {
            throw IllegalStateException(error.errorMessage?.toString() ?: error.javaClass.simpleName)
        }
        val idToken = googleIdToken(result)
        val claims = tokenClaims(idToken)
        val sub = claims.optString("sub")
        val email = claims.optString("email")
        if (sub.isBlank()) throw IllegalStateException("missing_google_id")
        val google = GoogleSignIn(
            idToken = idToken,
            nonce = nonce,
            sub = sub,
            email = email,
            name = claims.optString("name").ifBlank { email.substringBefore("@") },
        )
        activityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SUB, google.sub)
            .putString(EMAIL, google.email)
            .apply()
        AppLog.i(TAG, "google id ${google.sub} ${google.email}")
        return google
    }

    suspend fun completeSignIn(activityContext: Context, google: GoogleSignIn): MagnonProfile {
        val response = withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("id_token", google.idToken)
                .put("nonce", google.nonce)
                .put("device_id", CollectorClient.deviceId(activityContext))
                .put("flavor", BuildConfig.FLAVOR_LEVEL)
            post(activityContext, "/v1/auth/google", body, null)
        }
        val token = response.optString("session")
        if (token.isBlank()) {
            val error = response.optString("error").ifBlank { "login_failed" }
            AppLog.w(TAG, "collector rejected $error")
            throw IllegalStateException(error)
        }
        activityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SESSION, token).apply()
        val parsed = profile(response.getJSONObject("profile"))
        return parsed.copy(
            sub = parsed.sub.ifBlank { google.sub },
            email = parsed.email.ifBlank { google.email },
            name = parsed.name.ifBlank { google.name },
        )
    }

    suspend fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }.onFailure { AppLog.w(TAG, "clear credential ${it.javaClass.simpleName}") }
        AppLog.i(TAG, "signed out")
    }

    fun remembered(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(EMAIL, "").orEmpty() to prefs.getString(SUB, "").orEmpty()
    }

    fun savedIdentity(context: Context): String {
        val (email, sub) = remembered(context)
        return listOf(email, sub).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun googleIdToken(result: GetCredentialResponse): String {
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("unexpected_credential")
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GoogleIdTokenParsingException) {
            throw IllegalStateException("invalid_google_token")
        }
    }

    fun me(context: Context): MagnonProfile? {
        val token = session(context) ?: return null
        val response = get(context, "/api/magnon/me?flavor=${BuildConfig.FLAVOR_LEVEL}", token)
        if (response.has("error")) return null
        return profile(response)
    }


: JSONObject = get(context, "/v1/magnon/jobs", session(context))



    private fun profile(json: JSONObject): MagnonProfile {
        return MagnonProfile(
            name = json.optString("name"),
            level = json.optString("level"),
            role = json.optString("role"),
            stayOnLite = json.optBoolean("stay_on_lite"),
            email = json.optString("email"),
            sub = json.optString("sub"),
        )
    }

    private fun tokenClaims(idToken: String): JSONObject {
        val payload = idToken.split(".").getOrNull(1) ?: throw IllegalStateException("invalid_google_token")
        val padded = payload.replace('-', '+').replace('_', '/')
        val pad = (4 - padded.length % 4) % 4
        val bytes = Base64.decode(padded + "=".repeat(pad), Base64.DEFAULT)
        return JSONObject(String(bytes, StandardCharsets.UTF_8))
    }

: String = buildString {
        append("Bulan ini\n")
        append(names(json.optJSONArray("month")))
        append("\nSepanjang waktu\n")
        append(names(json.optJSONArray("all_time")))
    }


    internal fun session(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SESSION, null)?.takeIf { it.isNotBlank() }

    internal fun get(context: Context, path: String, token: String?): JSONObject {
        val connection = open(path, token)
        connection.requestMethod = "GET"
        return read(connection)
    }

    internal fun post(context: Context, path: String, body: JSONObject, token: String?): JSONObject {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = open(path, token)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(bytes) }
        return read(connection)
    }

    private fun open(path: String, token: String?): HttpURLConnection {
        val connection = (URL("https://${BuildConfig.COLLECTOR_HOST}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Connection", "close")
        }
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) JSONObject().put("error", "empty") else JSONObject(text)
        } catch (error: Exception) {
            JSONObject().put("error", error.message ?: "network")
        } finally {
            connection.disconnect()
        }
    }
}
