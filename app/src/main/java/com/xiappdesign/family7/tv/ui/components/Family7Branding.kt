package com.xiappdesign.family7.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiappdesign.family7.brand.R

/**
 * Het volledige Family7 woordmerk ("Family7"), als vector.
 * Bron: art/family7_logo.svg -> res/drawable/family7_logo.xml (verhouding 123:53).
 */
@Composable
fun Family7Logo(
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    Image(
        painter = painterResource(R.drawable.family7_logo),
        contentDescription = "Family7",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height)
    )
}

/**
 * Alleen het beeldmerk (de "7" in de bol), voor compacte plekken zoals de zijbalk.
 * Bron: art/family7_mark.svg -> res/drawable/family7_mark.xml (verhouding 360:326).
 */
@Composable
fun Family7Mark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(R.drawable.family7_mark),
            contentDescription = "Family7",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    }
}
