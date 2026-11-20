package com.skul9x.doctruyen.network

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.skul9x.doctruyen.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Centralized defensive network error resolver.
 * Maps raw Java/OkHttp/Retrofit/Gson exceptions into polite, user-friendly Vietnamese messages.
 * Prevents technical stack traces and internal class names (e.g., ParameterizedType ClassCastException)
 * from ever reaching the user-facing UI.
 */
@Keep
object NetworkErrorResolver {

    const val MSG_NO_INTERNET = "Không thể kết nối Internet. Vui lòng kiểm tra lại Wifi hoặc 4G."
    const val MSG_TIMEOUT = "Máy chủ phản hồi quá lâu. Vui lòng thử lại sau giây lát."
    const val MSG_AUTH_EXPIRED = "Phiên kết nối hết hạn, đang xác thực lại..."
    const val MSG_DATA_PROCESSING = "Có lỗi xảy ra khi xử lý dữ liệu. Vui lòng nhấn Thử lại."
    const val MSG_UNKNOWN = "Không thể tải dữ liệu lúc này. Vui lòng thử lại."

    @Keep
    data class ResolvedError(
        val userMessage: String,
        val isAuthError: Boolean,
        val canRetry: Boolean
    )

    /**
     * Determines whether the given throwable indicates an expired or missing hosting cookie
     * (e.g. Free.nf hosting anti-bot HTML challenge or HTTP 403 Forbidden).
     */
    fun shouldRefreshAuth(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        if (throwable is MalformedJsonException || throwable is JsonSyntaxException) {
            return true
        }
        val msg = throwable.message ?: ""
        return msg.contains("403", ignoreCase = true) ||
               msg.contains("forbidden", ignoreCase = true) ||
               msg.contains("html", ignoreCase = true)
    }

    /**
     * Resolves a throwable into a user-friendly ResolvedError.
     * Guaranteed never to leak technical stack traces, reflection names, or package identifiers.
     */
    fun resolve(throwable: Throwable?, context: Context? = null): ResolvedError {
        if (throwable == null) {
            val defaultMsg = getStringSafely(context, R.string.error_unknown, MSG_UNKNOWN)
            return ResolvedError(
                userMessage = defaultMsg,
                isAuthError = false,
                canRetry = true
            )
        }

        // 1. Connection / No Internet
        if (throwable is UnknownHostException || throwable is ConnectException) {
            val msg = getStringSafely(context, R.string.error_no_internet, MSG_NO_INTERNET)
            return ResolvedError(
                userMessage = msg,
                isAuthError = false,
                canRetry = true
            )
        }

        // 2. Timeout
        if (throwable is SocketTimeoutException) {
            val msg = getStringSafely(context, R.string.error_timeout, MSG_TIMEOUT)
            return ResolvedError(
                userMessage = msg,
                isAuthError = false,
                canRetry = true
            )
        }

        // 3. Auth / Cookie expiration / HTML challenge
        if (shouldRefreshAuth(throwable)) {
            val msg = getStringSafely(context, R.string.error_auth_expired, MSG_AUTH_EXPIRED)
            return ResolvedError(
                userMessage = msg,
                isAuthError = true,
                canRetry = true
            )
        }

        // 4. ClassCastException / Reflection / Deserialization error
        if (throwable is ClassCastException || throwable is TypeNotPresentException) {
            val msg = getStringSafely(context, R.string.error_data_processing, MSG_DATA_PROCESSING)
            return ResolvedError(
                userMessage = msg,
                isAuthError = false,
                canRetry = true
            )
        }

        // 5. General or unknown exception - sanitize against internal class names
        val rawMessage = throwable.message
        val containsTechnicalDetails = isTechnicalMessage(rawMessage)

        val userMsg = if (rawMessage.isNullOrBlank() || containsTechnicalDetails) {
            getStringSafely(context, R.string.error_unknown, MSG_UNKNOWN)
        } else {
            rawMessage
        }

        return ResolvedError(
            userMessage = userMsg,
            isAuthError = false,
            canRetry = true
        )
    }

    private fun isTechnicalMessage(msg: String?): Boolean {
        if (msg == null) return false
        val lower = msg.lowercase()
        return lower.contains("java.lang") ||
               lower.contains("reflect") ||
               lower.contains("parameterizedtype") ||
               lower.contains("retrofit") ||
               lower.contains("okhttp") ||
               lower.contains("gson") ||
               lower.contains("exception") ||
               lower.contains("classcast") ||
               lower.contains("nullpointer") ||
               lower.contains("coroutine")
    }

    private fun getStringSafely(context: Context?, resId: Int, fallback: String): String {
        return try {
            val res = context?.getString(resId)
            if (!res.isNullOrBlank()) res else fallback
        } catch (_: Throwable) {
            fallback
        }
    }
}

