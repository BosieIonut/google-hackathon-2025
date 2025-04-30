package com.example.hack2025.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.hack2025.data.models.UserInfo // Adjusted path based on your feedback
import com.example.hack2025.ui.screen.DashboardScreen
import com.example.hack2025.ui.screen.LoginScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Define navigation routes as constants
object AppDestinations {
    const val LOGIN_ROUTE = "login"
    // Define dashboard route with arguments for user info
    const val DASHBOARD_ROUTE = "dashboard"
    // Argument names
    const val DASHBOARD_ARG_USER_ID = "userId"
    const val DASHBOARD_ARG_EMAIL = "email"
    const val DASHBOARD_ARG_NAME = "name"
    const val DASHBOARD_ARG_TOKEN = "token"
    const val DASHBOARD_ARG_USER_TYPE = "userType" // New argument
    const val DASHBOARD_ARG_FRIEND_EMAIL = "friendEmail" // New argument

    // Construct the route pattern with mandatory and optional arguments
    // Mandatory args in path, optional args as query params
    val dashboardRouteWithArgs =
        "$DASHBOARD_ROUTE/{$DASHBOARD_ARG_USER_ID}/{$DASHBOARD_ARG_EMAIL}/{$DASHBOARD_ARG_USER_TYPE}/{$DASHBOARD_ARG_FRIEND_EMAIL}" +
                "?$DASHBOARD_ARG_NAME={$DASHBOARD_ARG_NAME}" +
                "&$DASHBOARD_ARG_TOKEN={$DASHBOARD_ARG_TOKEN}"
}

/**
 * File Path: app/src/main/java/com/example/hack2025/ui/navigation/AppNavigation.kt
 *
 * Sets up the navigation graph for the application using Jetpack Navigation Compose.
 *
 * @param navController The navigation controller used to manage screen transitions.
 */
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppDestinations.LOGIN_ROUTE // Start at the login screen
    ) {
        // Login Screen Composable
        composable(AppDestinations.LOGIN_ROUTE) {
            LoginScreen(
                onLoginSuccess = { userInfo ->
                    // Navigate to Dashboard on success, passing user info as arguments
                    // Encode arguments to handle special characters safely in URLs
                    val encodedEmail = URLEncoder.encode(userInfo.email, StandardCharsets.UTF_8.toString())
                    val encodedUserType = URLEncoder.encode(userInfo.userType, StandardCharsets.UTF_8.toString()) // Encode new field
                    val encodedFriendEmail = URLEncoder.encode(userInfo.friend, StandardCharsets.UTF_8.toString()) // Encode new field
                    val encodedName = URLEncoder.encode(userInfo.name ?: "N/A", StandardCharsets.UTF_8.toString())
                    val encodedToken = URLEncoder.encode(userInfo.authToken ?: "N/A", StandardCharsets.UTF_8.toString())

                    // Build the route string carefully
                    // Mandatory args first (in the path)
                    val routePath = "${AppDestinations.DASHBOARD_ROUTE}/${userInfo.userId}/$encodedEmail/$encodedUserType/$encodedFriendEmail"
                    // Optional args (as query parameters)
                    val routeQuery = "?${AppDestinations.DASHBOARD_ARG_NAME}=$encodedName" +
                            "&${AppDestinations.DASHBOARD_ARG_TOKEN}=$encodedToken"
                    val route = routePath + routeQuery

                    navController.navigate(route) {
                        // Pop LoginScreen off the back stack so user can't go back to it
                        popUpTo(AppDestinations.LOGIN_ROUTE) { inclusive = true }
                        // Avoid multiple copies of Dashboard if re-navigating quickly
                        launchSingleTop = true
                    }
                }
            )
        }

        // Dashboard Screen Composable with Arguments
        composable(
            route = AppDestinations.dashboardRouteWithArgs,
            arguments = listOf(
                // Mandatory arguments (from path)
                navArgument(AppDestinations.DASHBOARD_ARG_USER_ID) { type = NavType.StringType },
                navArgument(AppDestinations.DASHBOARD_ARG_EMAIL) { type = NavType.StringType },
                navArgument(AppDestinations.DASHBOARD_ARG_USER_TYPE) { type = NavType.StringType }, // Define new arg type
                navArgument(AppDestinations.DASHBOARD_ARG_FRIEND_EMAIL) { type = NavType.StringType }, // Define new arg type
                // Optional arguments (from query params)
                navArgument(AppDestinations.DASHBOARD_ARG_NAME) { type = NavType.StringType; nullable = true; defaultValue = "N/A" },
                navArgument(AppDestinations.DASHBOARD_ARG_TOKEN) { type = NavType.StringType; nullable = true; defaultValue = "N/A" }
            )
        ) { backStackEntry ->
            // Retrieve arguments (automatically URL-decoded by Navigation Compose)
            val userId = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_USER_ID)
            val email = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_EMAIL)
            val userType = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_USER_TYPE) // Retrieve new arg
            val friendEmail = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_FRIEND_EMAIL) // Retrieve new arg
            val name = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_NAME)?.takeIf { it != "N/A" } // Handle default value
            val token = backStackEntry.arguments?.getString(AppDestinations.DASHBOARD_ARG_TOKEN)?.takeIf { it != "N/A" } // Handle default value

            DashboardScreen(
                userId = userId,
                userEmail = email,
                userName = name,
                authToken = token,
                userType = userType, // Pass new arg
                friendEmail = friendEmail, // Pass new arg
                onLogout = {
                    // Navigate back to Login on logout
                    navController.navigate(AppDestinations.LOGIN_ROUTE) {
                        // Clear the entire back stack up to the login screen
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        // Ensure only one instance of login screen
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
