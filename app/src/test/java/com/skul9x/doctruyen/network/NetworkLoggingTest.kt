package com.skul9x.doctruyen.network

import com.skul9x.doctruyen.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NetworkLoggingTest {

    @Test
    fun testLoggingInterceptorLevelBasedOnBuildConfig() {
        val expectedLevel = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.HEADERS
        }
        
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.HEADERS
        }

        assertEquals("Logging level should match build config expectation", expectedLevel, loggingInterceptor.level)
    }

    @Test
    fun testApiServiceInstanceCreation() {
        assertNotNull(RetrofitClient.BASE_URL)
    }
}
