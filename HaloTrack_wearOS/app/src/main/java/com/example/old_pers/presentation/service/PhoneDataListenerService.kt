package com.example.old_pers.presentation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.old_pers.presentation.MainActivity // Replace with your actual MainActivity path
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class PhoneDataListenerService : WearableListenerService() {

    private val CHANNEL_ID = "PhoneDataChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        Log.d("PhoneDataListener", "onCreate() called")
        createNotificationChannel()
        val notification: NotificationCompat.Builder = buildNotification()
        startForeground(NOTIFICATION_ID, notification.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Phone Data Service"
            val descriptionText = "Listening for data from the phone"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app's icon
            .setContentTitle("Phone Data Service")
            .setContentText("Listening for data...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("PhoneDataListener", "onMessageReceived() called")
        Log.d("PhoneDataListener", "Received message with path: ${messageEvent.path}")

        when (messageEvent.path) {
            "/phone_to_watch" -> {
                val data = String(messageEvent.data, StandardCharsets.UTF_8)
                Log.d("PhoneDataListener", "Data received: $data")
                processReceivedData(data)
            }
            // Add other message paths here if needed
        }
    }

    override fun onDestroy() {
        Log.d("PhoneDataListener", "onDestroy() called")
        super.onDestroy()
    }

    private fun processReceivedData(data: String) {
        Log.i("DataProcessing", "Processing data: $data")
        // Implement your data processing logic here
    }
}