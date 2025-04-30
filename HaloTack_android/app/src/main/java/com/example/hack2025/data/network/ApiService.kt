package com.example.hack2025.data.network

// Adjust imports if your models are in 'data.models'
import com.example.hack2025.data.models.LoginRequest
import com.example.hack2025.data.models.NotificationResponse // Import new response model
import com.example.hack2025.data.models.UserInfo
import com.example.hack2025.data.models.MonitorDataResponse

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET // Import for GET requests
import retrofit2.http.Header // Import for adding headers
import retrofit2.http.POST

/**
 * File Path: app/src/main/java/com/example/hack2025/data/network/ApiService.kt
 *
 * Defines the API endpoints using Retrofit annotations.
 */
interface ApiService {

    /**
     * Sends login credentials to the server.
     * Endpoint: /api/auth/login (Adjust if needed)
     */
    @POST("api/auth/login")
    suspend fun loginUser(@Body loginRequest: LoginRequest): Response<UserInfo>

    /**
     * Sends notification data (e.g., ping response) to the server using a Map.
     * Endpoint: /api/notify/send
     */
    @POST("api/notify/send")
    suspend fun sendNotification(@Body notificationData: Map<String, String>): Response<Unit>

    /**
     * Checks the notification inbox for the authenticated user.
     * Endpoint: /api/notifications/check
     * Requires authentication via Authorization header.
     *
     * @param authToken The Bearer token for authentication.
     * @return A Response containing NotificationResponse or an empty object if no notification.
     */
    @GET("api/notifications/check") // New GET endpoint
    suspend fun checkNotifications(
        @Header("Authorization") authToken: String // Pass token in header
    ): Response<NotificationResponse> // Expecting the new response type


    @GET("api/monitor/current")
    suspend fun getCurrentMonitorData(): Response<MonitorDataResponse> // Expecting MonitorDataResponse

}