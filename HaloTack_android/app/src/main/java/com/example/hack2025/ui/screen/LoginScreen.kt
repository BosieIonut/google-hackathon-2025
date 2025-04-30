package com.example.hack2025.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState // Import for scrolling
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll // Import for scrolling
import androidx.compose.material.icons.Icons
// Import necessary icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hack2025.data.models.UserInfo
import com.example.hack2025.ui.viewmodel.LoginUiState
import com.example.hack2025.ui.viewmodel.LoginViewModel

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/screen/LoginScreen.kt
 *
 * Composable function for the visually enhanced HaloTrack Login user interface.
 *
 * @param loginViewModel The ViewModel instance managing the login state and logic.
 * @param onLoginSuccess Callback invoked when login is successful, passing UserInfo.
 */
@Composable
fun LoginScreen(
    loginViewModel: LoginViewModel = viewModel(), // Get instance via compose lifecycle
    onLoginSuccess: (UserInfo) -> Unit // Callback to navigate
) {
    // Observe the UI state from the ViewModel
    val loginState by loginViewModel.loginUiState.collectAsState()
    val context = LocalContext.current // Get context for Toasts

    // State for password visibility toggle
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // Handle UI state changes (like showing errors or navigating on success)
    LaunchedEffect(loginState) {
        when (val state = loginState) {
            is LoginUiState.Success -> {
                // Toast is optional, navigation handles the transition
                // Toast.makeText(context, "Login Successful!", Toast.LENGTH_SHORT).show()
                onLoginSuccess(state.userInfo) // Trigger navigation
            }
            is LoginUiState.Error -> {
                // Show error in Toast and potentially below fields
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            else -> { /* No action needed for Idle or Loading here */ }
        }
    }

    // Main layout column - Added vertical scroll for smaller screens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // Allow scrolling if content overflows
            .padding(horizontal = 24.dp, vertical = 32.dp), // Adjusted padding
        horizontalAlignment = Alignment.CenterHorizontally,
        // Changed arrangement to SpaceAround for better vertical distribution
        verticalArrangement = Arrangement.SpaceAround // Distribute space
    ) {

        // App Name and Title Section
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "HaloTrack",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary, // Use primary color for brand
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Welcome Back!", // Friendly subtitle
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant // Subtle color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Sign in to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }


        // Input Fields Section
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Email Input Field
            OutlinedTextField(
                value = loginViewModel.email,
                onValueChange = { loginViewModel.onEmailChange(it) },
                label = { Text("Email Address") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                leadingIcon = { // Added leading icon
                    Icon(Icons.Filled.Email, contentDescription = "Email Icon")
                },
                isError = loginState is LoginUiState.Error,
                enabled = loginState !is LoginUiState.Loading
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Password Input Field
            OutlinedTextField(
                value = loginViewModel.password,
                onValueChange = { loginViewModel.onPasswordChange(it) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = { // Added leading icon
                    Icon(Icons.Filled.Lock, contentDescription = "Password Icon")
                },
                trailingIcon = { // Keep visibility toggle
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    val description = if (passwordVisible) "Hide password" else "Show password"
                    IconButton(onClick = {passwordVisible = !passwordVisible}){
                        Icon(imageVector = image, description)
                    }
                },
                isError = loginState is LoginUiState.Error,
                enabled = loginState !is LoginUiState.Loading
            )

            // Display error message below fields
            // Reserve space for the error message to prevent layout jumps
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // Allocate space even when no error
                .padding(top = 8.dp)
            ) {
                if (loginState is LoginUiState.Error) {
                    Text(
                        text = (loginState as LoginUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterStart) // Align text to start
                    )
                }
            }
        }


        // Login Button and Loading Indicator Section
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp) // Add some space above the button
        ) {
            Button(
                onClick = { loginViewModel.attemptLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp), // Make button slightly taller
                enabled = loginState !is LoginUiState.Loading,
                // Optional: Add custom button colors/shape
                // shape = MaterialTheme.shapes.medium,
                // colors = ButtonDefaults.buttonColors(...)
            ) {
                Text("Sign In", style = MaterialTheme.typography.titleMedium) // Changed text, slightly larger
            }
            // Show progress indicator when loading
            if (loginState is LoginUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp // Slightly thinner stroke?
                )
            }
        }
    } // End Main Column
}