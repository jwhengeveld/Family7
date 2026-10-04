package nl.family7.tv.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.family7.tv.R
import nl.family7.core.data.Family7AuthRepository
import nl.family7.core.data.UserSession
import nl.family7.tv.ui.components.Family7Logo
import nl.family7.tv.ui.components.TVButton
import nl.family7.tv.ui.theme.Family7Blue
import nl.family7.tv.ui.theme.Family7BlueDark
import nl.family7.tv.ui.theme.Family7Red
import nl.family7.tv.ui.theme.TextMuted
import nl.family7.tv.ui.theme.TextPrimary
import nl.family7.tv.ui.theme.TextSecondary

@Composable
fun LoginScreen(
    authRepo: Family7AuthRepository,
    onLoginSuccess: (UserSession) -> Unit
) {
    var username by remember { mutableStateOf(authRepo.getSavedUsername()) }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "Vul zowel uw e-mailadres als wachtwoord in."
            return
        }
        isLoading = true
        scope.launch {
            val result = authRepo.login(username, password, rememberMe)
            isLoading = false
            result.onSuccess { onLoginSuccess(it) }
                .onFailure { errorMessage = it.message }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Family7Blue, Family7BlueDark),
                    radius = 1200f
                )
            )
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Hero Branding
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 40.dp)
            ) {
                Family7Logo(height = 56.dp)

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Welkom bij Family7 TV",
                    color = TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Log in met uw Family7 account om direct toegang te krijgen tot de Live TV uitzending en de complete On Demand catalogus.",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                SignUpCard()
            }

            // Right Login Box
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF041B3B).copy(alpha = 0.9f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Inloggen",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Username / Email input
                TVInputField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    placeholder = "E-mailadres of gebruikersnaam",
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Family7Red)
                    },
                    keyboardType = KeyboardType.Email
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Password input
                TVInputField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = "Wachtwoord",
                    isPassword = true,
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Family7Red)
                    },
                    onImeAction = { submit() }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Remember Me
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { rememberMe = !rememberMe }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rememberMe) Family7Red else Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (rememberMe) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.scale(0.8f))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Onthoud mijn inloggegevens",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isLoading) {
                    CircularProgressIndicator(color = Family7Red, modifier = Modifier.height(36.dp).width(36.dp))
                } else {
                    TVButton(
                        text = "INLOGGEN",
                        onClick = { submit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun TVInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        singleLine = true,
        cursorBrush = SolidColor(Family7Red),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (onImeAction != null) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() }
        ),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isFocused) Color(0xFF0C2C58) else Color(0xFF061833))
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = if (isFocused) Family7Red else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leadingIcon?.invoke()
                if (leadingIcon != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Aanmeldkaart voor wie nog geen Family7 Plus heeft. Op een TV is typen lastig,
 * dus de QR-code brengt de kijker met de telefoon direct naar het inschrijfformulier.
 */
@Composable
private fun SignUpCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.qr_family7_plus),
            contentDescription = "QR-code naar $SIGN_UP_URL",
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(18.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Nog geen Family7 Plus?",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Family7Red)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "€ 3 PER MAAND",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "eerste 10 dagen gratis",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Scan de code met uw telefoon om lid te worden, of ga naar",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Text(
                text = SIGN_UP_URL,
                color = Family7Red,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private const val SIGN_UP_URL = "family7.nl/plus/user/register"
