package com.example.old_pers.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.* // Use specific imports if preferred
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.old_pers.R // Make sure this points to your R file
import com.example.old_pers.presentation.service.SensorService // Import service for starting it
// Import constants defined in SensorService
import com.example.old_pers.presentation.service.POTENTIAL_FALL_DETECTED_ACTION
import com.example.old_pers.presentation.service.USER_CONFIRMED_OK_ACTION
import com.example.old_pers.presentation.service.FALL_ALERT_SENT_ACTION
import com.example.old_pers.presentation.service.REQUEST_SERVICE_STATUS_ACTION // Import the new action constant
import com.example.old_pers.presentation.theme.Old_persTheme // Your Compose theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException

class MainActivity : ComponentActivity() {

    // State derived from service events, controlling the visibility of the "I'm OK" button
    private val showImOkButton = mutableStateOf(false)

    // BroadcastReceiver to listen for events from SensorService
    private val serviceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Optional: Check package for security
            if (intent?.`package` != null && intent.`package` != context?.packageName) {
                Log.w("MainActivity", "Received broadcast from unexpected package: ${intent.`package`}")
                return
            }

            Log.d("MainActivity", "Received broadcast: ${intent?.action}")
            when (intent?.action) {
                POTENTIAL_FALL_DETECTED_ACTION -> {
                    // Service detected a potential fall OR confirmed status is 'fall detected'
                    showImOkButton.value = true
                    Log.d("MainActivity", "Potential fall detected or confirmed via broadcast, showing 'I'm OK' button.")
                }
                USER_CONFIRMED_OK_ACTION, // Service indicates user confirmed OK OR status check confirmed no fall active
                FALL_ALERT_SENT_ACTION -> { // Service indicates alert was sent (timeout)
                    // In all these cases, hide the button.
                    showImOkButton.value = false
                    Log.d("MainActivity", "Hiding 'I'm OK' button (User OK / Alert Sent / Status Not Active).")
                }
            }
        }
    }

    // Activity Result Launcher for BODY_SENSORS permission
    private val requestBodySensorPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "BODY_SENSORS permission granted by user.")
                startSensorServiceIfNotRunning() // Start service now that permission is granted
            } else {
                Log.w("MainActivity", "BODY_SENSORS permission denied by user.")
                // TODO: Show a persistent message/dialog explaining why the permission is crucial
                // and possibly guide the user to settings. The app cannot function without it.
                // For now, just log. You might want to disable parts of the UI.
            }
        }

    // Activity Result Launcher for POST_NOTIFICATIONS permission (Android 13+)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission granted (Android 13+).")
            } else {
                Log.w("MainActivity", "POST_NOTIFICATIONS permission denied (Android 13+).")
                // TODO: Inform the user that fall alert notifications might not appear.
                // The foreground service notification might still work depending on OS specifics.
            }
        }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")

        setContent {
            val serverStatus = remember { mutableStateOf("Checking server...") }
            // TODO: Replace with actual user login/retrieval logic
            val userEmail = remember { "User@example.com" }

            Old_persTheme {
                WearApp(
                    greetingName = userEmail.substringBefore('@'),
                    onCalibrateClick = { calibrateSensors() },
                    serverStatus = serverStatus,
                    showImOkButton = showImOkButton.value, // Pass state down
                    onImOkClick = { confirmUserOk()},
                    onNotOkClick = { imnotWELL() }     // Pass click handler down

                )
            }

            // Effect to check server status on launch
            LaunchedEffect(key1 = Unit) {
                checkServerStatus(serverStatus)
            }
        }

        // Request necessary permissions on create
        requestAppPermissions()

        // Register the broadcast receiver to listen for events from the service
        val intentFilter = IntentFilter().apply {
            addAction(POTENTIAL_FALL_DETECTED_ACTION)
            addAction(USER_CONFIRMED_OK_ACTION)
            addAction(FALL_ALERT_SENT_ACTION)
        }
        // Register receiver based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceEventReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            Log.d("MainActivity", "Registered serviceEventReceiver (API 33+)")
        } else {
            registerReceiver(serviceEventReceiver, intentFilter)
            Log.d("MainActivity", "Registered serviceEventReceiver")
        }
    }

    // Function to check and request BODY_SENSORS and POST_NOTIFICATIONS permissions
    private fun requestAppPermissions() {
        // 1. Check/Request BODY_SENSORS
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "BODY_SENSORS permission already granted.")
                startSensorServiceIfNotRunning() // Ensure service starts if permission is present
            }
            shouldShowRequestPermissionRationale(Manifest.permission.BODY_SENSORS) -> {
                Log.i("MainActivity", "Showing rationale for BODY_SENSORS permission.")
                // TODO: Implement a dialog explaining the need for BODY_SENSORS.
                // After showing rationale, request again:
                requestBodySensorPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
            }
            else -> {
                Log.d("MainActivity", "Requesting BODY_SENSORS permission.")
                requestBodySensorPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
            }
        }

        // 2. Check/Request POST_NOTIFICATIONS (Android 13+ / API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.i("MainActivity", "Showing rationale for POST_NOTIFICATIONS permission.")
                    // TODO: Implement a dialog explaining the need for Notifications (fall alerts).
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }


    // Sends an intent to the service to trigger calibration
    private fun calibrateSensors() {
        Log.d("MainActivity", "Sending CALIBRATE intent to SensorService.")
        val intent = Intent(this, SensorService::class.java).apply {
            action = "CALIBRATE" // Use the action defined in SensorService
        }
        // Use startForegroundService for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun imnotWELL() {
        Log.d("MainActivity", "Sending NOT_WELL intent to SensorService.")
        val intent = Intent(this, SensorService::class.java).apply {
            action = "NOT_WELL"
        }
        // Use startForegroundService for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Called when the user clicks the "I'm OK" button
    private fun confirmUserOk() {
        Log.d("MainActivity", "User clicked 'I'm OK'. Sending confirmation broadcast to service.")
        showImOkButton.value = false // Immediately hide button for responsiveness
        // Send a broadcast that the service is listening for
        val intent = Intent(USER_CONFIRMED_OK_ACTION)
        intent.setPackage(packageName) // IMPORTANT: Restrict broadcast to this app
        sendBroadcast(intent)
    }


    // Starts the SensorService if it's not already running
    private fun startSensorServiceIfNotRunning() {
        // Check if BODY_SENSORS permission is granted before starting
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("MainActivity", "Cannot start SensorService: BODY_SENSORS permission missing.")
            // Optionally prompt the user again or show an error message.
            return
        }

        Log.d("MainActivity", "Ensuring SensorService is started.")
        val intent = Intent(this, SensorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // --- Server Status Check ---
    private suspend fun checkServerStatus(serverStatus: MutableState<String>) {
        withContext(Dispatchers.IO) {
            var statusMessage = "Server: Unknown"
            try {
                // !!! IMPORTANT: Replace hardcoded IP/URL with configuration or constant !!!
                val url = URL("http://10.41.61.38:2242/") // Example endpoint
                Log.d("MainActivity", "Checking server status at: $url")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 // 5 seconds
                connection.readTimeout = 5000    // 5 seconds

                val responseCode = connection.responseCode
                Log.d("MainActivity", "Server check response code: $responseCode")
                statusMessage = if (responseCode in 200..299) {
                    "Server: OK"
                } else {
                    "Server Error: $responseCode"
                }
                connection.disconnect()
            } catch (e: SocketTimeoutException) {
                Log.e("MainActivity", "Server check timed out: ${e.message}")
                statusMessage = "Server: Timeout"
            } catch (e: ConnectException) {
                Log.e("MainActivity", "Server check connection failed: ${e.message}")
                statusMessage = "Server: Unreachable"
            } catch (e: Exception) {
                Log.e("MainActivity", "Server check failed: ${e.message}", e)
                statusMessage = "Server: Error"
            } finally {
                withContext(Dispatchers.Main) {
                    serverStatus.value = statusMessage
                }
            }
        }
    }
    // --- End Server Status Check ---

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        // --- NEW: Request the current status from the service ---
        // This ensures the UI (like the "I'm OK" button) reflects the correct state
        // if a fall happened while the app was in the background.
        requestServiceStatus()
        // ------------------------------------------------------
    }

    // --- NEW Function in MainActivity ---
    /**
     * Sends an Intent to the SensorService asking it to broadcast its current
     * fall detection status back to this Activity.
     */
    private fun requestServiceStatus() {
        // Check if service should be running (permission granted)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Requesting status from SensorService via startService.")
            val intent = Intent(this, SensorService::class.java).apply {
                // Set the action that SensorService.onStartCommand will receive
                action = REQUEST_SERVICE_STATUS_ACTION // Use constant defined in service scope
            }
            // Starting the service when it's already running just delivers the intent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Log.w("MainActivity", "Cannot request status, BODY_SENSORS permission needed.")
        }
    }
    // --- End New Function ---


    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
        // Unregister the broadcast receiver
        try {
            unregisterReceiver(serviceEventReceiver)
            Log.d("MainActivity", "Unregistered serviceEventReceiver.")
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Service event receiver was not registered or already unregistered.")
        }
        // Note: The service continues running unless explicitly stopped or killed by system.
    }
}

// --- Composable UI ---

@Composable
fun WearApp(
    greetingName: String,
    onCalibrateClick: () -> Unit,
    serverStatus: MutableState<String>,
    showImOkButton: Boolean,      // Receive button visibility state
    onImOkClick: () -> Unit,         // Receive click handler for "I'm OK" button
    onNotOkClick: () -> Unit
) {
    Old_persTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText() // Standard Wear OS time display
            MainApp(
                greetingName = greetingName,
                onCalibrateClick = onCalibrateClick,
                serverStatus = serverStatus,
                showImOkButton = showImOkButton, // Pass state down
                onImOkClick = onImOkClick,      // Pass handler down
                onNotOkClick = onNotOkClick
            )
        }
    }
}

@Composable
fun MainApp(
    greetingName: String,
    onCalibrateClick: () -> Unit,
    serverStatus: MutableState<String>,
    showImOkButton: Boolean,   // Receive state from WearApp
    onImOkClick: () -> Unit,      // Receive handler from WearApp
    onNotOkClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp), // Increased padding slightly
        horizontalAlignment = Alignment.CenterHorizontally,
        // Adjust arrangement based on whether the "I'm OK" button is shown
        verticalArrangement = if (showImOkButton) Arrangement.Center else Arrangement.SpaceAround
    ) {
        // Conditional UI: Show normal screen OR fall alert screen
        if (!showImOkButton) {
            // --- Normal State UI ---
            Spacer(Modifier.weight(0.5f)) // Push content down slightly

            Text(
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                text = "Hello, Johny!",
                style = MaterialTheme.typography.title3
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Server Status Text with Color Coding
            val serverColor = when {
                serverStatus.value.contains("OK") -> Color(0xFF4CAF50) // Green
                serverStatus.value.contains("Error") ||
                        serverStatus.value.contains("Unreachable") -> Color(0xFFF44336) // Red
                serverStatus.value.contains("Timeout") -> Color(0xFFFFC107) // Amber/Yellow
                else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f) // Default greyish
            }
            Text(
                textAlign = TextAlign.Center,
                color = serverColor,
                text = serverStatus.value,
                style = MaterialTheme.typography.caption1
            )

            Spacer(Modifier.weight(1f)) // Add more space before button

            // Calibration Button Logic
            var buttonText by remember { mutableStateOf("Calibrate") }
            var buttonText2 by remember { mutableStateOf("HELP!") }
            var isCalibrating by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            Button(
                onClick = {
                        buttonText2 = "Sending"
                        onNotOkClick() // Trigger the calibration
                        coroutineScope.launch {
                            // Simple visual feedback delay - actual calibration is service-driven
                            delay(5000)
                            buttonText2 = "Sent"
                            delay(2000)
                            buttonText2 = "HELP!"
                        }
                },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    disabledBackgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.5f)
                )
            ) {
                Text(buttonText2)
            }
            Spacer(Modifier.height(32.dp)) // Space at bottom
            Button(
                onClick = {
                    if (!isCalibrating) {
                        isCalibrating = true
                        buttonText = "Calibrating..."
                        onCalibrateClick() // Trigger the calibration
                        coroutineScope.launch {
                            // Simple visual feedback delay - actual calibration is service-driven
                            delay(5000)
                            buttonText = "Calibrate"
                            isCalibrating = false
                        }
                    }
                },
                enabled = !isCalibrating,
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(32.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    disabledBackgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.5f)
                )
            ) {
                Text(buttonText)
            }
            Spacer(Modifier.height(8.dp)) // Space at bottom

        } else {
            // --- Potential Fall Detected State UI ---
            Spacer(Modifier.weight(1f)) // Center vertically

            // Alert Text
            Text(
                text = "Are you OK?",
                style = MaterialTheme.typography.title1, // Larger text
                color = Color.Yellow, // High visibility color
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp) // Ensure text wraps if needed
            )

            Spacer(modifier = Modifier.height(16.dp)) // Space between text and button

            // "I'm OK" Button
            Button(
                onClick = onImOkClick, // Call the handler from Activity
                modifier = Modifier
                    .fillMaxWidth(0.8f) // Large button
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50)) // Green
            ) {
                Text("I'M OK", color = Color.White, style = MaterialTheme.typography.button)
            }
            Spacer(Modifier.weight(1f)) // Balance spacing
        }
    }
}


// --- Previews ---

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Default State")
@Composable
fun DefaultPreview() {
    val previewServerStatus = remember { mutableStateOf("Server: OK") }
    Old_persTheme {
        WearApp(
            greetingName = "Preview",
            onCalibrateClick = {},
            serverStatus = previewServerStatus,
            showImOkButton = false, // Normal state preview
            onImOkClick = {},
            onNotOkClick = {}
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Fall Detected State")
@Composable
fun FallDetectedPreview() {
    val previewServerStatus = remember { mutableStateOf("Server: OK") }
    Old_persTheme {
        WearApp(
            greetingName = "Preview",
            onCalibrateClick = {},
            serverStatus = previewServerStatus,
            showImOkButton = true, // Fall detected state preview
            onImOkClick = {},
            onNotOkClick = {}
        )
    }
}
// --- End Preview ---