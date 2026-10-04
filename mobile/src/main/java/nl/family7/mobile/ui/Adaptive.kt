package nl.family7.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablet of breed scherm (vanaf 600 dp, de grens die Material voor tablets
 * aanhoudt). Daar volgt de app de indeling van de TV-app: een zijbalk links,
 * een brede uitgelichte kop en grotere kaarten.
 */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** Breedte van een programmakaart in een rij: op tablets zo groot als op de TV. */
@Composable
fun programCardWidth(): Dp = if (isWideScreen()) 240.dp else 168.dp

/** Kleinste tegelbreedte in een raster. */
@Composable
fun gridMinCellWidth(): Dp = if (isWideScreen()) 220.dp else 160.dp
