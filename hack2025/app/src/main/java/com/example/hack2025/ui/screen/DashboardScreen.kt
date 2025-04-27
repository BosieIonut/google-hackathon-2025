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
// Import the specific icon needed
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
 * Implements polling for both proteges (to receive check_ok) and guardians
 * (to receive yes_ok/no_ok/not_well/fall_detection responses). Allows responding. Styled with Material 3.
 * Guardian view now separates alerts into distinct cards with an icon button for dismissal.
 */

// Notification constants (Unchanged)
const val PING_CHANNEL_ID = "ping_channel"
const val PING_CHANNEL_NAME = "Pings"
const val PING_CHANNEL_DESCRIPTION = "Notifications for pings between users"
const val PING_NOTIFICATION_ID = 1
const val CHECK_REQUEST_NOTIFICATION_ID = 2
const val NOT_WELL_NOTIFICATION_ID = 3
const val FALL_DETECTED_NOTIFICATION_ID = 4


// Tag for logging (Unchanged)
private const val TAG = "DashboardScreen"
private const val POLLING_INTERVAL_MS = 3000L

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

    // State variables (Unchanged)
    var activeCheckOkSender by remember { mutableStateOf<String?>(null) }
    var lastProtegeResponse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNotWellBanner by remember { mutableStateOf(false) }
    var showFallDetectionBanner by remember { mutableStateOf(false) }


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


    // --- Polling Logic (Unchanged) ---
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
                            val notification = response.body()
                            val sender = notification?.senderEmail
                            val type = notification?.type

                            if (sender != null && type != null) {
                                if (currentUserType == "protege" && type == "check_ok") {
                                    Log.i(TAG, "Protege received check_ok from $sender")
                                    if (activeCheckOkSender == null) {
                                        activeCheckOkSender = sender
                                        if (hasNotificationPermission) {
                                            showSimpleNotification(context, "Check-in Request", "$sender is checking on you!", CHECK_REQUEST_NOTIFICATION_ID)
                                        } else {
                                            Toast.makeText(context,"Received check-in (notifications blocked).", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Log.d(TAG, "Already handling check_ok from $activeCheckOkSender, ignoring new one.")
                                    }
                                }
                                else if (currentUserType == "guardian") {
                                    when (type) {
                                        "yes_ok", "no_ok" -> {
                                            Log.i(TAG, "Guardian received $type response from $sender")
                                            lastProtegeResponse = Pair(sender, type)
                                            val responseText = if(type == "yes_ok") "Yes, OK" else "No, Need Help"
                                            Toast.makeText(context, "Response from $sender: $responseText", Toast.LENGTH_SHORT).show()
                                        }
                                        "not_well" -> {
                                            Log.i(TAG, "Guardian received not_well alert from $sender")
                                            showNotWellBanner = true
                                            lastProtegeResponse = null
                                            Toast.makeText(context, "$sender reported feeling unwell.", Toast.LENGTH_LONG).show()
                                            if (hasNotificationPermission) {
                                                showSimpleNotification(context, "Protege Alert", "$sender is not feeling well.", NOT_WELL_NOTIFICATION_ID)
                                            }
                                        }
                                        "fall_detection" -> {
                                            Log.i(TAG, "Guardian received fall_detection alert from $sender")
                                            showFallDetectionBanner = true
                                            lastProtegeResponse = null
                                            Toast.makeText(context, "$sender may have fallen!", Toast.LENGTH_LONG).show()
                                            if (hasNotificationPermission) {
                                                showSimpleNotification(context, "Protege Alert", "$sender might have fallen!", FALL_DETECTED_NOTIFICATION_ID)
                                            }
                                        }
                                        else -> { Log.d(TAG, "Guardian received notification type '$type' not handled.") }
                                    }
                                }
                                else { Log.d(TAG, "Received notification type '$type' not relevant for current user type '$currentUserType'.") }
                            } else if (response.code() == 200) { Log.d(TAG, "No new relevant notifications found for $currentUserType.") }
                        } else {
                            Log.e(TAG, "Error checking notifications ($currentUserType): ${response.code()} - ${response.message()}")
                            if (response.code() == 401) { Toast.makeText(context, "Auth error checking notifications.", Toast.LENGTH_LONG).show(); break }
                            delay(5000L)
                        }
                    } catch (e: Exception) { Log.e(TAG, "Exception during notification polling ($currentUserType)", e); delay(5000L) }
                    delay(POLLING_INTERVAL_MS)
                }
                Log.d(TAG, "Notification polling stopped for $currentUserType.")
            }
        } else { Log.w(TAG, "Cannot start polling, auth token is null.") }
    }
    // --- End Polling Logic ---


    // --- UI Layout using Scaffold ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
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
            Text(
                text = "Hello, ${userName ?: "User"}!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (userType != null) {
                Text(
                    text = "You are a: $userType",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                when (userType.lowercase()) {
                    // --- Guardian View ---
                    "guardian" -> {
                        // *** Card 1: Protege Info & Action Button (Unchanged) ***
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
                                        Text(
                                            responseText, style = MaterialTheme.typography.bodyMedium,
                                            color = responseColor,
                                            fontWeight = if (lastProtegeResponse?.second == "no_ok") FontWeight.Bold else FontWeight.Normal
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    } else { Spacer(modifier = Modifier.height(16.dp)) }

                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            lastProtegeResponse = null
                                            showNotWellBanner = false
                                            showFallDetectionBanner = false
                                            if (userEmail != null && friendEmail != null) {
                                                scope.launch { /* ... send check_ok notification ... */
                                                    val notificationData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "check_ok")
                                                    try {
                                                        val response = ApiClient.instance.sendNotification(notificationData)
                                                        if (response.isSuccessful) {
                                                            Toast.makeText(context, "Check-in request sent!", Toast.LENGTH_SHORT).show()
                                                            if (hasNotificationPermission) { showSimpleNotification(context, "Check-in Sent", "Waiting for response...", PING_NOTIFICATION_ID) }
                                                            else { Toast.makeText(context,"Request sent (no permission for local status).", Toast.LENGTH_LONG).show() }
                                                        } else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() }
                                                    } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                                                }
                                            } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() }
                                        }
                                    ) { Text("Check on Protege") }
                                } else { Text("Protege information not available.", style = MaterialTheme.typography.bodyMedium) }
                            }
                        } // End Card 1

                        // Spacer between main card and potential alerts
                        Spacer(modifier = Modifier.height(16.dp))

                        // *** Alert Card 2: "Not Well" Alert (Conditional & Dismissible with Icon) ***
                        if (showNotWellBanner) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp), // Padding for the Row content
                                    verticalAlignment = Alignment.CenterVertically // Align text and icon vertically
                                ) {
                                    // Alert Text (takes up available space)
                                    Text(
                                        text = "Your protege is not feeling well",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Start, // Align text to the start
                                        modifier = Modifier.weight(1f) // Make text expand
                                    )
                                    // Spacer(modifier = Modifier.width(8.dp)) // Optional space between text and icon

                                    // Dismiss Icon Button
                                    IconButton(onClick = { showNotWellBanner = false }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Dismiss 'not well' alert", // Accessibility
                                            tint = MaterialTheme.colorScheme.onErrorContainer // Match text color
                                        )
                                    }
                                }
                            }
                            // Space below this alert card
                            Spacer(modifier = Modifier.height(16.dp))
                        }


                        // *** Alert Card 3: "Fall Detection" Alert (Conditional & Dismissible with Icon) ***
                        if (showFallDetectionBanner) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp), // Padding for the Row content
                                    verticalAlignment = Alignment.CenterVertically // Align text and icon vertically
                                ) {
                                    // Alert Text (takes up available space)
                                    Text(
                                        text = "Your protege might have fallen",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold, // Keep fall detection bold
                                        textAlign = TextAlign.Start, // Align text to the start
                                        modifier = Modifier.weight(1f) // Make text expand
                                    )
                                    // Spacer(modifier = Modifier.width(8.dp)) // Optional space between text and icon

                                    // Dismiss Icon Button
                                    IconButton(onClick = { showFallDetectionBanner = false }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Dismiss 'fall detection' alert", // Accessibility
                                            tint = MaterialTheme.colorScheme.onErrorContainer // Match text color
                                        )
                                    }
                                }
                            }
                            // Optional: Spacer(modifier = Modifier.height(16.dp))
                        }

                    } // End Guardian case

                    // --- Protege View (Unchanged) ---
                    "protege" -> {
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
                                            Button( modifier = Modifier.weight(1f), onClick = { /* Send yes_ok */
                                                if (userEmail != null && friendEmail != null) {
                                                    scope.launch {
                                                        val responseData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "yes_ok")
                                                        try {
                                                            val response = ApiClient.instance.sendNotification(responseData)
                                                            if (response.isSuccessful) { Toast.makeText(context, "Responded: Yes, OK", Toast.LENGTH_SHORT).show(); activeCheckOkSender = null }
                                                            else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() }
                                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                                                    }
                                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() }
                                            }) { Text("Yes, I'm OK") }
                                            Button( modifier = Modifier.weight(1f), onClick = { /* Send no_ok */
                                                if (userEmail != null && friendEmail != null) {
                                                    scope.launch {
                                                        val responseData = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "no_ok")
                                                        try {
                                                            val response = ApiClient.instance.sendNotification(responseData)
                                                            if (response.isSuccessful) { Toast.makeText(context, "Responded: No, Need Help", Toast.LENGTH_SHORT).show(); activeCheckOkSender = null }
                                                            else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() }
                                                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                                                    }
                                                } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() }
                                            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) { Text("No, Need Help") }
                                        }
                                    } else { Text("Waiting for check-in from guardian...", style = MaterialTheme.typography.bodyMedium) }
                                } else { Text("Guardian info unavailable.", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    } // End Protege case
                } // End when(userType)
            } else {
                Text("User role information not available.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            }
        } // End top content Column
    } // End Scaffold content Column
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
    // IMPORTANT: Replace R.drawable.ic_notification_default with your actual icon resource ID.
    val notificationIcon = R.drawable.ic_notification_default // <-- !!! UPDATE THIS !!!
    val builder = NotificationCompat.Builder(context, PING_CHANNEL_ID)
        .setSmallIcon(notificationIcon)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(
            if (notificationId == NOT_WELL_NOTIFICATION_ID || notificationId == FALL_DETECTED_NOTIFICATION_ID)
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