package com.yumzy.restaurant.auth

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.MiniRestaurant
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onLoginSuccess: (MiniRestaurant) -> Unit
) {
    var allRestaurants by remember { mutableStateOf<List<MiniRestaurant>>(emptyList()) }
    var selectedRestaurant by remember { mutableStateOf<MiniRestaurant?>(null) }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Fetch all restaurants for the dropdown
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val snapshot = Firebase.firestore.collection("mini_restaurants").get().await()
            allRestaurants = snapshot.documents.mapNotNull {
                it.toObject(MiniRestaurant::class.java)?.copy(id = it.id)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error fetching restaurants: ${e.message}", Toast.LENGTH_LONG).show()
        }
        isLoading = false
    }

    fun handleLogin() {
        if (selectedRestaurant == null) {
            Toast.makeText(context, "Please select a restaurant", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isBlank()) {
            Toast.makeText(context, "Please enter a password", Toast.LENGTH_SHORT).show()
            return
        }

        isLoading = true
        // Check if the password matches
        if (selectedRestaurant!!.password == password) {
            Toast.makeText(context, "Login Successful! Welcome ${selectedRestaurant!!.name}", Toast.LENGTH_SHORT).show()
            onLoginSuccess(selectedRestaurant!!)
        } else {
            Toast.makeText(context, "Incorrect password. Please try again.", Toast.LENGTH_LONG).show()
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Foodish Restaurant",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Partner Login",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(40.dp))

        if (isLoading && allRestaurants.isEmpty()) {
            CircularProgressIndicator()
        } else {
            // Restaurant Dropdown
            ExposedDropdownMenuBox(
                expanded = isDropdownExpanded,
                onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedRestaurant?.name ?: "Select your restaurant",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Restaurant") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    allRestaurants.forEach { restaurant ->
                        DropdownMenuItem(
                            text = { Text(restaurant.name ?: "Unnamed Restaurant") },
                            onClick = {
                                selectedRestaurant = restaurant
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Password Field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { handleLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Login", fontSize = 18.sp)
                }
            }
        }
    }
}