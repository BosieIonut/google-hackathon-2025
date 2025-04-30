package com.example.hack2025.data.models


import com.google.gson.annotations.SerializedName

/**
 * File Path: app/src/main/java/com/example/hack2025/data/models/MonitorDataResponse.kt
 *
 * Represents the monitoring data received from the /api/monitor/current endpoint.
 * Fields are nullable as the initial state on the server might be null.
 */
data class MonitorDataResponse(
    @SerializedName("temperature")
    val temperature: Float?, // Using Float for potential decimal values

    @SerializedName("humidity")
    val humidity: Float?, // Using Float for potential decimal values

    @SerializedName("timestamp")
    val timestamp: String? // Optional timestamp string from the server
)
