package com.xiappdesign.family7.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashBlue = Color(0xFF031A38)
private val SplashBlueLight = Color(0xFF0A3F86)
private val SplashBlueDeep = Color(0xFF020D24)
private val Tagline = Color(0xFFB0C4DE)

/**
 * Breedte van het embleem op het systeem-splashscherm van Android 12+
 * (splash_emblem.xml: 774 eenheden breed, geschaald met 88/412). De animatie
 * begint op precies die maat en plek, zodat de overgang naadloos is.
 */
private val SystemSplashEmblemWidth = (774f * 88f / 412f).dp

/**
 * Het geanimeerde splashscherm van Family7.
 *
 * Begint waar het systeem-splashscherm ophoudt (het embleem op effen blauw)
 * en onthult dan het merk: het embleem schuift omhoog, er komt een zachte
 * gloed achter, en "Family7" met de ondertitel verschijnt eronder. De app laadt
 * intussen gewoon door; pas als [ready] waar is én de onthulling klaar is,
 * vervaagt het scherm en volgt [onFinished]. Duurt het laden langer, dan
 * verschijnen er na een tel rustig pulserende puntjes.
 */
@Composable
fun Family7Splash(
    ready: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    tagline: String = "De christelijke familiezender"
) {
    val emblemLift = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val subtitle = remember { Animatable(0f) }
    val waiting = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val currentReady by rememberUpdatedState(ready)
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        coroutineScope {
            delay(120)
            listOf(
                async { emblemLift.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) },
                async { delay(80); glow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) },
                async { delay(320); text.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) },
                async { delay(520); subtitle.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
            ).awaitAll()
        }
        // Nog niet klaar met laden: na een korte pauze de puntjes tonen.
        val dots = launch { delay(500); waiting.animateTo(1f, tween(400)) }
        while (!currentReady) delay(50)
        dots.cancel()
        exit.animateTo(0f, tween(320, easing = LinearEasing))
        currentOnFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(exit.value)
            .background(SplashBlue),
        contentAlignment = Alignment.Center
    ) {
        // Verloop en gloed komen er pas bij na de start, zodat het eerste beeld
        // gelijk is aan het effen systeem-splashscherm.
        Box(
            Modifier
                .fillMaxSize()
                .alpha(glow.value)
                .background(
                    Brush.radialGradient(
                        0f to SplashBlueLight,
                        0.55f to SplashBlue,
                        1f to SplashBlueDeep
                    )
                )
        )

        val lift = emblemLift.value
        val emblemOffset = (-58).dp * lift
        val emblemScale = 1f - 0.2f * lift

        // Zachte gloed achter het embleem.
        Box(
            Modifier
                .offset(y = emblemOffset)
                .size(SystemSplashEmblemWidth * 1.6f)
                .alpha(0.55f * glow.value)
                .scale(0.8f + 0.2f * glow.value)
                .background(
                    Brush.radialGradient(listOf(Color(0x664CA3FF), Color(0x00031A38))),
                    CircleShape
                )
        )

        Image(
            painter = painterResource(R.drawable.family7_mark),
            contentDescription = "Family7",
            modifier = Modifier
                .offset(y = emblemOffset)
                .width(SystemSplashEmblemWidth)
                // Vaste verhouding: anders houdt Image de eigen hoogte van de
                // drawable aan en schaalt hij het embleem daarbinnen veel te klein.
                .aspectRatio(774f / 689f)
                .scale(emblemScale)
        )

        Image(
            painter = painterResource(R.drawable.family7_text),
            contentDescription = null,
            modifier = Modifier
                .offset(y = 62.dp + 14.dp * (1f - text.value))
                .width(150.dp)
                .aspectRatio(3643.6f / 944f)
                .graphicsLayer { alpha = text.value }
        )

        BasicText(
            text = tagline,
            style = TextStyle(color = Tagline, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
            modifier = Modifier
                .offset(y = 106.dp + 10.dp * (1f - subtitle.value))
                .graphicsLayer { alpha = subtitle.value }
        )

        LoadingDots(
            modifier = Modifier
                .offset(y = 160.dp)
                .alpha(waiting.value)
        )
    }
}

@Composable
private fun LoadingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        listOf(Color(0xFFE8000F), Color(0xFF35C815), Color(0xFF1C3FE0)).forEachIndexed { index, color ->
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(520, delayMillis = index * 160, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .scale(0.7f + 0.3f * pulse)
                    .alpha(pulse)
                    .background(color, CircleShape)
            )
        }
    }
}
