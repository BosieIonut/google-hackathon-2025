package com.example.hack2025.data.models // Ensure this matches your package structure

import com.google.gson.annotations.SerializedName

/**
 * File Path: app/src/main/java/com/example/hack2025/data/models/NotificationResponse.kt
 *
 * Represents a notification received from the /api/notifications/check endpoint.
 * Fields should match the JSON keys returned by your Flask server.
 * Make fields nullable if they might be missing (e.g., if the endpoint returns {}).
 */
data class NotificationResponse(
    @SerializedName("sender_email")
    val senderEmail: String?, // Email of the person who sent the notification

    @SerializedName("type")
    val type: String? // e.g., "check_ok"
)