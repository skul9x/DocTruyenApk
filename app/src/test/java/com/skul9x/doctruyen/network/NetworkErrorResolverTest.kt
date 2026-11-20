package com.skul9x.doctruyen.network

import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Phase 03 Comprehensive File-Based Test: NetworkErrorResolverTest
 *
 * Verifies the defensive network error translation architecture:
 * 1. testResolveUnknownHostException: UnknownHostException & ConnectException translate to internet connectivity messages.
 * 2. testResolveTimeoutException: SocketTimeoutException translates to timeout message.
 * 3. testResolveAuthAndHtmlParsingError: MalformedJsonException, JsonSyntaxException & 403 Forbidden trigger auth refresh (isAuthError = true).
 * 4. testResolveReflectionClassCastException: ClassCastException (R8 ParameterizedType issue) shields internal technical names and provides retry guidance.
 * 5. testNullOrEmptyThrowableHandling: Null/empty throwables gracefully resolve without crashes or technical leakage.
 */
class NetworkErrorResolverTest {

    @Test
    fun testResolveUnknownHostException() {
        val unknownHost = UnknownHostException("Unable to resolve host \"skul9x.free.nf\": No address associated with hostname")
        val resolvedUnknownHost = NetworkErrorResolver.resolve(unknownHost)

        assertEquals("Không thể kết nối Internet. Vui lòng kiểm tra lại Wifi hoặc 4G.", resolvedUnknownHost.userMessage)
        assertFalse("UnknownHostException must not be flagged as auth error", resolvedUnknownHost.isAuthError)
        assertTrue("UnknownHostException should allow user retry", resolvedUnknownHost.canRetry)

        val connectEx = ConnectException("Failed to connect to /185.27.134.138:443")
        val resolvedConnect = NetworkErrorResolver.resolve(connectEx)

        assertEquals("Không thể kết nối Internet. Vui lòng kiểm tra lại Wifi hoặc 4G.", resolvedConnect.userMessage)
        assertFalse(resolvedConnect.isAuthError)
        assertTrue(resolvedConnect.canRetry)
    }

    @Test
    fun testResolveTimeoutException() {
        val timeoutEx = SocketTimeoutException("timeout")
        val resolved = NetworkErrorResolver.resolve(timeoutEx)

        assertEquals("Máy chủ phản hồi quá lâu. Vui lòng thử lại sau giây lát.", resolved.userMessage)
        assertFalse("Timeout error is not an auth error", resolved.isAuthError)
        assertTrue("Timeout error must allow retry", resolved.canRetry)
    }

    @Test
    fun testResolveAuthAndHtmlParsingError() {
        // 1. MalformedJsonException from HTML challenge page
        val malformedJsonEx = MalformedJsonException("Use JsonReader.setLenient(true) to accept malformed JSON at line 1 column 1 path $")
        assertTrue("MalformedJsonException should request auth refresh", NetworkErrorResolver.shouldRefreshAuth(malformedJsonEx))
        val resolvedMalformed = NetworkErrorResolver.resolve(malformedJsonEx)
        assertTrue("isAuthError must be true for MalformedJsonException", resolvedMalformed.isAuthError)
        assertTrue("canRetry must be true", resolvedMalformed.canRetry)
        assertEquals("Phiên kết nối hết hạn, đang xác thực lại...", resolvedMalformed.userMessage)

        // 2. JsonSyntaxException
        val syntaxEx = JsonSyntaxException("Expected BEGIN_OBJECT but was STRING at line 1 column 1")
        assertTrue("JsonSyntaxException should request auth refresh", NetworkErrorResolver.shouldRefreshAuth(syntaxEx))
        val resolvedSyntax = NetworkErrorResolver.resolve(syntaxEx)
        assertTrue(resolvedSyntax.isAuthError)
        assertEquals("Phiên kết nối hết hạn, đang xác thực lại...", resolvedSyntax.userMessage)

        // 3. HTTP 403 Forbidden message
        val forbiddenEx = RuntimeException("HTTP 403 Forbidden - Access denied by security challenge")
        assertTrue("403 Forbidden should trigger auth refresh", NetworkErrorResolver.shouldRefreshAuth(forbiddenEx))
        val resolvedForbidden = NetworkErrorResolver.resolve(forbiddenEx)
        assertTrue(resolvedForbidden.isAuthError)
        assertTrue(resolvedForbidden.canRetry)

        // 4. HTML block response exception
        val htmlEx = RuntimeException("Response payload contains <html> challenge from hosting")
        assertTrue("HTML content response should trigger auth refresh", NetworkErrorResolver.shouldRefreshAuth(htmlEx))
        val resolvedHtml = NetworkErrorResolver.resolve(htmlEx)
        assertTrue(resolvedHtml.isAuthError)
        assertTrue(resolvedHtml.canRetry)
    }

    @Test
    fun testResolveReflectionClassCastException() {
        // Realistic exception from R8 Full Mode stripping parameterized type information:
        val r8CrashException = ClassCastException("java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType")
        val resolved = NetworkErrorResolver.resolve(r8CrashException)

        // Verify technical internal paths are NEVER leaked to the user
        assertFalse("Must not expose java.lang package", resolved.userMessage.contains("java.lang"))
        assertFalse("Must not expose reflect package", resolved.userMessage.contains("reflect"))
        assertFalse("Must not expose ParameterizedType identifier", resolved.userMessage.contains("ParameterizedType"))
        assertFalse("Must not expose ClassCastException identifier", resolved.userMessage.contains("ClassCastException"))
        assertFalse("Must not expose retrofit2 package", resolved.userMessage.contains("retrofit2"))
        assertFalse("Must not expose okhttp3 package", resolved.userMessage.contains("okhttp3"))

        // Verify user-friendly guidance and retry action
        assertEquals("Có lỗi xảy ra khi xử lý dữ liệu. Vui lòng nhấn Thử lại.", resolved.userMessage)
        assertFalse("Reflection error is not an auth error", resolved.isAuthError)
        assertTrue("Reflection error must permit retry", resolved.canRetry)
    }

    @Test
    fun testNullOrEmptyThrowableHandling() {
        // 1. Null throwable
        val resolvedNull = NetworkErrorResolver.resolve(null)
        assertNotNull(resolvedNull.userMessage)
        assertFalse(resolvedNull.userMessage.isBlank())
        assertEquals("Không thể tải dữ liệu lúc này. Vui lòng thử lại.", resolvedNull.userMessage)
        assertFalse(resolvedNull.isAuthError)
        assertTrue(resolvedNull.canRetry)

        // 2. Exception with empty message
        val resolvedEmptyMsg = NetworkErrorResolver.resolve(RuntimeException(""))
        assertNotNull(resolvedEmptyMsg.userMessage)
        assertFalse(resolvedEmptyMsg.userMessage.isBlank())
        assertEquals("Không thể tải dữ liệu lúc này. Vui lòng thử lại.", resolvedEmptyMsg.userMessage)
        assertFalse(resolvedEmptyMsg.isAuthError)
        assertTrue(resolvedEmptyMsg.canRetry)

        // 3. Exception with null message
        val resolvedNullMsg = NetworkErrorResolver.resolve(RuntimeException())
        assertNotNull(resolvedNullMsg.userMessage)
        assertFalse(resolvedNullMsg.userMessage.isBlank())
        assertEquals("Không thể tải dữ liệu lúc này. Vui lòng thử lại.", resolvedNullMsg.userMessage)
        assertFalse(resolvedNullMsg.isAuthError)
        assertTrue(resolvedNullMsg.canRetry)

        // 4. Exception containing technical keywords should be sanitized to fallback
        val technicalEx = RuntimeException("retrofit2.HttpException: internal error occurred")
        val resolvedTech = NetworkErrorResolver.resolve(technicalEx)
        assertFalse(resolvedTech.userMessage.contains("retrofit2"))
        assertFalse(resolvedTech.userMessage.contains("HttpException"))
        assertEquals("Không thể tải dữ liệu lúc này. Vui lòng thử lại.", resolvedTech.userMessage)
    }
}

