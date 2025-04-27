package com.example.hack2025.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hack2025.data.models.UserInfo
import com.example.hack2025.ui.viewmodel.LoginUiState
import com.example.hack2025.ui.viewmodel.LoginViewModel

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/screen/LoginScreen.kt
 *
 * Composable function for the Login user interface.
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
                Toast.makeText(context, "Login Successful!", Toast.LENGTH_SHORT).show()
                onLoginSuccess(state.userInfo) // Trigger navigation
                // Optionally reset state in ViewModel if needed after navigation
                // loginViewModel.resetState()
            }
            is LoginUiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                // Keep the error state until user interacts again (handled in ViewModel)
            }
            else -> { /* Handle Idle or Loading if necessary, e.g., disable inputs */ }
        }
    }

    // Main layout column
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp), // Add padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center // Center content vertically
    ) {
        Text("Login", style = MaterialTheme.typography.headlineLarge)

        Spacer(modifier = Modifier.height(40.dp))

        // Email Input Field
        OutlinedTextField(
            value = loginViewModel.email,
            onValueChange = { loginViewModel.onEmailChange(it) },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            isError = loginState is LoginUiState.Error, // Show error state
            enabled = loginState !is LoginUiState.Loading // Disable when loading
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
            trailingIcon = {
                val image = if (passwordVisible)
                    Icons.Filled.Visibility
                else Icons.Filled.VisibilityOff
                val description = if (passwordVisible) "Hide password" else "Show password"

                IconButton(onClick = {passwordVisible = !passwordVisible}){
                    Icon(imageVector  = image, description)
                }
            },
            isError = loginState is LoginUiState.Error, // Show error state
            enabled = loginState !is LoginUiState.Loading // Disable when loading
        )

        // Display error message below fields if needed (alternative to Toast)
        if (loginState is LoginUiState.Error) {
            Text(
                text = (loginState as LoginUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Login Button and Loading Indicator
        Box(contentAlignment = Alignment.Center) {
            Button(
                onClick = { loginViewModel.attemptLogin() },
                modifier = Modifier.fillMaxWidth(),
                enabled = loginState !is LoginUiState.Loading // Disable button when loading
            ) {
                Text("Login")
            }
            // Show progress indicator when loading
            if (loginState is LoginUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
    }
}
