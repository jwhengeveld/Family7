package nl.family7.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hoe een lijst scrolt als de focus van de afstandsbediening naar een element
 * verhuist.
 *
 * Standaard scrolt Compose "net genoeg" om het element zichtbaar te maken.
 * Onze kaarten worden bij focus groter (met een rode rand en schaduw), dus dat
 * "net genoeg" verschilt per stap: de rij schuift telkens een ander stukje op,
 * en de rode rand valt soms buiten beeld.
 *
 * Deze regel houdt het rustig: staat het element (vergroot) ruim binnen beeld,
 * dan beweegt er niets. Pas als het te dicht bij de rand komt, scrolt de lijst
 * precies zo ver dat het element op een vaste afstand van die rand staat. Elke
 * stap is daardoor gelijk, en de focusrand is altijd helemaal te zien.
 */
@OptIn(ExperimentalFoundationApi::class)
private class TvBringIntoViewSpec(
    private val leadingMarginPx: Float,
    private val trailingMarginPx: Float,
    /**
     * Kleine afwijkingen negeren: een kaart die bij focus groter wordt, meldt
     * tijdens die animatie telkens een iets andere maat. Zonder speling kruipt
     * de pagina daardoor bij elke stap een paar pixels mee.
     */
    private val slackPx: Float
) : BringIntoViewSpec {

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leadingMarginPx
        val end = containerSize - trailingMarginPx
        val trailing = offset + size
        return when {
            // Past niet eens tussen de marges: dan met de voorkant op de marge.
            size > end - start -> if (kotlin.math.abs(offset - start) <= slackPx) 0f else offset - start
            offset < start -> if (start - offset <= slackPx) 0f else offset - start
            trailing > end -> if (trailing - end <= slackPx) 0f else trailing - end
            else -> 0f
        }
    }
}

/**
 * Geeft de lijsten binnen [content] de rustige tv-scrolregel. [leading] en
 * [trailing] zijn de afstanden tot de randen (links/rechts voor een rij,
 * boven/onder voor een kolom); ze moeten ruimte laten voor de vergroting bij
 * focus. Lijsten die dieper genest zijn, kunnen hun eigen regel geven.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusScrolling(
    leading: Dp = 48.dp,
    trailing: Dp = 48.dp,
    /**
     * Speling voor kolommen: daar meldt een rij die horizontaal van focus
     * wisselt telkens een iets andere hoogte. Rijen zelf gebruiken 0, zodat de
     * gefocuste kaart aan de rand steeds op precies dezelfde plek staat.
     */
    slack: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val spec = remember(density, leading, trailing, slack) {
        with(density) { TvBringIntoViewSpec(leading.toPx(), trailing.toPx(), slack.toPx()) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
