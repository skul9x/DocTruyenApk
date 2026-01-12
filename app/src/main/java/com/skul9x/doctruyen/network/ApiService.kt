package com.skul9x.doctruyen.network

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ============ Data Models ============

data class Story(
    val id: Int,
    val title: String,
    val image: String?,
    val content: String? = null,
    @SerializedName("content_text") val contentText: String? = null
)

data class StoryListResponse(
    val status: String,
    val data: List<Story> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerializedName("total_pages") val totalPages: Int = 1,
    val message: String? = null
)

data class StoryDetailResponse(
    val status: String,
    val data: Story
)

data class RandomStoryResponse(
    val status: String,
    val data: Story
)

// ============ API Interface ============

interface ApiService {
    
    @GET("api.php")
    suspend fun getStories(
        @Query("action") action: String = "list",
        @Query("page") page: Int = 1,
        @Query("search") search: String? = null,
        @Query("sort") sort: String = "newest"
    ): StoryListResponse
    
    @GET("api.php")
    suspend fun getStoryDetail(
        @Query("action") action: String = "detail",
        @Query("id") id: Int
    ): StoryDetailResponse
    
    @GET("api.php")
    suspend fun getRandomStory(
        @Query("action") action: String = "random"
    ): RandomStoryResponse
}

// ============ Retrofit Client ============

object RetrofitClient {
    // Cookie from HostingVerifier
    var cookie: String = ""
    
    fun saveCookie(context: android.content.Context, newCookie: String) {
        cookie = newCookie
        com.skul9x.doctruyen.utils.UserConfig.setCookie(context, newCookie)
    }
    
    fun loadCookie(context: android.content.Context) {
        cookie = com.skul9x.doctruyen.utils.UserConfig.getCookie(context)
    }
    
    fun clearCookie(context: android.content.Context) {
        cookie = ""
        com.skul9x.doctruyen.utils.UserConfig.setCookie(context, "")
    }
    
    // We need context to get the URL, so we can't be a pure singleton with init
    private var retrofit: Retrofit? = null
    private val gson = GsonBuilder().setLenient().create()
    
    var BASE_URL = "https://skul9x.free.nf/truyen/" // Fallback/Cache
    
    private fun createClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                    .addHeader("X-Requested-With", "com.skul9x.doctruyen")
                    // MUST match HostingVerifier User-Agent exactly!
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                
                // Add cookie if we have one from HostingVerifier
                if (cookie.isNotEmpty()) {
                    requestBuilder.addHeader("Cookie", cookie)
                }
                
                val finalRequest = requestBuilder.build()

                // Detailed Request Logging
                com.skul9x.doctruyen.utils.DebugLogger.log(
                    "Network", 
                    "Request: ${finalRequest.method} ${finalRequest.url}\nHeaders: ${finalRequest.headers}",
                    com.skul9x.doctruyen.utils.LogType.REQUEST
                )

                val response = try {
                    chain.proceed(finalRequest)
                } catch (e: Exception) {
                    com.skul9x.doctruyen.utils.DebugLogger.logError("Network Error", "${e.message}", e)
                    throw e
                }
                
                // Detailed Response Logging
                val responseBody = response.peekBody(Long.MAX_VALUE).string()
                com.skul9x.doctruyen.utils.DebugLogger.log(
                    "Network", 
                    "Response: ${response.code} ${response.message}\nURL: ${response.request.url}\nBody: $responseBody",
                    if (response.isSuccessful) com.skul9x.doctruyen.utils.LogType.RESPONSE else com.skul9x.doctruyen.utils.LogType.ERROR
                )
                
                response
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    private fun getClient(baseUrl: String): Retrofit {
        // If URL changed or retrofit is null, rebuild
        if (retrofit == null || retrofit?.baseUrl().toString() != baseUrl) {
            BASE_URL = baseUrl
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(createClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        }
        return retrofit!!
    }
    
    // Get ApiService with specific URL (from settings)
    fun getApiService(context: android.content.Context): ApiService {
        val url = com.skul9x.doctruyen.utils.UserConfig.getBaseUrl(context)
        return getClient(url).create(ApiService::class.java)
    }

    // Overload for places where we might rely on cached BASE_URL or existing instance
    // But ideally should always pass context
    fun getApiService(): ApiService {
        if (retrofit == null) {
             throw IllegalStateException("Retrofit not initialized. Call getApiService(context) first.")
        }
        return retrofit!!.create(ApiService::class.java)
    }
    
    // Reset client (useful when cookie changes)
    fun resetClient() {
        retrofit = null
    }
}
