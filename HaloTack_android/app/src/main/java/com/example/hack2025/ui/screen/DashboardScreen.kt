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
// Import required icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person // Icon for Protege
import androidx.compose.material.icons.filled.Shield // Icon for Guardian
import androidx.compose.material.icons.outlined.Info // Icon for failed update
import androidx.compose.material.icons.outlined.Thermostat // Icon for Temperature
import androidx.compose.material.icons.outlined.WaterDrop // Icon for Humidity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // For font size customization
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
 * Enhanced Dashboard UI with role icons, better spacing, and refined Monitor card.
 * App Bar title is "HaloTrack". Handles alerts and displays sensor data.
 */

// Notification constants (Unchanged)
const val PING_CHANNEL_ID = "ping_channel"; const val PING_CHANNEL_NAME = "Pings"; const val PING_CHANNEL_DESCRIPTION = "Notifications for pings between users"
const val CHECK_REQUEST_NOTIFICATION_ID = 2; const val NOT_WELL_NOTIFICATION_ID = 3; const val FALL_DETECTED_NOTIFICATION_ID = 4

// Tag for logging (Unchanged)
private const val TAG = "DashboardScreen"
// Polling Intervals (Unchanged)
private const val NOTIFICATION_POLLING_INTERVAL_MS = 3000L; private const val MONITOR_POLLING_INTERVAL_MS = 10000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userId: String?, userEmail: String?, userName: String?, authToken: String?,
    userType: String?, friendEmail: String?, onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- State Variables (Unchanged) ---
    var activeCheckOkSender by remember { mutableStateOf<String?>(null) }
    var lastProtegeResponse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNotWellBanner by remember { mutableStateOf(false) }
    var showFallDetectedBanner by remember { mutableStateOf(false) }
    var temperature by remember { mutableStateOf<Float?>(null) }
    var humidity by remember { mutableStateOf<Float?>(null) }
    var monitorTimestamp by remember { mutableStateOf<String?>(null) }
    var monitorDataFetchFailed by remember { mutableStateOf(false) }


    // --- Notification Permission Handling (Unchanged) ---
    var hasNotificationPermission by remember { mutableStateOf( if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED } else { true } ) }
    val permissionLauncher = rememberLauncherForActivityResult( contract = ActivityResultContracts.RequestPermission(), onResult = { isGranted -> hasNotificationPermission = isGranted; if (!isGranted) Toast.makeText(context, "Notification permission denied.", Toast.LENGTH_LONG).show() else { createNotificationChannel(context); Toast.makeText(context, "Notification permission granted.", Toast.LENGTH_SHORT).show() } } )
    LaunchedEffect(key1 = true) { createNotificationChannel(context); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    // --- End Notification Permission Handling ---


    // --- Polling Logic: Notifications (Unchanged) ---
    LaunchedEffect(userType, authToken) { /* ... Notification polling logic ... */
        if (authToken != null) { val bearerToken = "Bearer $authToken"; val currentUserType = userType?.lowercase()
            if (currentUserType == "protege" || currentUserType == "guardian") { Log.d(TAG, "Starting notification polling for $currentUserType.")
                while (isActive) { try { Log.d(TAG, "Polling for notifications ($currentUserType)..."); val response = ApiClient.instance.checkNotifications(bearerToken)
                    if (response.isSuccessful) { val notification = response.body(); val sender = notification?.senderEmail; val type = notification?.type
                        if (sender != null && type != null) {
                            if (currentUserType == "protege" && type == "check_ok") { Log.i(TAG, "Protege received check_ok from $sender")
                                if (activeCheckOkSender == null) { activeCheckOkSender = sender; if (hasNotificationPermission) showSimpleNotification(context, "Check-in Request", "$sender is checking on you!", CHECK_REQUEST_NOTIFICATION_ID) else Toast.makeText(context,"Received check-in (notifications blocked).", Toast.LENGTH_LONG).show() } else Log.d(TAG, "Already handling check_ok from $activeCheckOkSender, ignoring new one.")
                            } else if (currentUserType == "guardian") { when (type) {
                                "yes_ok", "no_ok" -> { Log.i(TAG, "Guardian received $type response from $sender"); lastProtegeResponse = Pair(sender, type); val rT = if(type == "yes_ok") "Yes, OK" else "No, Need Help"; Toast.makeText(context, "Response from $sender: $rT", Toast.LENGTH_SHORT).show() }
                                "not_well" -> { Log.i(TAG, "Guardian received not_well alert from $sender"); showNotWellBanner = true; lastProtegeResponse = null; Toast.makeText(context, "$sender reported feeling unwell.", Toast.LENGTH_LONG).show(); if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "$sender is not feeling well.", NOT_WELL_NOTIFICATION_ID) }
                                "fall_detected" -> { Log.i(TAG, "Guardian received fall_detected alert from $sender"); showFallDetectedBanner = true; lastProtegeResponse = null; Toast.makeText(context, "Fall detected for $sender!", Toast.LENGTH_LONG).show(); if (hasNotificationPermission) showSimpleNotification(context, "Protege Alert", "Fall Detected for $sender!", FALL_DETECTED_NOTIFICATION_ID) }
                                else -> Log.d(TAG, "Guardian received notification type '$type' not handled.") }
                            } else Log.d(TAG, "Received notification type '$type' not relevant for current user type '$currentUserType'.")
                        } else if (response.code() == 200) Log.d(TAG, "No new relevant notifications found for $currentUserType.")
                    } else { Log.e(TAG, "Error checking notifications ($currentUserType): ${response.code()} - ${response.message()}"); if (response.code() == 401) { Toast.makeText(context, "Auth error checking notifications.", Toast.LENGTH_LONG).show(); break }; delay(5000L) }
                } catch (e: Exception) { Log.e(TAG, "Exception during notification polling ($currentUserType)", e); delay(5000L) }; delay(NOTIFICATION_POLLING_INTERVAL_MS) }
                Log.d(TAG, "Notification polling stopped for $currentUserType.") }
        } else { Log.w(TAG, "Cannot start notification polling, auth token is null.") } }
    // --- End Notification Polling Logic ---


    // --- Polling Logic: Monitor Data (Unchanged) ---
    LaunchedEffect(authToken) { /* ... Monitor data polling logic ... */
        if (authToken != null) { Log.d(TAG, "Starting monitor data polling.")
            while(isActive) { var fetchSuccess = false
                try { Log.d(TAG, "Polling for monitor data..."); val response = ApiClient.instance.getCurrentMonitorData()
                    if (response.isSuccessful) { fetchSuccess = true; val data = response.body(); temperature = data?.temperature; humidity = data?.humidity; monitorTimestamp = data?.timestamp; monitorDataFetchFailed = false; Log.d(TAG, "Monitor data updated: Temp=${temperature}, Humid=${humidity}, TS=${monitorTimestamp}")
                    } else { Log.e(TAG, "Error polling monitor data: ${response.code()} - ${response.message()}"); monitorDataFetchFailed = true; delay(MONITOR_POLLING_INTERVAL_MS * 2) }
                } catch (e: Exception) { Log.e(TAG, "Exception during monitor data polling", e); monitorDataFetchFailed = true; delay(MONITOR_POLLING_INTERVAL_MS * 2) }; delay(MONITOR_POLLING_INTERVAL_MS) }
            Log.d(TAG, "Monitor data polling stopped.")
        } else { Log.w(TAG, "Cannot start monitor data polling, auth token is null."); temperature = null; humidity = null; monitorTimestamp = null; monitorDataFetchFailed = false } }
    // --- End Monitor Data Polling Logic ---


    // --- UI Layout using Scaffold ---
    Scaffold(
        topBar = { TopAppBar( title = { Text("HaloTrack") }, // Updated title
            actions = { IconButton(onClick = onLogout) { Icon(Icons.Filled.Logout, "Logout") } } ) }
    ) { innerPadding ->
        Column( // Main content column
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp), // Outer padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp) // Add consistent spacing between main sections
        ) {
            // Welcome Message
            Text( text = "Hello, ${userName ?: "User"}!", style = MaterialTheme.typography.headlineLarge ) // Slightly larger welcome

            // User Role Display with Icon
            if (userType != null) {
                UserRoleDisplay(userType = userType) // Extracted composable for role
            } else {
                Text("User role information not available.", style = MaterialTheme.typography.bodyMedium)
            }

            // Role-Specific Content (Guardian/Protege Cards and Alerts)
            // Wrap in a Box to allow content presence check
            Box(modifier = Modifier.fillMaxWidth()) {
                if (userType != null) {
                    when (userType.lowercase()) {
                        "guardian" -> {
                            GuardianContent( userEmail = userEmail, friendEmail = friendEmail, lastProtegeResponse = lastProtegeResponse,
                                showNotWellBanner = showNotWellBanner, showFallDetectedBanner = showFallDetectedBanner,
                                hasNotificationPermission = hasNotificationPermission,
                                onDismissNotWell = { showNotWellBanner = false }, onDismissFall = { showFallDetectedBanner = false },
                                onSendCheckIn = { lastProtegeResponse = null; showNotWellBanner = false; showFallDetectedBanner = false
                                    if (userEmail != null && friendEmail != null) { scope.launch { val nD = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to "check_ok"); try { val response = ApiClient.instance.sendNotification(nD); if (response.isSuccessful) Toast.makeText(context, "Check-in request sent!", Toast.LENGTH_SHORT).show() else Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() } } } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() } } ) }
                        "protege" -> {
                            ProtegeContent( userEmail = userEmail, friendEmail = friendEmail, activeCheckOkSender = activeCheckOkSender,
                                onSendResponse = { responseType -> if (userEmail != null && friendEmail != null) { scope.launch { val rD = mapOf("recipient_email" to friendEmail, "sender_email" to userEmail, "notification_type" to responseType); try { val response = ApiClient.instance.sendNotification(rD); val tM = if (responseType == "yes_ok") "Responded: Yes, OK" else "Responded: No, Need Help"; if (response.isSuccessful) { Toast.makeText(context, tM, Toast.LENGTH_SHORT).show(); activeCheckOkSender = null } else { Toast.makeText(context, "Failed: ${response.message()}", Toast.LENGTH_LONG).show() } } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() } } } else { Toast.makeText(context, "Error: Emails missing.", Toast.LENGTH_LONG).show() } } ) }
                    }
                }
                // No need for explicit spacer here if main column has arrangement spacedBy
            }


            // Common Content: Monitor Card
            MonitorCard( temperature = temperature, humidity = humidity,
                timestamp = monitorTimestamp, fetchFailed = monitorDataFetchFailed )

        } // End Main content Column
    } // End Scaffold
}

// --- Extracted Composable for User Role Display ---
@Composable
fun UserRoleDisplay(userType: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp) // Add some vertical padding
    ) {
        val icon = when (userType.lowercase()) {
            "guardian" -> Icons.Filled.Shield
            "protege" -> Icons.Filled.Person
            else -> null // Handle unknown types if necessary
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = "$userType Role",
                modifier = Modifier.size(32.dp), // Make icon slightly larger
                tint = MaterialTheme.colorScheme.primary // Use primary color for the icon
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = "You are a $userType",
            style = MaterialTheme.typography.titleMedium, // Make text slightly larger/bolder
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary // Match icon color
        )
    }
}


// --- Extracted Composable for Guardian Content ---
@Composable
fun GuardianContent(
    userEmail: String?, friendEmail: String?, lastProtegeResponse: Pair<String, String>?,
    showNotWellBanner: Boolean, showFallDetectedBanner: Boolean,
    hasNotificationPermission: Boolean,
    onDismissNotWell: () -> Unit, onDismissFall: () -> Unit, onSendCheckIn: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { // Add spacing between card and alerts
        // Card 1: Protege Info & Action Button
        Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (friendEmail != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { // Add icon next to label
                        Icon(Icons.Filled.Person, contentDescription = "Protege Icon", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(4.dp))
                        Text("Your protege:", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(friendEmail, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 22.dp)) // Indent name slightly
                    Spacer(modifier = Modifier.height(16.dp))
                    val (responseText, responseColor) = lastProtegeResponse?.let { (_, respType) -> if (respType == "yes_ok") "Responded: Yes, OK" to MaterialTheme.colorScheme.primary else "Responded: No, Need Help!" to MaterialTheme.colorScheme.error } ?: ("" to LocalContentColor.current)
                    if (responseText.isNotEmpty()) { Text( responseText, style = MaterialTheme.typography.bodyMedium, color = responseColor, fontWeight = if (lastProtegeResponse?.second == "no_ok") FontWeight.Bold else FontWeight.Normal ); Spacer(modifier = Modifier.height(16.dp)) }
                    else { Spacer(modifier = Modifier.height(16.dp)) } // Ensure space before button always
                    Button( modifier = Modifier.fillMaxWidth(), onClick = onSendCheckIn ) { Text("Check on Protege") }
                } else { Text("Protege information not available.", style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // Alert Card: "Not Well"
        if (showNotWellBanner) { AlertCard( message = "Your protege is not feeling well", onDismiss = onDismissNotWell ) }

        // Alert Card: "Fall Detected"
        if (showFallDetectedBanner) { AlertCard( message = "Fall Detected for Protege", isImportant = true, onDismiss = onDismissFall ) }
    }
}

// --- Extracted Composable for Protege Content ---
@Composable
fun ProtegeContent( userEmail: String?, friendEmail: String?, activeCheckOkSender: String?, onSendResponse: (String) -> Unit ) {
    Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) ) {
        Column( modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally ) {
            if (friendEmail != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Start)) { // Align row to start
                    Icon(Icons.Filled.Shield, contentDescription = "Guardian Icon", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text("Your guardian:", style = MaterialTheme.typography.labelLarge)
                }
                Text(friendEmail, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Start).padding(start = 22.dp)) // Indent name
                Spacer(modifier = Modifier.height(24.dp)) // More space before check-in request

                if (activeCheckOkSender != null) {
                    Text( "$activeCheckOkSender is checking on you!",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp) )
                    Row( modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally) ) {
                        Button( modifier = Modifier.weight(1f), onClick = { onSendResponse("yes_ok") } ) { Text("Yes, I'm OK") }
                        Button( modifier = Modifier.weight(1f), onClick = { onSendResponse("no_ok") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) ) { Text("No, Need Help") }
                    }
                } else {
                    // Maybe add a subtle placeholder or visual element when idle?
                    // For now, keeping it empty as requested earlier.
                    Spacer(modifier = Modifier.height(80.dp)) // Add fixed space to prevent layout jump
                }
            } else { Text("Guardian info unavailable.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// --- Extracted Composable for Dismissible Alert Cards (Unchanged) ---
@Composable
fun AlertCard( message: String, isImportant: Boolean = false, onDismiss: () -> Unit ) { /* ... Alert Card UI ... */
    Card( modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) ) {
        Row( modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically ) {
            Text( text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = if (isImportant) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Start, modifier = Modifier.weight(1f) )
            IconButton(onClick = onDismiss) { Icon( imageVector = Icons.Filled.Close, contentDescription = if(message.startsWith("Fall Detected")) "Dismiss fall detected alert" else "Dismiss alert: $message", tint = MaterialTheme.colorScheme.onErrorContainer ) } } }
}


// --- Extracted Composable for Monitor Data Card ---
@Composable
fun MonitorCard( temperature: Float?, humidity: Float?, timestamp: String?, fetchFailed: Boolean ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        // Optional: Add a subtle border or different background for Monitor card
        // border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally // Center column content
        ) {
            Text( text = "Bedroom Monitor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp) ) // More descriptive title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly // Distribute items evenly
            ) {
                // Temperature Column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Thermostat, contentDescription = "Temperature Icon", tint = Color(0xFFFFA500) /* Orange-ish */, modifier = Modifier.size(28.dp)) // Temp icon
                    Spacer(modifier = Modifier.height(4.dp))
                    Text( text = "${temperature?.toString() ?: "--"} °C", style = MaterialTheme.typography.headlineSmall, // Larger value text
                        fontWeight = FontWeight.SemiBold )
                    Text(text = "Temperature", style = MaterialTheme.typography.bodySmall) // Label below value
                }
                // Humidity Column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.WaterDrop, contentDescription = "Humidity Icon", tint = Color(0xFF1E90FF) /* Dodger Blue */, modifier = Modifier.size(28.dp)) // Humidity icon
                    Spacer(modifier = Modifier.height(4.dp))
                    Text( text = "${humidity?.toString() ?: "--"} %", style = MaterialTheme.typography.headlineSmall, // Larger value text
                        fontWeight = FontWeight.SemiBold )
                    Text(text = "Humidity", style = MaterialTheme.typography.bodySmall) // Label below value
                }
            }
            // Timestamp and Fetch Status Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp), // Add padding above this row
                horizontalArrangement = Arrangement.Center, // Center timestamp/status info
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (fetchFailed) { // Show error icon if fetch failed
                    Icon( Icons.Outlined.Info, contentDescription = "Update Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp) )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text( text = "Failed to update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error )
                } else if (timestamp != null) { // Show timestamp if fetch succeeded
                    Text( text = "Last update: ${formatISOTimestamp(timestamp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant )
                }
            }
        }
    }
}

// --- Helper Function to Format Timestamp (Unchanged) ---
fun formatISOTimestamp(isoTimestamp: String?): String { /* ... Formatting logic ... */ if (isoTimestamp == null) return "N/A"; return try { val iPs=listOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS",Locale.getDefault()), SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.getDefault())); iPs.forEach {it.timeZone=TimeZone.getTimeZone("UTC")}; val oF=SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()); oF.timeZone=TimeZone.getDefault(); var pD: java.util.Date?=null; for(p in iPs){try{pD=p.parse(isoTimestamp); if(pD!=null)break}catch(e: java.text.ParseException){}}; pD?.let{oF.format(it)}?:isoTimestamp }catch(e:Exception){Log.w(TAG,"Failed to parse timestamp '$isoTimestamp'",e); isoTimestamp} }

// --- Notification Helper Functions (Unchanged) ---
fun createNotificationChannel(context: Context) { /* ... Channel creation logic ... */ if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val c=NotificationChannel(PING_CHANNEL_ID, PING_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply{description= PING_CHANNEL_DESCRIPTION}; val nM:NotificationManager?=context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager; nM?.createNotificationChannel(c); Log.d(TAG,"Notification channel '$PING_CHANNEL_ID' created or ensured.") } }
fun showSimpleNotification(context: Context, title: String, message: String, notificationId: Int) { /* ... Permission check & Notification build/show logic ... */ if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "Cannot show notification: POST_NOTIFICATIONS permission not granted."); return } }; val nI=R.drawable.ic_notification_default; val b=NotificationCompat.Builder(context, PING_CHANNEL_ID).setSmallIcon(nI).setContentTitle(title).setContentText(message).setPriority(if (notificationId == CHECK_REQUEST_NOTIFICATION_ID || notificationId == NOT_WELL_NOTIFICATION_ID || notificationId == FALL_DETECTED_NOTIFICATION_ID) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true); try { NotificationManagerCompat.from(context).notify(notificationId, b.build()); Log.d(TAG, "Notification shown with ID: $notificationId") } catch (e: Exception) { Log.e(TAG, "Error showing notification", e) } }