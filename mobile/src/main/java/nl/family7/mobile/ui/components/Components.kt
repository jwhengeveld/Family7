package nl.family7.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import nl.family7.core.data.ProgramItem
import nl.family7.brand.R
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextSecondary

/**
 * Hoogte voor een brede kopafbeelding: 16:9 over de breedte, maar nooit meer
 * dan de helft van het scherm, zodat er liggend ook nog inhoud zichtbaar is.
 */
@Composable
fun bannerHeight(horizontalPadding: Dp = 0.dp): Dp {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val byWidth = (config.screenWidthDp.dp - horizontalPadding * 2) * 9f / 16f
    val byHeight = config.screenHeightDp.dp * 0.5f
    return minOf(byWidth, byHeight)
}

@Composable
fun Family7Logo(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Image(
        painter = painterResource(R.drawable.family7_logo),
        contentDescription = "Family7",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height)
    )
}

/**
 * Een afbeelding met een plaatshouder in dezelfde vorm terwijl hij laadt, en
 * het Family7-beeldmerk als hij niet te laden is: nooit een leeg gat.
 */
@Composable
fun RemoteImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url.takeIf { it.isNotBlank() },
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { ShimmerBox(Modifier.fillMaxSize()) },
        error = {
            Box(Modifier.fillMaxSize().background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.family7_mark),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    )
}

@Composable
fun ProgramCard(
    program: ProgramItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 168.dp
) {
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            RemoteImage(
                url = program.thumbnailUrl,
                contentDescription = program.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
            )
            if (program.badge.isNotBlank()) {
                Text(
                    text = program.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Family7Red)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = program.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp, start = 2.dp, end = 2.dp)
        )
    }
}

/** Een glanzende plaatshouder in de vorm van de inhoud die eraan komt. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-offset"
    )
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(DarkSurfaceVariant, DarkSurfaceVariant.copy(alpha = 0.45f), DarkSurfaceVariant),
                start = androidx.compose.ui.geometry.Offset(offset - 300f, 0f),
                end = androidx.compose.ui.geometry.Offset(offset, 300f)
            )
        )
    )
}

@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        ShimmerBox(
            Modifier
                .padding(horizontal = 16.dp)
                .width(140.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            repeat(3) {
                ShimmerBox(
                    Modifier
                        .width(168.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

/** Foutscherm voor als er niets te tonen is. */
@Composable
fun FullScreenError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center, color = TextSecondary)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) {
            Text("Opnieuw proberen")
        }
    }
}

/**
 * Rustige melding boven inhoud die er al staat: offline, of verversen
 * mislukt. De inhoud zelf blijft bruikbaar.
 */
@Composable
fun StatusBanner(isOffline: Boolean, error: String?, isRefreshing: Boolean, onRetry: () -> Unit) {
    Column {
        AnimatedVisibility(visible = isRefreshing, enter = expandVertically(), exit = shrinkVertically()) {
            LinearProgressIndicator(color = Family7Red, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        val text = when {
            isOffline -> "Geen internet. U ziet wat er het laatst geladen is."
            error != null -> error
            else -> null
        }
        AnimatedVisibility(visible = text != null, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Text(
                    text = text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                if (!isOffline) {
                    TextButton(onClick = onRetry) { Text("Opnieuw") }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}
