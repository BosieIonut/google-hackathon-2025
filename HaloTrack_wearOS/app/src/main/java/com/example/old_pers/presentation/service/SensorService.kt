package com.example.old_pers.presentation.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.old_pers.R // Make sure this points to your R file
import com.example.old_pers.presentation.MainActivity // Import MainActivity for notification pending intent
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.*
import java.util.LinkedList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.core.content.ContextCompat
// Optional: If using explicit sound URI later
// import android.media.RingtoneManager

// --- Constants ---
// Actions for Service <-> Activity Communication (Broadcasts)
const val POTENTIAL_FALL_DETECTED_ACTION = "com.example.old_pers.POTENTIAL_FALL_DETECTED"
const val USER_CONFIRMED_OK_ACTION = "com.example.old_pers.USER_CONFIRMED_OK"
const val FALL_ALERT_SENT_ACTION = "com.example.old_pers.FALL_ALERT_SENT"
const val REQUEST_SERVICE_STATUS_ACTION = "com.example.old_pers.REQUEST_SERVICE_STATUS" // For Activity -> Service state request

// Foreground Service Notification Constants
const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
const val FOREGROUND_NOTIFICATION_ID = 1 // ID for the persistent foreground service notification

// Fall Alert Notification Constants
const val ALERT_NOTIFICATION_CHANNEL_ID = "FallAlertChannel" // Separate channel for alerts
const val ALERT_NOTIFICATION_ID = 2 // Different ID for the fall alert notification

// --- NEW: BPM Alert Constants ---
const val BPM_LOW_THRESHOLD = 50f  // Example: Alert if below 50 BPM
const val BPM_HIGH_THRESHOLD = 120f // Example: Alert if above 120 BPM (resting)
// --- End Constants ---


class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var heartRateSensor: Sensor? = null // BPM Sensor

    // --- Calibration and Filter variables ---
    private var altitudeBias = 0.0
    private val P0 = 1013.25f // Standard atmospheric pressure at sea level in hPa (mbar)
    private var isCalibrating = false
    private val calibrationSamples = mutableListOf<FloatArray>()
    private val calibrationSampleLimit = 90
    private val altitudeCalibrationSamples = mutableListOf<Double>()
    private val altitudeCalibrationSampleLimit = 100
    private var isAltitudeCalibrating = false
    private var lastAltitude = 0.0
    private var totalAcceleration = 0.0

    // IIR Filter Coefficients
    private val b0 = 0.6065; private val b1 = -0.6065
    private val a1 = -1.213; private val a2 = 0.3679
    private var y1 = 0.0; private var y2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0

    // Accelerometer Bias values
    private var x_biass = 0f; private var y_biass = 0f; private var z_biass = 0f

    // --- Fall detection variables ---
    @Volatile // Ensure visibility across threads
    private var potentialFallDetected = false
    private var fallConfirmationTimeoutJob: Job? = null
    private val fallConfirmationTimeoutDuration = 15000L // 20 seconds timeout
    private val fallThresholdAltitude = -0.3
    private val fallThresholdLowAcceleration = 2
    private val fallThresholdHighAcceleration = 20.0
    private val fallDetectionDuration = 1000L // 1 second analysis window

    private data class SensorData(val timestamp: Long, val altitude: Double, val acceleration: Double)
    private val sensorDataBuffer = LinkedList<SensorData>()

    // --- NEW: BPM related variables ---
    private var lastKnownBpm: Float = 0f // Stores the last valid BPM reading
    @Volatile private var isBpmAlertActiveLow = false // State flag for low BPM alert
    @Volatile private var isBpmAlertActiveHigh = false // State flag for high BPM alert
    // --- End BPM variables ---

    // Use Dispatchers.IO for network calls, Dispatchers.Default for heavy computation if needed
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job()) // Use Default for sensor processing
    private val networkScope = CoroutineScope(Dispatchers.IO + Job()) // Separate scope for network

    // BroadcastReceiver to listen for "I'm OK" confirmation from MainActivity
    private val userConfirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == USER_CONFIRMED_OK_ACTION && intent.`package` == packageName) {
                Log.d("SensorService", "User confirmed OK via broadcast. Cancelling fall alert.")
                cancelFallAlertProcedure()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d("SensorService", "onCreate")

        // Sensor Initialization
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) // Get HR sensor

        // Sampling rate for motion sensors (e.g., 20Hz)
        val sensorDelayMicroseconds = 50_000

        accelerometer?.also {
            sensorManager.registerListener(this, it, sensorDelayMicroseconds)
            Log.d("SensorService", "Accelerometer sensor registered (${sensorDelayMicroseconds}us)")
        } ?: Log.w("SensorService", "Accelerometer sensor not available.")

        pressureSensor?.also {
            sensorManager.registerListener(this, it, sensorDelayMicroseconds)
            Log.d("SensorService", "Pressure sensor registered (${sensorDelayMicroseconds}us)")
        } ?: Log.w("SensorService", "Pressure sensor not available.")

        // Register Heart Rate Sensor
//        heartRateSensor?.also {
//            // Use a standard delay for HR to conserve battery vs motion sensors
//            val hrSamplingRate = SensorManager.SENSOR_DELAY_NORMAL
//            sensorManager.registerListener(this, it, hrSamplingRate)
//            Log.d("SensorService", "Heart Rate sensor registered (Delay: $hrSamplingRate)")
//        } ?: Log.w("SensorService", "Heart Rate sensor not available. Make sure BODY_SENSORS permission is granted.")

        // Register broadcast receiver for USER_CONFIRMED_OK
        val filter = IntentFilter(USER_CONFIRMED_OK_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userConfirmationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            Log.d("SensorService", "Registered userConfirmationReceiver (API 33+)")
        } else {
            registerReceiver(userConfirmationReceiver, filter)
            Log.d("SensorService", "Registered userConfirmationReceiver")
        }

        // Create notification channels (Foreground + Alert)
        createNotificationChannels()

        // Start as a foreground service
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        Log.d("SensorService", "Service started in foreground.")
    }

    // --- Foreground Service & Alert Notification Setup ---

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Foreground Service Channel (Low Importance)
            val serviceChannel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                "Sensor Service Status",
                NotificationManager.IMPORTANCE_LOW // Should be silent
            ).apply { description = "Persistent notification for running service." }
            notificationManager?.createNotificationChannel(serviceChannel)

            // Fall Alert Channel (High Importance)
            val alertChannel = NotificationChannel(
                ALERT_NOTIFICATION_CHANNEL_ID,
                "Fall Alerts",
                NotificationManager.IMPORTANCE_HIGH // Should make sound/vibrate by default
            ).apply { description = "Notifications for potential fall detection." }
            notificationManager?.createNotificationChannel(alertChannel)

            // Optional: Could create a separate channel for BPM alerts if needed
            // but for now, we won't create a local notification for BPM alerts.

            Log.d("SensorService", "Notification channels created (Foreground & Alert).")
        }
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else { PendingIntent.FLAG_UPDATE_CURRENT }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // !!! Ensure R.drawable.app_icon exists !!!
        return NotificationCompat.Builder(this, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Fall Detection Active")
            .setContentText("Monitoring movement & BPM") // Updated text
            .setSmallIcon(R.drawable.app_icon) // <-- REPLACE if needed
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Persistent
            .setSilent(true) // No sound for foreground status
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority
            .build()
    }

    @SuppressLint("ObsoleteSdkInt", "WearRecents")
    private fun createFallAlertNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("source", "fall_alert_notification")
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else { PendingIntent.FLAG_UPDATE_CURRENT }
        val pendingIntent = PendingIntent.getActivity(this, 1, notificationIntent, pendingIntentFlags)

        // !!! Use a distinct warning icon if available !!!
        val alertIcon = R.drawable.app_icon // <-- REPLACE with e.g., R.drawable.ic_fall_warning

        // val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) // Example if using explicit sound

        return NotificationCompat.Builder(this, ALERT_NOTIFICATION_CHANNEL_ID) // Use ALERT channel
            .setContentTitle("Potential Fall Detected!")
            .setContentText("Tap to open app and confirm.")
            .setSmallIcon(alertIcon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority for visibility/sound
            .setAutoCancel(true) // Dismiss on tap
            .setDefaults(Notification.DEFAULT_ALL) // Request default sound, vibration, lights
            // .setSound(defaultSoundUri) // Alternative: Explicitly set sound
            // .setVibrate(longArrayOf(0, 500, 200, 500)) // Alternative: Explicitly set vibration
            .build()
    }
    // --- End Notification Setup ---

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("SensorService", "onStartCommand, Action: $action, StartId: $startId")

        when (action) {
            "NOT_WELL" -> {
                sendApiNotification("not_well")
            }
            "CALIBRATE" -> {
                if (!isCalibrating && !isAltitudeCalibrating) {
                    Log.d("SensorService", "Starting calibration via intent")
                    startCalibration()
                } else {
                    Log.d("SensorService", "Ignoring CALIBRATE command, already calibrating.")
                }
            }
            REQUEST_SERVICE_STATUS_ACTION -> {
                Log.d("SensorService", "Received status request from Activity.")
                sendCurrentStatusToActivity()
            }
            null -> {
                Log.d("SensorService", "Service started or restarted without specific action.")
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            }
            else -> {
                Log.w("SensorService", "Received unknown action: $action")
            }
        }
        return START_STICKY
    }

    private fun sendCurrentStatusToActivity() {
        val currentStatusAction = if (potentialFallDetected) {
            POTENTIAL_FALL_DETECTED_ACTION
        } else {
            USER_CONFIRMED_OK_ACTION
        }
        Log.d("SensorService", "Sending current status ($currentStatusAction) back to Activity.")
        val statusIntent = Intent(currentStatusAction)
        statusIntent.setPackage(packageName)
        sendBroadcast(statusIntent)
    }

    private fun startCalibration() {
        if (isCalibrating || isAltitudeCalibrating) {
            Log.w("SensorService", "Calibration requested but already in progress.")
            return
        }
        isCalibrating = true
        isAltitudeCalibrating = true
        calibrationSamples.clear()
        altitudeCalibrationSamples.clear()
        cancelFallAlertProcedure() // Cancel any ongoing fall process
        // Reset BPM alert state on calibration start as well
        isBpmAlertActiveLow = false
        isBpmAlertActiveHigh = false
        Log.d("SensorService", "Started sensor calibration. Keep device still.")
        // TODO: Optional: Update foreground notification text to "Calibrating..."
    }

    // --- Filter and Calibration methods ---
    private fun applyFilter(x: Double): Double {
        val y = b0 * x + b1 * x1 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    private fun calibrate_acc(x: Float, y: Float, z: Float) {
        calibrationSamples.add(floatArrayOf(x, y, z))
        if (calibrationSamples.size >= calibrationSampleLimit) {
            val sum = floatArrayOf(0f, 0f, 0f)
            calibrationSamples.forEach { sample ->
                sum[0] += sample[0]
                sum[1] += sample[1]
                sum[2] += (sample[2] - SensorManager.GRAVITY_EARTH)
            }
            x_biass = sum[0] / calibrationSamples.size
            y_biass = sum[1] / calibrationSamples.size
            z_biass = sum[2] / calibrationSamples.size
            isCalibrating = false
            Log.d("SensorService", "Accelerometer Calibration complete: bias=($x_biass, $y_biass, $z_biass)")
            checkCalibrationComplete()
        }
    }

    private fun calibrate_altitude(altitude : Double) {
        altitudeCalibrationSamples.add(altitude)
        if (altitudeCalibrationSamples.size >= altitudeCalibrationSampleLimit) {
            altitudeBias = altitudeCalibrationSamples.average()
            isAltitudeCalibrating = false
            Log.d("SensorService", "Altitude Calibration complete: altitudeBias=$altitudeBias")
            y1 = 0.0; y2 = 0.0; x1 = 0.0; x2 = 0.0 // Reset filter
            lastAltitude = 0.0
            altitudeCalibrationSamples.clear()
            checkCalibrationComplete()
        }
    }

    private fun checkCalibrationComplete() {
        if (!isCalibrating && !isAltitudeCalibrating) {
            Log.d("SensorService", "All calibrations complete.")
            // TODO: Optional: Update notification text back to "Monitoring movement & BPM"
        }
    }
    // --- End Filter and Calibration ---

    override fun onSensorChanged(event: SensorEvent?) {
        val currentEvent = event ?: return
        val currentTime = System.currentTimeMillis()

        // --- Calibration Handling ---
        if (isCalibrating || isAltitudeCalibrating) {
            when (currentEvent.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER -> if (isCalibrating) calibrate_acc(currentEvent.values[0], currentEvent.values[1], currentEvent.values[2])
                Sensor.TYPE_PRESSURE -> if (isAltitudeCalibrating) {
                    val pressure = currentEvent.values[0]
                    if (pressure > 0) {
                        val rawAltitude = 44330.0 * (1.0 - (pressure / P0).toDouble().pow(1.0 / 5.255))
                        calibrate_altitude(rawAltitude)
                    }
                }
                // Ignore HR during calibration
            }
            return
        }

        // --- Process Sensor Data When Not Calibrating ---
        var motionDataUpdated = false // Tracks if Accel/Pressure updated (relevant for fall buffer)
        when (currentEvent.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = currentEvent.values[0]; val y = currentEvent.values[1]; val z = currentEvent.values[2]
                val correctedX = x - x_biass; val correctedY = y - y_biass; val correctedZ = z - z_biass
                totalAcceleration = sqrt((correctedX * correctedX + correctedY * correctedY + correctedZ * correctedZ).toDouble())
//                Log.d("SensorService", "acc: $totalAcceleration")
                sensorDataBuffer.add(SensorData(currentTime, lastAltitude, totalAcceleration))
                motionDataUpdated = true
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = currentEvent.values[0]
                if (pressure > 0) {
                    var altitude = 44330.0 * (1.0 - (pressure / P0).toDouble().pow(1.0 / 5.255))
                    altitude -= altitudeBias
//                    Log.d("SensorService", "alt: $altitude")
                    val filteredAltitude = applyFilter(altitude)
                    lastAltitude = filteredAltitude
                    sensorDataBuffer.add(SensorData(currentTime, filteredAltitude, totalAcceleration))
                    motionDataUpdated = true
                }
            }
//            Sensor.TYPE_HEART_RATE -> {
//                val bpm = currentEvent.values[0]
//                Log.d("SensorService", "BPM: $bpm")
//                if (bpm > 0) { // Process only valid readings
//                    lastKnownBpm = bpm
//                    // Log.d("SensorService", "Heart Rate Updated: $bpm BPM") // Can be noisy
//                    checkBpmThresholds(bpm) // Check if BPM alert needs to be sent
//                }
//                // BPM changes alone do not trigger fall detection logic re-evaluation
//            }
        } // End when

        // Prune fall buffer and check for fall IF motion data was updated
        if (motionDataUpdated) {
            pruneSensorDataBuffer(currentTime)
            if (!potentialFallDetected && sensorDataBuffer.size > 10) {
                checkFallCondition(currentTime)
            }
        }
    }

    private fun pruneSensorDataBuffer(currentTime: Long) {
        val cutoffTime = currentTime - (fallDetectionDuration + 200L) // Keep ~1.2 seconds
        while (sensorDataBuffer.isNotEmpty() && sensorDataBuffer.first().timestamp < cutoffTime) {
            sensorDataBuffer.removeFirst()
        }
    }

    private fun checkFallCondition(currentTime: Long) {
        val relevantData = sensorDataBuffer.filter { it.timestamp >= currentTime - fallDetectionDuration }
        if (relevantData.size < 1) return // Need enough samples in window

        var minAltitude = Double.MAX_VALUE
        var hasLowAcc = false
        var hasHighAcc = false
        var impactTimestamp = -1L
        var freefallTimestamp = -1L

        relevantData.forEach { data ->
            minAltitude = minOf(minAltitude, data.altitude)
            if (data.acceleration < fallThresholdLowAcceleration) {
                hasLowAcc = true
                if (freefallTimestamp < 0) freefallTimestamp = data.timestamp
            }
            if (data.acceleration > fallThresholdHighAcceleration) {
                hasHighAcc = true
                impactTimestamp = data.timestamp
            }
        }

        val altitudeDropDetected = minAltitude < fallThresholdAltitude
        val timingCorrect = freefallTimestamp <= impactTimestamp || impactTimestamp < 0 // Allow impact first

        // --- Trigger Fall Detection ---
        if (altitudeDropDetected && hasLowAcc && hasHighAcc && timingCorrect) {
            if (potentialFallDetected) return // Already handling a fall

            Log.w("SensorService", "********** POTENTIAL FALL DETECTED! **********")
            potentialFallDetected = true

            // 1. Notify MainActivity UI
            val potentialFallIntent = Intent(POTENTIAL_FALL_DETECTED_ACTION)
            potentialFallIntent.setPackage(packageName)
            sendBroadcast(potentialFallIntent)
            Log.d("SensorService", "Sent POTENTIAL_FALL_DETECTED broadcast")

            // 2. Show High-Priority Notification on Watch
            showFallAlertNotification()

            // 3. Start Timeout Coroutine
            fallConfirmationTimeoutJob?.cancel()
            fallConfirmationTimeoutJob = serviceScope.launch {
                Log.d("SensorService", "Starting ${fallConfirmationTimeoutDuration}ms confirmation timeout.")
                delay(fallConfirmationTimeoutDuration)

                if (potentialFallDetected) { // Check flag again after delay
                    Log.w("SensorService", "Timeout expired. User did not confirm OK. Sending FALL alert API request.")
                    sendApiNotification("fall_detected") // Make API call for FALL
                    potentialFallDetected = false // Reset state *after* sending alert
                    dismissFallAlertNotification()

                    // Notify activity that alert was sent
                    val alertSentIntent = Intent(FALL_ALERT_SENT_ACTION)
                    alertSentIntent.setPackage(packageName)
                    sendBroadcast(alertSentIntent)
                    Log.d("SensorService", "Sent FALL_ALERT_SENT broadcast")
                } else {
                    Log.d("SensorService", "Timeout job finished, but fall was already cancelled.")
                }
            }
        }
    }

    private fun showFallAlertNotification() {
        with(NotificationManagerCompat.from(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@SensorService, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("SensorService", "POST_NOTIFICATIONS permission not granted. Cannot show fall alert notification.")
                return
            }
            notify(ALERT_NOTIFICATION_ID, createFallAlertNotification())
            Log.d("SensorService", "Posted fall alert notification (ID: $ALERT_NOTIFICATION_ID)")
        }
    }

    private fun cancelFallAlertProcedure() {
        if (potentialFallDetected) {
            fallConfirmationTimeoutJob?.cancel()
            fallConfirmationTimeoutJob = null
            potentialFallDetected = false
            Log.d("SensorService", "Fall alert procedure cancelled.")
            dismissFallAlertNotification()

            // Notify activity UI
            val cancelledIntent = Intent(USER_CONFIRMED_OK_ACTION)
            cancelledIntent.setPackage(packageName)
            sendBroadcast(cancelledIntent)
            Log.d("SensorService", "Sent USER_CONFIRMED_OK broadcast (to signal cancellation/reset)")
        }
    }

    private fun dismissFallAlertNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        Log.d("SensorService", "Dismissed fall alert notification (ID: $ALERT_NOTIFICATION_ID)")
    }

    private fun checkBpmThresholds(currentBpm: Float) {
        // --- Check for Low BPM ---
        Log.w("SensorService", "Current: $currentBpm")
        if (currentBpm < BPM_LOW_THRESHOLD ) {
            if (!isBpmAlertActiveLow) {
                Log.w("SensorService", "*** BPM LOW *** Current: $currentBpm < Threshold: $BPM_LOW_THRESHOLD. Sending Alert.")
                isBpmAlertActiveLow = true
                isBpmAlertActiveHigh = false // Ensure high alert state is off
                sendApiNotification("bpm_low") // Send POST request
            }
        }
        // --- Check for High BPM ---
        else if (currentBpm > BPM_HIGH_THRESHOLD) {
            if (!isBpmAlertActiveHigh) {
                Log.w("SensorService", "*** BPM HIGH *** Current: $currentBpm > Threshold: $BPM_HIGH_THRESHOLD. Sending Alert.")
                isBpmAlertActiveHigh = true
                isBpmAlertActiveLow = false // Ensure low alert state is off
                sendApiNotification("bpm_high") // Send POST request
            }
        }
        // --- BPM is within normal range ---
        else {
            if (isBpmAlertActiveLow || isBpmAlertActiveHigh) {
                Log.i("SensorService", "BPM ($currentBpm) returned to normal range [${BPM_LOW_THRESHOLD}-${BPM_HIGH_THRESHOLD}]. Resetting alert flags.")
                // Optional: Send a "bpm_normal" alert if needed by your backend
                // sendBpmAlert("bpm_normal", currentBpm)
            }
            // Reset flags as BPM is normal
            isBpmAlertActiveLow = false
            isBpmAlertActiveHigh = false
        }
    }

    private fun sendApiNotification(notificationType: String, details: JSONObject? = null) {
        networkScope.launch {
            try {
                val client = OkHttpClient()
                // !!! IMPORTANT: Replace hardcoded values !!!
                val url = "http://10.41.61.38:2242/api/notify/send" // <<--- UPDATED Endpoint
                val recipientEmail = "john@email.com"      // <<--- REPLACE
                val senderEmail = "johny@email.com"          // <<--- REPLACE

                // Base JSON structure
                val json = JSONObject().apply {
                    put("recipient_email", recipientEmail)
                    put("sender_email", senderEmail)
                    put("notification_type", notificationType) // Use the parameter here
                    put("timestamp", System.currentTimeMillis())

                    // Merge optional details into the main JSON object
                    details?.keys()?.forEach { key ->
                        // Avoid overwriting base keys if they happen to be in details
                        if (!this.has(key)) {
                            put(key, details.get(key))
                        } else {
                            Log.w("SensorService", "Skipping detail key '$key' as it conflicts with base JSON key.")
                        }
                    }
                }

                // Special handling: Add last known BPM specifically for fall alerts
                // This is done here instead of requiring it in the 'details' for fall alerts
                // to simplify the call site for fall detection.
                if (notificationType == "falldetected" && lastKnownBpm > 0) {
                    json.put("last_known_bpm", lastKnownBpm.toInt())
                }


                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                val request = Request.Builder().url(url).post(body).build()

                Log.d("SensorService", "Sending API notification ($notificationType) to: $url with body: $json")
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.d("SensorService", "API notification ($notificationType) sent successfully. Code: ${response.code}")
                } else {
                    Log.e("SensorService", "Failed to send API notification ($notificationType): ${response.code} ${response.message}")
                    response.body?.string()?.let { Log.e("SensorService", "Response body: $it") }
                    // Consider retry logic or specific error handling based on notification type
                }
                response.close()

            } catch (e: IOException) {
                Log.e("SensorService", "Network error sending API notification ($notificationType): ${e.message}", e)
            } catch (e: Exception) {
                Log.e("SensorService", "Unexpected error sending API notification ($notificationType): ${e.message}", e)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val sensorName = sensor?.name ?: "Unknown Sensor"
        val accuracyLevel = when(accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN ($accuracy)"
        }
        Log.d("SensorService", "Accuracy changed for $sensorName: $accuracyLevel")
        // If HR accuracy becomes low/unreliable, maybe stop checking BPM thresholds?
        if (sensor?.type == Sensor.TYPE_HEART_RATE && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w("SensorService", "Heart rate sensor unreliable. Resetting BPM alert flags.")
            isBpmAlertActiveLow = false
            isBpmAlertActiveHigh = false
            lastKnownBpm = 0f // Reset last known BPM as it's unreliable
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SensorService", "onDestroy")
        sensorManager.unregisterListener(this) // Unregister all sensor listeners for this service
        serviceScope.cancel() // Cancel service coroutines
        networkScope.cancel() // Cancel network coroutines

        // Unregister the broadcast receiver
        try {
            unregisterReceiver(userConfirmationReceiver)
            Log.d("SensorService", "Unregistered userConfirmationReceiver")
        } catch (e: IllegalArgumentException) {
            // Receiver already unregistered or never registered
        }

        dismissFallAlertNotification() // Ensure alert notification is removed
        stopForeground(STOP_FOREGROUND_REMOVE) // Remove the foreground notification
        Log.d("SensorService", "Service stopped, resources released.")
    }

    // Binding is not used in this setup
    override fun onBind(intent: Intent?): IBinder? = null
}