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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Project-specific imports
import com.example.hack2025.R
// import com.example.hack2025.data.models.UserInfo
import com.example.hack2025.data.network.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/screen/DashboardScreen.kt
 *
 * Implements polling for notifications and monitor data. Displays role-specific UI,
 * alerts (including fall_detected), and common monitor data with last known values on fetch failure.
 * App Bar title is "HaloTrack".
 */

// Notification constants
const val PING_CHANNEL_ID = "ping_channel"
const val PING_CHANNEL_NAME = "Pings"
const val PING_CHANNEL_DESCRIPTION = "Notifications for pings between users"
// const val PING_NOTIFICATION_ID = 1 // Unused ID
const val CHECK_REQUEST_NOTIFICATION_ID = 2 // For protege receiving check_ok
const val NOT_WELL_NOTIFICATION_ID = 3      // For guardian receiving not_well alert
const val FALL_DETECTED_NOTIFICATION_ID = 4 // For guardian receiving fall_detected alert


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
    var showFallDetectedBanner by remember { mutableStateOf(false) } // Renamed state
    // Monitor Data State
    var temperature by remember { mutableStateOf<Float?>(null) }
    var humidity by remember { mutableStateOf<Float?>(null) }
    var monitorTimestamp by remember { mutableStateOf<String?>(null) }
    var monitorDataFetchFailed by remember { mutableStateOf(false) } // Track fetch status


    // --- Notification Permission Handling (Unchanged) ---
    var hasNotificationPermission by remember {
        mutableStateOf( if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else { true } ) }
    val permissionLauncher = rememberLauncherForActivityResult( contract = ActivityResultContracts.RequestPermission(), onResult = { isGranted ->
        hasNotificationPermission = isGranted
        if (!isGranted) Toast.makeText(context, "Notification permission denied.", Toast.LENGTH_LONG).show()
        else { createNotificationChannel(context); Toast.makeText(context, "Notification permission granted.", Toast.LENGTH_SHORT).show() } } )
    LaunchedEffect(key1 = true) { createNotificationChannel(context); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
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
                        if (response.isSuccessful) {
                            val notification = response.body(); val sender = notification?.senderEmail; val type = notification?.type
                            if (sender != null && type != null) {
                                if (currentUserType == "protege" && type == "check_ok") { /* ... Protege check_ok logic ... */
                                    Log.i(TAG, "Protege received check_ok from $sender")
                                    if (activeCheckOkSender == null) { activeCheckOkSender = sender
                                        if (hasNotificationPermission) showSimpleNotification(context, "Check-in Request", "$sender is checking on you!", CHECK_REQUEST_NOTIFICATION_ID)
                                        else Toast.makeText(context,"Received check-in (notifications blocked).", Toast.LENGTH_LONG).show()
                                    } else Log.d(TAG, "Already handling check_ok from $activeCheckOkSender, ignoring new one.")
                                } else if (currentUserType == "guardian") {
                                    when (type) {
                                        "yes_ok", "no_ok" -> { /* ... Guardian yes/no response logic ... */
                                            Log.i(TAG, "Guardian received $type response from $sender")
                                            lastProtegeResponse = Pair(sender, type)
                                            val responseText = if(type == "yes_ok") "Yes, OK" else "No, Need Help"
                                            Toast.makeText(context, "Response from $sender: $responseText", Toast.LENGTH_SHORT).show()
                                        }
                                        "not_well" -> { /* ... Guardian not_well logic ... */
                                            Log.i(TAG, "Guardian received not_well alert from $sender")
                                            showNotWellBanner = true; lastProtegeResponse = null
                                            Toast.makeText(context, "$sender reported feeling unwell.", Toast.LENGTH_LONG).show()
                                            if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "$sender is not feeling well.", NOT_WELL_NOTIFICATION_ID)
                                        }
                                        "fall_detected" -> { // Changed type check
                                            Log.i(TAG, "Guardian received fall_detected alert from $sender")
                                            showFallDetectedBanner = true; lastProtegeResponse = null // Use renamed state
                                            Toast.makeText(context, "Fall detected for $sender!", Toast.LENGTH_LONG).show() // Updated Toast text
                                            if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "Fall Detected for $sender!", FALL_DETECTED_NOTIFICATION_ID) // Updated notification text
                                        }
                                        else -> Log.d(TAG, "Guardian received notification type '$type' not handled.")
                                    }
                                } else Log.d(TAG, "Received notification type '$type' not relevant for current user type '$currentUserType'.")
                            } else if (response.code() == 200) Log.d(TAG, "No new relevant notifications found for $currentUserType.")
                        } else { /* ... Error handling ... */
                            Log.e(TAG, "Error checking notifications ($currentUserType): ${response.code()} - ${response.message()}")
                            if (response.code() == 401) { Toast.makeText(context, "Auth error checking notifications.", Toast.LENGTH_LONG).show(); break }
                            delay(5000L)
                        }
                    } catch (e: Exception) { /* ... Exception handling ... */ Log.e(TAG, "Exception during notification polling ($currentUserType)", e); delay(5000L) }
                    delay(NOTIFICATION_POLLING_INTERVAL_MS)
                }
                Log.d(TAG, "Notification polling stopped for $currentUserType.")
            }
        } else { Log.w(TAG, "Cannot start notification polling, auth token is null.") }
    }
    // --- End Notification Polling Logic ---


    // --- Polling Logic: Monitor Data ---
    LaunchedEffect(authToken) {
        if (authToken != null) {
            Log.d(TAG, "Starting monitor data polling.")
            while(isActive) {
                var fetchSuccess = false // Track success for this attempt
                try {
                    Log.d(TAG, "Polling for monitor data...")
                    val response = ApiClient.instance.getCurrentMonitorData()

                    if (response.isSuccessful) {
                        fetchSuccess = true
                        val data = response.body()
                        // State variables are ONLY updated here on success
                        temperature = data?.temperature
                        humidity = data?.humidity
                        monitorTimestamp = data?.timestamp
                        monitorDataFetchFailed = false // Reset flag on success
                        Log.d(TAG, "Monitor data updated: Temp=${temperature}, Humid=${humidity}, TS=${monitorTimestamp}")
                    } else {
                        // State variables are NOT updated here on API error
                        Log.e(TAG, "Error polling monitor data: ${response.code()} - ${response.message()}")
                        monitorDataFetchFailed = true // Set flag on API error
                        delay(MONITOR_POLLING_INTERVAL_MS * 2)
                    }
                } catch (e: Exception) {
                    // State variables are NOT updated here on exception
                    Log.e(TAG, "Exception during monitor data polling", e)
                    monitorDataFetchFailed = true // Set flag on exception
                    delay(MONITOR_POLLING_INTERVAL_MS * 2)
                }
                // If fetch failed, keep showing old data, but the flag is set.
                delay(MONITOR_POLLING_INTERVAL_MS)
            }
            Log.d(TAG, "Monitor data polling stopped.")
        } else {
            Log.w(TAG, "Cannot start monitor data polling, auth token is null.")
            temperature = null; humidity = null; monitorTimestamp = null; monitorDataFetchFailed = false // Clear data on logout
        }
    }
    // --- End Monitor Data Polling Logic ---


    // --- UI Layout using Scaffold ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HaloTrack") }, // Changed title
                actions = { IconButton(onClick = onLogout) { Icon(Icons.Filled.Logout, "Logout") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text( text = "Hello, ${userName ?: "User"}!", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp) )

            if (userType != null) {
                Text( text = "You are a: $userType", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant )
                Spacer(modifier = Modifier.height(24.dp))

                // Role-Specific Content
                when (userType.lowercase()) {
                    "guardian" -> {
                        GuardianContent(
                            userEmail = userEmail,
                            friendEmail = friendEmail,
                            lastProtegeResponse = lastProtegeResponse,
                            showNotWellBanner = showNotWellBanner,
                            showFallDetectedBanner = showFallDetectedBanner, // Pass renamed state
                            hasNotificationPermission = hasNotificationPermission,
                            onDismissNotWell = { showNotWellBanner = false },
                            onDismissFall = { showFallDetectedBanner = false }, // Use renamed state
                            onSendCheckIn = { // Check-in logic
                                lastProtegeResponse = null; showNotWellBanner = false; showFallDetectedBanner = false
                                if (userEmail != null && friendEmail != null) {
                                    scope.launch { /* ... Send check_ok notification ... */
                                        val notificationData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "check_ok")
                                        try { val response = ApiClient.instance.sendNotification(notificationData)
                                            if (response.isSuccessful) Toast.makeText(context, "Check-in request sent!", Toast.LENGTH_SHORT).show()
                                            else Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() } }
                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() } }
                        )
                    }
                    "protege" -> {
                        ProtegeContent( userEmail = userEmail, friendEmail = friendEmail, activeCheckOkSender = activeCheckOkSender,
                            onSendResponse = { responseType -> /* ... Send yes/no response logic ... */
                                if (userEmail != null && friendEmail != null) {
                                    scope.launch { val responseData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to responseType)
                                        try { val response = ApiClient.instance.sendNotification(responseData)
                                            val toastMsg = if (responseType == "yes_ok") "Responded: Yes, OK" else "Responded: No, Need Help"
                                            if (response.isSuccessful) { Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show(); activeCheckOkSender = null }
                                            else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() }
                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() } }
                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() } }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                Text("User role information not available.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Common Content: Monitor Card
            MonitorCard(
                temperature = temperature, // Reads current state (last good value)
                humidity = humidity,       // Reads current state (last good value)
                timestamp = monitorTimestamp, // Reads current state (last good value)
                fetchFailed = monitorDataFetchFailed // Pass fetch status for visual cue
            )
        } // End top content Column
    } // End Scaffold
}

// --- Extracted Composable for Guardian Content ---
@Composable
fun GuardianContent(
    userEmail: String?, friendEmail: String?, lastProtegeResponse: Pair<String, String>?,
    showNotWellBanner: Boolean, showFallDetectedBanner: Boolean, // Use renamed prop
    hasNotificationPermission: Boolean,
    onDismissNotWell: () -> Unit, onDismissFall: () -> Unit, onSendCheckIn: () -> Unit
) {
    // Card 1: Protege Info & Action Button
    Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (friendEmail != null) {
                Text("Your protege:", style = MaterialTheme.typography.labelLarge); Text(friendEmail, style = MaterialTheme.typography.bodyLarge); Spacer(modifier = Modifier.height(16.dp))
                val (responseText, responseColor) = lastProtegeResponse?.let { (_, respType) -> if (respType == "yes_ok") "Responded: Yes, OK" to MaterialTheme.colorScheme.primary else "Responded: No, Need Help!" to MaterialTheme.colorScheme.error } ?: ("" to LocalContentColor.current)
                if (responseText.isNotEmpty()) { Text( responseText, style = MaterialTheme.typography.bodyMedium, color = responseColor, fontWeight = if (lastProtegeResponse?.second == "no_ok") FontWeight.Bold else FontWeight.Normal ); Spacer(modifier = Modifier.height(16.dp))
                } else { Spacer(modifier = Modifier.height(16.dp)) }
                Button( modifier = Modifier.fillMaxWidth(), onClick = onSendCheckIn ) { Text("Check on Protege") }
            } else { Text("Protege information not available.", style = MaterialTheme.typography.bodyMedium) } }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // Alert Card: "Not Well"
    if (showNotWellBanner) {
        AlertCard( message = "Your protege is not feeling well", onDismiss = onDismissNotWell ); Spacer(modifier = Modifier.height(16.dp))
    }
    // Alert Card: "Fall Detected"
    if (showFallDetectedBanner) { // Use renamed prop
        AlertCard(
            message = "Fall Detected for Protege", // Updated text
            isImportant = true,
            onDismiss = onDismissFall
        )
    }
}

// --- Extracted Composable for Protege Content ---
@Composable
fun ProtegeContent( userEmail: String?, friendEmail: String?, activeCheckOkSender: String?, onSendResponse: (String) -> Unit ) {
    Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) ) {
        Column( modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally ) {
            if (friendEmail != null) {
                Text("Your guardian:", style = MaterialTheme.typography.labelLarge); Text(friendEmail, style = MaterialTheme.typography.bodyLarge); Spacer(modifier = Modifier.height(16.dp))
                if (activeCheckOkSender != null) {
                    Text( "$activeCheckOkSender is checking on you!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp) )
                    Row( modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally) ) {
                        Button( modifier = Modifier.weight(1f), onClick = { onSendResponse("yes_ok") } ) { Text("Yes, I'm OK") }
                        Button( modifier = Modifier.weight(1f), onClick = { onSendResponse("no_ok") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) ) { Text("No, Need Help") }
                    }
                }
            } else { Text("Guardian info unavailable.", style = MaterialTheme.typography.bodyMedium) } } }
}

// --- Extracted Composable for Dismissible Alert Cards ---
@Composable
fun AlertCard( message: String, isImportant: Boolean = false, onDismiss: () -> Unit ) {
    Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) ) {
        Row( modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically ) {
            Text( text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = if (isImportant) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Start, modifier = Modifier.weight(1f) )
            IconButton(onClick = onDismiss) {
                Icon( imageVector = Icons.Filled.Close,
                    contentDescription = if(message.startsWith("Fall Detected")) "Dismiss fall detected alert" else "Dismiss alert: $message", // Updated description
                    tint = MaterialTheme.colorScheme.onErrorContainer )
            }
        }
    }
}


// --- Extracted Composable for Monitor Data Card ---
@Composable
fun MonitorCard(
    temperature: Float?,
    humidity: Float?,
    timestamp: String?,
    fetchFailed: Boolean // Use this to show indicator
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally // Center column content
        ) {
            Text( text = "Monitor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp) )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly // Centered distribution
            ) {
                // Display last known good value, or "--" if never fetched
                Text( text = "Temperature:\n${temperature?.toString() ?: "--"} °C", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center )
                // Display last known good value, or "--" if never fetched
                Text( text = "Humidity:\n${humidity?.toString() ?: "--"} %", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center )
            }
            // Display last known good timestamp
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text( text = "Last update: ${formatISOTimestamp(timestamp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant )
            }
            // Display fetch failure message if the *last* attempt failed
            if (fetchFailed) {
                Spacer(modifier = Modifier.height(4.dp))
                Text( text = "Failed to update sensor data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error )
            }
        }
    }
}

// --- Helper Function to Format Timestamp (Unchanged) ---
fun formatISOTimestamp(isoTimestamp: String?): String {
    if (isoTimestamp == null) return "N/A"
    return try {
        val inputPatterns = listOf( SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.getDefault()), SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()) )
        inputPatterns.forEach { it.timeZone = TimeZone.getTimeZone("UTC") }
        val outputFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        outputFormat.timeZone = TimeZone.getDefault()
        var parsedDate: java.util.Date? = null
        for (pattern in inputPatterns) { try { parsedDate = pattern.parse(isoTimestamp); if (parsedDate != null) break } catch (e: java.text.ParseException) { /* Try next */ } }
        parsedDate?.let { outputFormat.format(it) } ?: isoTimestamp
    } catch (e: Exception) { Log.w(TAG, "Failed to parse timestamp '$isoTimestamp'", e); isoTimestamp }
}


// --- Notification Helper Functions (Unchanged) ---
fun createNotificationChannel(context: Context) { /* ... Channel creation logic ... */
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel( PING_CHANNEL_ID, PING_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH ).apply { description = PING_CHANNEL_DESCRIPTION }
        val notificationManager: NotificationManager? = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel); Log.d(TAG, "Notification channel '$PING_CHANNEL_ID' created or ensured.") }
}

fun showSimpleNotification(context: Context, title: String, message: String, notificationId: Int) { /* ... Permission check ... */
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Cannot show notification: POST_NOTIFICATIONS permission not granted."); return } }
    val notificationIcon = R.drawable.ic_notification_default // <-- !!! UPDATE THIS !!!
    val builder = NotificationCompat.Builder(context, PING_CHANNEL_ID)
        .setSmallIcon(notificationIcon).setContentTitle(title).setContentText(message)
        .setPriority( if (notificationId == CHECK_REQUEST_NOTIFICATION_ID || notificationId == NOT_WELL_NOTIFICATION_ID || notificationId == FALL_DETECTED_NOTIFICATION_ID) // Uses correct ID
            NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT )
        .setAutoCancel(true)
    try { NotificationManagerCompat.from(context).notify(notificationId, builder.build()); Log.d(TAG, "Notification shown with ID: $notificationId")
    } catch (e: Exception) { Log.e(TAG, "Error showing notification", e) }
}