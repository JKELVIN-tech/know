package com.knowapp.android.data.network

import com.google.gson.Gson
import com.knowapp.android.data.Session
import com.knowapp.android.data.model.TokenResponse
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Route
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

import com.knowapp.android.BuildConfig
import com.knowapp.android.data.SessionStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Attaches the stored bearer token to every outgoing request, if present. */
private class AuthInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessionStore.currentToken()
        val request = chain.request().let { original ->
            if (token != null) {
                original.newBuilder().addHeader("Authorization", "Bearer $token").build()
            } else {
                original
            }
        }
        return chain.proceed(request)
    }
}

// Shows the last saved copy of a read when the server is slow to answer (for example waking up), and refreshes it whenever the server does answer
private class StaleIfSlowInterceptor(dir: File, private val sessionStore: SessionStore) : Interceptor {
    private val folder = File(dir, "know_reads").also { it.mkdirs() }

    private fun fileFor(request: Request): File {
        val raw = "${sessionStore.session.value?.userId}|${request.url}"
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder, name)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (request.method != "GET") {
            val response = chain.proceed(request)
            if (response.isSuccessful) folder.listFiles()?.forEach { it.delete() }
            return response
        }
        if (path.contains("/api/auth") || path.contains("pdf") || path.contains("download")) return chain.proceed(request)
        val saved = fileFor(request)
        val hasCopy = saved.exists() && System.currentTimeMillis() - saved.lastModified() < 7 * 24 * 3600 * 1000L
        val response = try {
            if (hasCopy) chain.withConnectTimeout(2500, TimeUnit.MILLISECONDS).withReadTimeout(2500, TimeUnit.MILLISECONDS).proceed(request) else chain.proceed(request)
        } catch (e: IOException) {
            if (!hasCopy) throw e
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Saved copy")
                .body(saved.readBytes().toResponseBody("application/json".toMediaType())).build()
        }
        if (response.isSuccessful) {
            try { saved.writeBytes(response.peekBody(5_000_000).bytes()) } catch (_: IOException) { }
        }
        return response
    }
}

// Marks every request as coming from the Android app, which gets a long-lived session
private class ClientHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Client", "android").build())
}

// When the server says the session has expired, sign in again with the saved details and retry, so nobody is logged out
private class SilentLoginAuthenticator(private val sessionStore: SessionStore) : Authenticator {
    private val gson = Gson()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.endsWith("/api/auth/login")) return null
        if (responseCount(response) >= 2) return null
        synchronized(this) {
            val current = sessionStore.currentToken()
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
            // Another request already renewed the session while this one waited
            if (current != null && sent != null && current != sent) {
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val creds = sessionStore.credentials() ?: return null
            val client = OkHttpClient.Builder().connectTimeout(45, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
            val form = FormBody.Builder().add("username", creds.first).add("password", creds.second).build()
            val request = Request.Builder().url("${BuildConfig.API_BASE_URL}/api/auth/login").header("X-Client", "android").post(form).build()
            return try {
                client.newCall(request).execute().use { r ->
                    when {
                        r.isSuccessful -> {
                            val body = gson.fromJson(r.body?.string(), TokenResponse::class.java) ?: return null
                            sessionStore.save(Session(body.accessToken, body.userId, body.role, body.staffType, body.fullName, body.institutionId))
                            response.request.newBuilder().header("Authorization", "Bearer ${body.accessToken}").build()
                        }
                        // The password was changed or the account switched off: only then does the person need to sign in again
                        r.code == 401 || r.code == 403 -> {
                            sessionStore.clear()
                            null
                        }
                        else -> null
                    }
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

object NetworkModule {
    fun buildApiService(sessionStore: SessionStore, cacheDir: File): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        // Render's free tier cold-starts after inactivity — a first request
        // can take 30-50s to wake the backend, so timeouts are generous
        // rather than the OkHttp default of 10s.
        val client = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(ClientHeaderInterceptor())
            .addInterceptor(AuthInterceptor(sessionStore))
            .addInterceptor(StaleIfSlowInterceptor(cacheDir, sessionStore))
            .authenticator(SilentLoginAuthenticator(sessionStore))
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ApiService::class.java)
    }
}
