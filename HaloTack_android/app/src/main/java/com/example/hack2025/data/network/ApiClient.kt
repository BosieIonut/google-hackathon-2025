package com.example.hack2025.data.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * File Path: app/src/main/java/com/example/hack2025/data/network/ApiClient.kt
 *
 * Singleton object to configure and provide the Retrofit instance for API calls.
 */
object ApiClient {


    private const val BASE_URL = "http://10.200.23.240:2242"

    // Lazy initialization of the ApiService
    val instance: ApiService by lazy {
        // Create a logging interceptor (optional, useful for debugging)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // Log request and response bodies
        }

        // Configure OkHttpClient
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor) // Add logging interceptor
            .connectTimeout(30, TimeUnit.SECONDS) // Connection timeout
            .readTimeout(30, TimeUnit.SECONDS) // Read timeout
            .writeTimeout(30, TimeUnit.SECONDS) // Write timeout
            .build()

        // Configure Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Set the custom OkHttpClient
            .addConverterFactory(GsonConverterFactory.create()) // Use Gson for JSON parsing
            .build()

        // Create and return the ApiService implementation
        retrofit.create(ApiService::class.java)
    }
}
