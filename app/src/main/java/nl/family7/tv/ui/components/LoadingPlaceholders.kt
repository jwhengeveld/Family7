package nl.family7.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Een rustig doorlopend glanseffect, zodat een laadscherm de uiteindelijke
 * indeling al toont in plaats van een leeg vlak met een draaiend wieltje.
 */
// De grondtoon moet duidelijk lichter zijn dan de paginakleur (#031A38), anders
// is een plaatshouder niet van een lege pagina te onderscheiden.
private val SkeletonBase = Color(0xFF0C2B52)
private val SkeletonHighlight = Color(0xFF1B4C82)

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1400f,
        targetValue = 2600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    return Brush.linearGradient(
        colors = listOf(SkeletonBase, SkeletonHighlight, SkeletonBase),
        start = Offset(offset, 0f),
        end = Offset(offset + 1200f, 0f)
    )
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush())
    )
}

/** Plaatshouder voor de uitgelichte banner bovenaan het startscherm. */
@Composable
fun SkeletonHero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxSize(), cornerRadius = 0.dp)
        Column(
            modifier = Modifier
                .padding(start = 36.dp, top = 90.dp)
        ) {
            SkeletonBlock(modifier = Modifier.width(90.dp).height(18.dp))
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(modifier = Modifier.width(320.dp).height(32.dp))
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(modifier = Modifier.width(150.dp).height(44.dp))
                SkeletonBlock(modifier = Modifier.width(130.dp).height(44.dp))
            }
        }
    }
}

/** Plaatshouder voor een rij programmakaarten. */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    cardCount: Int = 5,
    cardWidth: Dp = 240.dp
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        SkeletonBlock(
            modifier = Modifier
                .padding(start = 36.dp, bottom = 12.dp)
                .width(180.dp)
                .height(20.dp)
        )
        Row(
            modifier = Modifier.padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(cardCount) {
                Column(modifier = Modifier.width(cardWidth)) {
                    SkeletonBlock(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonBlock(modifier = Modifier.width(cardWidth * 0.7f).height(14.dp))
                }
            }
        }
    }
}

/** Volledig startscherm in laadtoestand. */
@Composable
fun SkeletonHomeScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SkeletonHero()
        repeat(2) { SkeletonRow() }
    }
}

/** Rasterplaatshouder voor A-Z, zoeken, kids en mijn lijst. */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    columns: Int = 4,
    cardWidth: Dp = 240.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(columns) {
                    Column(modifier = Modifier.width(cardWidth)) {
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            cornerRadius = 10.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonBlock(modifier = Modifier.width(cardWidth * 0.7f).height(14.dp))
                    }
                }
            }
        }
    }
}

/** Plaatshouder voor de programmapagina. */
@Composable
fun SkeletonProgramDetail(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            SkeletonBlock(modifier = Modifier.fillMaxSize(), cornerRadius = 0.dp)
            Column(modifier = Modifier.padding(start = 48.dp, top = 110.dp)) {
                SkeletonBlock(modifier = Modifier.width(110.dp).height(40.dp))
                Spacer(modifier = Modifier.height(16.dp))
                SkeletonBlock(modifier = Modifier.width(360.dp).height(34.dp))
                Spacer(modifier = Modifier.height(12.dp))
                SkeletonBlock(modifier = Modifier.width(460.dp).height(16.dp))
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        SkeletonRow(cardWidth = 280.dp, cardCount = 4)
    }
}

/**
 * Kleine, terughoudende laadindicatie voor plekken waar geen plaatshouder past,
 * met de vaste huisstijl van de app.
 */
@Composable
fun Family7LoadingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Family7Mark(size = 56.dp)
        if (label != null) {
            Spacer(modifier = Modifier.height(14.dp))
            SkeletonBlock(modifier = Modifier.width(160.dp).height(12.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(shimmerBrush())
            )
        }
    }
}
