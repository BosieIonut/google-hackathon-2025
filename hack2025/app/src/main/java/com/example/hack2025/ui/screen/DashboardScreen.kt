package com.example.hack2025.ui.screen

// Standard Android & Compose imports
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons // Import Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout // Import Logout icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign // Import TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat // For timestamp formatting
import java.util.Locale // For timestamp formatting
import java.util.TimeZone // For timestamp formatting


// Project-specific imports
import com.example.hack2025.R // For notification icon resource
// import com.example.hack2025.data.models.UserInfo // Not used directly here anymore
import com.example.hack2025.data.network.ApiClient
import kotlinx.coroutines.delay // Import delay for polling
import kotlinx.coroutines.isActive // Import isActive to check coroutine status
import kotlinx.coroutines.launch

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/screen/DashboardScreen.kt
 *
 * Implements polling for notifications and monitor data. Displays role-specific UI,
 * alerts, and common monitor data.
 */

// Notification constants (Unchanged)
const val PING_CHANNEL_ID = "ping_channel"
const val PING_CHANNEL_NAME = "Pings"
const val PING_CHANNEL_DESCRIPTION = "Notifications for pings between users"
const val PING_NOTIFICATION_ID = 1
const val CHECK_REQUEST_NOTIFICATION_ID = 2
const val NOT_WELL_NOTIFICATION_ID = 3
const val FALL_DETECTED_NOTIFICATION_ID = 4


// Tag for logging
private const val TAG = "DashboardScreen"
// Polling Intervals
private const val NOTIFICATION_POLLING_INTERVAL_MS = 3000L // Check notifications every 3 seconds
private const val MONITOR_POLLING_INTERVAL_MS = 10000L // Check monitor data every 10 seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userId: String?,
    userEmail: String?,
    userName: String?,
    authToken: String?,
    userType: String?,
    friendEmail: String?,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- State Variables ---
    // Notification/Interaction State
    var activeCheckOkSender by remember { mutableStateOf<String?>(null) }
    var lastProtegeResponse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNotWellBanner by remember { mutableStateOf(false) }
    var showFallDetectionBanner by remember { mutableStateOf(false) }
    // Monitor Data State
    var temperature by remember { mutableStateOf<Float?>(null) }
    var humidity by remember { mutableStateOf<Float?>(null) }
    var monitorTimestamp by remember { mutableStateOf<String?>(null) } // Store raw timestamp

    // --- Notification Permission Handling (Unchanged) ---
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else { true }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasNotificationPermission = isGranted
            if (!isGranted) {
                Toast.makeText(context, "Notification permission denied.", Toast.LENGTH_LONG).show()
            } else {
                createNotificationChannel(context)
                Toast.makeText(context, "Notification permission granted.", Toast.LENGTH_SHORT).show()
            }
        }
    )
    LaunchedEffect(key1 = true) {
        createNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // --- End Notification Permission Handling ---


    // --- Polling Logic: Notifications ---
    LaunchedEffect(userType, authToken) {
        if (authToken != null) {
            val bearerToken = "Bearer $authToken"
            val currentUserType = userType?.lowercase()

            if (currentUserType == "protege" || currentUserType == "guardian") {
                Log.d(TAG, "Starting notification polling for $currentUserType.")
                while (isActive) {
                    try {
                        Log.d(TAG, "Polling for notifications ($currentUserType)...")
                        val response = ApiClient.instance.checkNotifications(bearerToken)
                        // ... (Notification handling logic as before) ...
                        if (response.isSuccessful) {
                            val notification = response.body()
                            val sender = notification?.senderEmail
                            val type = notification?.type
                            if (sender != null && type != null) {
                                if (currentUserType == "protege" && type == "check_ok") {
                                    Log.i(TAG, "Protege received check_ok from $sender")
                                    if (activeCheckOkSender == null) {
                                        activeCheckOkSender = sender
                                        if (hasNotificationPermission) showSimpleNotification(context, "Check-in Request", "$sender is checking on you!", CHECK_REQUEST_NOTIFICATION_ID)
                                        else Toast.makeText(context,"Received check-in (notifications blocked).", Toast.LENGTH_LONG).show()
                                    } else Log.d(TAG, "Already handling check_ok from $activeCheckOkSender, ignoring new one.")
                                } else if (currentUserType == "guardian") {
                                    when (type) {
                                        "yes_ok", "no_ok" -> {
                                            Log.i(TAG, "Guardian received $type response from $sender")
                                            lastProtegeResponse = Pair(sender, type)
                                            val responseText = if(type == "yes_ok") "Yes, OK" else "No, Need Help"
                                            Toast.makeText(context, "Response from $sender: $responseText", Toast.LENGTH_SHORT).show()
                                        }
                                        "not_well" -> {
                                            Log.i(TAG, "Guardian received not_well alert from $sender")
                                            showNotWellBanner = true; lastProtegeResponse = null
                                            Toast.makeText(context, "$sender reported feeling unwell.", Toast.LENGTH_LONG).show()
                                            if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "$sender is not feeling well.", NOT_WELL_NOTIFICATION_ID)
                                        }
                                        "fall_detected" -> {
                                            Log.i(TAG, "Guardian received fall_detection alert from $sender")
                                            showFallDetectionBanner = true; lastProtegeResponse = null
                                            Toast.makeText(context, "$sender may have fallen!", Toast.LENGTH_LONG).show()
                                            if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "$sender might have fallen!", FALL_DETECTED_NOTIFICATION_ID)
                                        }
                                        else -> Log.d(TAG, "Guardian received notification type '$type' not handled.")
                                    }
                                } else Log.d(TAG, "Received notification type '$type' not relevant for current user type '$currentUserType'.")
                            } else if (response.code() == 200) Log.d(TAG, "No new relevant notifications found for $currentUserType.")
                        } else {
                            Log.e(TAG, "Error checking notifications ($currentUserType): ${response.code()} - ${response.message()}")
                            if (response.code() == 401) { Toast.makeText(context, "Auth error checking notifications.", Toast.LENGTH_LONG).show(); break }
                            delay(5000L) // Longer delay on error
                        }
                    } catch (e: Exception) { Log.e(TAG, "Exception during notification polling ($currentUserType)", e); delay(5000L) } // Longer delay on exception
                    delay(NOTIFICATION_POLLING_INTERVAL_MS) // Regular notification polling interval
                }
                Log.d(TAG, "Notification polling stopped for $currentUserType.")
            }
        } else { Log.w(TAG, "Cannot start notification polling, auth token is null.") }
    }
    // --- End Notification Polling Logic ---


    // --- Polling Logic: Monitor Data ---
    LaunchedEffect(authToken) { // Re-run if auth token changes
        // This polling runs for *both* user types as long as logged in
        if (authToken != null) { // Check if logged in
            Log.d(TAG, "Starting monitor data polling.")
            while(isActive) {
                try {
                    Log.d(TAG, "Polling for monitor data...")
                    // Assuming getCurrentMonitorData doesn't need auth token based on ApiService definition
                    val response = ApiClient.instance.getCurrentMonitorData()

                    if (response.isSuccessful) {
                        val data = response.body()
                        // Update state even if data is null (to clear previous values if sensor stops sending)
                        temperature = data?.temperature
                        humidity = data?.humidity
                        monitorTimestamp = data?.timestamp // Store raw timestamp
                        Log.d(TAG, "Monitor data received: Temp=${temperature}, Humid=${humidity}, TS=${monitorTimestamp}")
                    } else {
                        // Handle API errors (non-2xx status codes)
                        Log.e(TAG, "Error polling monitor data: ${response.code()} - ${response.message()}")
                        // Consider if specific error codes need handling (e.g., 404 if endpoint removed)
                        delay(MONITOR_POLLING_INTERVAL_MS * 2) // Wait longer after an error
                    }
                } catch (e: Exception) {
                    // Handle network exceptions or other errors
                    Log.e(TAG, "Exception during monitor data polling", e)
                    delay(MONITOR_POLLING_INTERVAL_MS * 2) // Wait longer after an exception
                }
                // Wait for the defined monitor polling interval before the next attempt
                delay(MONITOR_POLLING_INTERVAL_MS)
            }
            Log.d(TAG, "Monitor data polling stopped.")
        } else {
            Log.w(TAG, "Cannot start monitor data polling, auth token is null.")
            // Optional: Clear monitor data when logged out
            temperature = null
            humidity = null
            monitorTimestamp = null
        }
    }
    // --- End Monitor Data Polling Logic ---


    // --- UI Layout using Scaffold ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HaloTrack") },
                actions = { IconButton(onClick = onLogout) { Icon(Icons.Filled.Logout, "Logout") } }
            )
        }
    ) { innerPadding ->
        // Use Column for overall layout, enabling scrolling if content overflows
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Add verticalScroll if content might exceed screen height
                // .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp), // Outer padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Welcome Message
            Text(
                text = "Hello, ${userName ?: "User"}!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // User Type Display
            if (userType != null) {
                Text(
                    text = "You are a: $userType",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp)) // Space before role-specific content

                // Role-Specific Content (Guardian/Protege Cards and Alerts)
                when (userType.lowercase()) {
                    "guardian" -> {
                        GuardianContent(
                            userEmail = userEmail,
                            friendEmail = friendEmail,
                            lastProtegeResponse = lastProtegeResponse,
                            showNotWellBanner = showNotWellBanner,
                            showFallDetectionBanner = showFallDetectionBanner,
                            hasNotificationPermission = hasNotificationPermission,
                            onDismissNotWell = { showNotWellBanner = false }, // Pass dismiss actions
                            onDismissFall = { showFallDetectionBanner = false }, // Pass dismiss actions
                            onSendCheckIn = { // Pass check-in action
                                // Clear status immediately for responsiveness
                                lastProtegeResponse = null
                                showNotWellBanner = false
                                showFallDetectionBanner = false
                                // Launch coroutine to send request
                                if (userEmail != null && friendEmail != null) {
                                    scope.launch {
                                        val notificationData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "check_ok")
                                        try {
                                            val response = ApiClient.instance.sendNotification(notificationData)
                                            if (response.isSuccessful) Toast.makeText(context, "Check-in request sent!", Toast.LENGTH_SHORT).show()
                                            else Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                                    }
                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() }
                            }
                        )
                    }
                    "protege" -> {
                        ProtegeContent(
                            userEmail = userEmail,
                            friendEmail = friendEmail,
                            activeCheckOkSender = activeCheckOkSender,
                            onSendResponse = { responseType -> // Pass response action
                                if (userEmail != null && friendEmail != null) {
                                    scope.launch {
                                        val responseData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to responseType)
                                        try {
                                            val response = ApiClient.instance.sendNotification(responseData)
                                            val toastMsg = if (responseType == "yes_ok") "Responded: Yes, OK" else "Responded: No, Need Help"
                                            if (response.isSuccessful) { Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show(); activeCheckOkSender = null }
                                            else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() }
                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                                    }
                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() }
                            }
                        )
                    }
                } // End when(userType)

                // Spacer before the common Monitor Card
                Spacer(modifier = Modifier.height(24.dp))

            } else {
                // Fallback if userType is null
                Text("User role information not available.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                Spacer(modifier = Modifier.height(24.dp)) // Space before monitor card even if role is unknown
            }

            // --- Common Content: Monitor Card ---
            MonitorCard(
                temperature = temperature,
                humidity = humidity,
                timestamp = monitorTimestamp // Pass raw timestamp
            )

            // Add extra space at the bottom if needed, especially if using verticalScroll
            // Spacer(modifier = Modifier.height(16.dp))

        } // End top content Column
    } // End Scaffold
}

// --- Extracted Composable for Guardian Content ---
@Composable
fun GuardianContent(
    userEmail: String?, // Needed for sending check-in
    friendEmail: String?,
    lastProtegeResponse: Pair<String, String>?,
    showNotWellBanner: Boolean,
    showFallDetectionBanner: Boolean,
    hasNotificationPermission: Boolean, // Needed if sending notification on check-in (removed)
    onDismissNotWell: () -> Unit,
    onDismissFall: () -> Unit,
    onSendCheckIn: () -> Unit // Callback for sending check-in
) {
    // Card 1: Protege Info & Action Button
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (friendEmail != null) {
                Text("Your protege:", style = MaterialTheme.typography.labelLarge)
                Text(friendEmail, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))

                val (responseText, responseColor) = lastProtegeResponse?.let { (_, respType) ->
                    if (respType == "yes_ok") "Responded: Yes, OK" to MaterialTheme.colorScheme.primary
                    else "Responded: No, Need Help!" to MaterialTheme.colorScheme.error
                } ?: ("" to LocalContentColor.current)

                if (responseText.isNotEmpty()) {
                    Text( responseText, style = MaterialTheme.typography.bodyMedium, color = responseColor,
                        fontWeight = if (lastProtegeResponse?.second == "no_ok") FontWeight.Bold else FontWeight.Normal )
                    Spacer(modifier = Modifier.height(16.dp))
                } else { Spacer(modifier = Modifier.height(16.dp)) }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSendCheckIn // Use the callback
                ) { Text("Check on Protege") }
            } else { Text("Protege information not available.", style = MaterialTheme.typography.bodyMedium) }
        }
    }

    // Spacer before alerts
    Spacer(modifier = Modifier.height(16.dp))

    // Alert Card: "Not Well"
    if (showNotWellBanner) {
        AlertCard(
            message = "Your protege is not feeling well",
            onDismiss = onDismissNotWell // Use callback
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Alert Card: "Fall Detection"
    if (showFallDetectionBanner) {
        AlertCard(
            message = "Your protege might have fallen",
            isImportant = true, // Make fall detection bold
            onDismiss = onDismissFall // Use callback
        )
        // No spacer needed after the last element in this section usually
    }
}

// --- Extracted Composable for Protege Content ---
@Composable
fun ProtegeContent(
    userEmail: String?, // Needed for sending response
    friendEmail: String?,
    activeCheckOkSender: String?,
    onSendResponse: (String) -> Unit // Callback accepting "yes_ok" or "no_ok"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (friendEmail != null) {
                Text("Your guardian:", style = MaterialTheme.typography.labelLarge)
                Text(friendEmail, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))

                if (activeCheckOkSender != null) {
                    Text( "$activeCheckOkSender is checking on you!",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp) )
                    Row( modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally) ) {
                        Button( // Yes Button
                            modifier = Modifier.weight(1f),
                            onClick = { onSendResponse("yes_ok") } // Use callback
                        ) { Text("Yes, I'm OK") }
                        Button( // No Button
                            modifier = Modifier.weight(1f),
                            onClick = { onSendResponse("no_ok") }, // Use callback
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("No, Need Help") }
                    }
                } else {
                    // No waiting text
                }
            } else {
                Text("Guardian info unavailable.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// --- Extracted Composable for Dismissible Alert Cards ---
@Composable
fun AlertCard(
    message: String,
    isImportant: Boolean = false, // Flag to make text bold
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = if (isImportant) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) { // Use the passed dismiss lambda
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss alert: $message", // More specific description
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}


// --- Extracted Composable for Monitor Data Card ---
@Composable
fun MonitorCard(
    temperature: Float?,
    humidity: Float?,
    timestamp: String? // Receive raw timestamp
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Monitor",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween // Space out temp and humidity
            ) {
                Text(
                    text = "Temperature: ${temperature?.toString() ?: "--"} °C",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Humidity: ${humidity?.toString() ?: "--"} %",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            // Optionally display formatted timestamp
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // Attempt to format, fallback to raw string if error
                    text = "Last update: ${formatISOTimestamp(timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- Helper Function to Format Timestamp ---
fun formatISOTimestamp(isoTimestamp: String?): String {
    if (isoTimestamp == null) return "N/A"
    return try {
        // Input format from Python's datetime.isoformat() might include microseconds
        // Adjust pattern based on actual server output
        val inputPatterns = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.getDefault()), // With microseconds
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())       // Without microseconds
        )
        // Set timezone to UTC as ISO format implies it unless offset is specified
        inputPatterns.forEach { it.timeZone = TimeZone.getTimeZone("UTC") }

        val outputFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        // Display in local timezone
        outputFormat.timeZone = TimeZone.getDefault()

        var parsedDate: java.util.Date? = null
        for (pattern in inputPatterns) {
            try {
                parsedDate = pattern.parse(isoTimestamp)
                if (parsedDate != null) break // Stop if parsing succeeds
            } catch (e: java.text.ParseException) {
                // Try next pattern
            }
        }

        parsedDate?.let { outputFormat.format(it) } ?: isoTimestamp // Fallback to raw string if all parsing fails
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse timestamp '$isoTimestamp'", e)
        isoTimestamp // Fallback to raw string on any other error
    }
}


// --- Notification Helper Functions (Unchanged) ---
fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            PING_CHANNEL_ID, PING_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        ).apply { description = PING_CHANNEL_DESCRIPTION }
        val notificationManager: NotificationManager? =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel '$PING_CHANNEL_ID' created or ensured.")
    }
}

fun showSimpleNotification(context: Context, title: String, message: String, notificationId: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot show notification: POST_NOTIFICATIONS permission not granted.")
            return
        }
    }
    val notificationIcon = R.drawable.ic_notification_default // <-- !!! UPDATE THIS !!!
    val builder = NotificationCompat.Builder(context, PING_CHANNEL_ID)
        .setSmallIcon(notificationIcon)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority( /* ... Priority logic ... */
            if (notificationId == CHECK_REQUEST_NOTIFICATION_ID || notificationId == NOT_WELL_NOTIFICATION_ID || notificationId == FALL_DETECTED_NOTIFICATION_ID)
                NotificationCompat.PRIORITY_HIGH
            else
                NotificationCompat.PRIORITY_DEFAULT
        )
        .setAutoCancel(true)
    try {
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        Log.d(TAG, "Notification shown with ID: $notificationId")
    } catch (e: Exception) {
        Log.e(TAG, "Error showing notification", e)
    }
}