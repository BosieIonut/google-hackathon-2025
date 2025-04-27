package com.example.hack2025.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hack2025.data.models.LoginRequest
import com.example.hack2025.data.models.UserInfo
import com.example.hack2025.data.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

// Sealed interface to represent the different states of the login process
sealed interface LoginUiState {
    object Idle : LoginUiState // Initial state
    object Loading : LoginUiState // Login request in progress
    data class Success(val userInfo: UserInfo) : LoginUiState // Login successful
    data class Error(val message: String) : LoginUiState // Login failed
}

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/viewmodel/LoginViewModel.kt
 *
 * ViewModel for the Login Screen. Handles user input state and the login API call.
 */
class LoginViewModel : ViewModel() {

    private val TAG = "LoginViewModel"

    // --- State Management ---

    // MutableState for text field inputs (directly observed by Composables)
    var email by mutableStateOf("")
        private set // Allow modification only within the ViewModel

    var password by mutableStateOf("")
        private set

    // Backing property for UI state (loading, success, error) using StateFlow
    private val _loginUiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    // Publicly exposed immutable StateFlow for Composables to observe
    val loginUiState: StateFlow<LoginUiState> = _loginUiState.asStateFlow()

    // --- Event Handlers ---

    fun onEmailChange(newEmail: String) {
        email = newEmail
        // Reset error state if user starts typing again
        if (_loginUiState.value is LoginUiState.Error) {
            resetState()
        }
    }

    fun onPasswordChange(newPassword: String) {
        password = newPassword
        // Reset error state if user starts typing again
        if (_loginUiState.value is LoginUiState.Error) {
            resetState()
        }
    }

    fun resetState() {
        _loginUiState.value = LoginUiState.Idle
    }


    // --- Login Logic ---

    fun attemptLogin() {
        // Basic validation (can be enhanced)
        if (!isValidEmail(email) || password.isBlank()) {
            _loginUiState.value = LoginUiState.Error("Please enter valid email and password.")
            return
        }

        // Set state to Loading and launch the API call in the ViewModel's scope
        _loginUiState.value = LoginUiState.Loading
        viewModelScope.launch {
            try {
                val loginRequest = LoginRequest(email, password)
                val response = ApiClient.instance.loginUser(loginRequest) // Use ApiClient singleton

                if (response.isSuccessful && response.body() != null) {
                    val userInfo = response.body()!!
                    Log.i(TAG, "Login successful: $userInfo")
                    _loginUiState.value = LoginUiState.Success(userInfo)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown login error"
                    Log.e(TAG, "Login failed: ${response.code()} - $errorBody")
                    _loginUiState.value = LoginUiState.Error("Login failed: ${response.message()}")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during login", e)
                _loginUiState.value = LoginUiState.Error("Network error. Check connection.")
            } catch (e: HttpException) {
                Log.e(TAG, "HTTP error during login", e)
                _loginUiState.value = LoginUiState.Error("Server error (${e.code()}). Try again later.")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during login", e)
                _loginUiState.value = LoginUiState.Error("An unexpected error occurred.")
            }
        }
    }

    // Simple email validation utility
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}
