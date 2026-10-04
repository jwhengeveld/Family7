package nl.family7.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import nl.family7.core.data.GuideItem
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.playback.PlayRequest
import nl.family7.mobile.playback.PlaybackManager
import nl.family7.mobile.playback.PlaybackState
import nl.family7.mobile.ui.GuideViewModel
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.components.ShimmerBox
import nl.family7.mobile.ui.components.StatusBanner
import nl.family7.mobile.ui.isWideScreen
import nl.family7.mobile.ui.theme.DarkSurface
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextMuted
import nl.family7.mobile.ui.theme.TextSecondary

/**
 * Live tv zoals op de site: bovenaan een kleine speler met de uitzending, en
 * daaronder de programmagids (van de site) zodat te zien is wat wanneer komt.
 * De knop rechtsonder in de speler opent hem schermvullend.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    playback: PlaybackManager,
    viewModel: GuideViewModel,
    onFullscreen: () -> Unit,
    onOpenGuideItem: (GuideItem) -> Unit,
    onSearch: () -> Unit
) {
    val state by playback.state.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val nowMinutes by viewModel.nowMinutes.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    // Meteen live kijken. Speelt er al iets op de tv, dan verandert dit scherm
    // daar niets aan; dan is er een knop om live op de tv te zetten.
    LaunchedEffect(Unit) {
        val now = playback.state.value
        if (!now.isCasting && now.request !is PlayRequest.Live) playback.play(PlayRequest.Live)
    }

    // Weg van dit scherm stopt de kleine speler, behalve als hij groot opengaat.
    val goingFullscreen = remember { booleanArrayOf(false) }
    DisposableEffect(playback) {
        playback.playerScreenVisible = true
        onDispose {
            playback.playerScreenVisible = false
            if (!goingFullscreen[0] && activity?.isChangingConfigurations != true) playback.stopLocal()
        }
    }
    val openFullscreen by rememberUpdatedState {
        goingFullscreen[0] = true
        onFullscreen()
    }

    // Naar de achtergrond: pauzeren; een cast-sessie speelt door op de tv.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !playback.state.value.isCasting) playback.pause()
            if (event == Lifecycle.Event.ON_START && !playback.state.value.isCasting) player?.play()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isToday = days.getOrNull(selected)?.label == "Vandaag"
    val items = guide.data.orEmpty()
    val currentIndex = if (isToday) items.indexOfLast { it.startMinutes <= nowMinutes } else -1

    val playerArea: @Composable (Modifier) -> Unit = { modifier ->
        LivePlayerBox(
            state = state,
            player = player,
            onFullscreen = { openFullscreen() },
            onPlayOnTv = { playback.play(PlayRequest.Live) },
            modifier = modifier
        )
    }
    val nowCard: @Composable () -> Unit = {
        NowOnAir(item = items.getOrNull(currentIndex), next = items.getOrNull(currentIndex + 1), nowMinutes = nowMinutes, isToday = isToday)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Live tv") },
            actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Zoeken") }
                if (state.castAvailable) CastButton()
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        val landscapeWide = isWideScreen() && LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        if (landscapeWide) {
            // Tablet liggend: speler links, gids rechts, zoals op tv.
            Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.4f)) {
                    playerArea(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                    nowCard()
                }
                Guide(
                    days = days.map { it.label }, selected = selected, onSelect = viewModel::select,
                    entries = items, isLoading = guide.data == null && guide.isLoading, error = guide.error,
                    isOffline = guide.isOffline, onRetry = viewModel::refresh,
                    currentIndex = currentIndex, nowMinutes = nowMinutes, onOpen = onOpenGuideItem,
                    header = null, modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            playerArea(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Guide(
                days = days.map { it.label }, selected = selected, onSelect = viewModel::select,
                entries = items, isLoading = guide.data == null && guide.isLoading, error = guide.error,
                isOffline = guide.isOffline, onRetry = viewModel::refresh,
                currentIndex = currentIndex, nowMinutes = nowMinutes, onOpen = onOpenGuideItem,
                header = nowCard, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayerBox(
    state: PlaybackState,
    player: Player?,
    onFullscreen: () -> Unit,
    onPlayOnTv: () -> Unit,
    modifier: Modifier
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            state.isCasting -> {
                // De uitzending of iets anders speelt op de tv; hier de stand daarvan.
                RemoteImage(state.artworkUrl, null, Modifier.fillMaxSize().alpha(0.35f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Cast, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.isLive) "Live tv speelt op ${state.castDeviceName ?: "de tv"}"
                        else "Verbonden met ${state.castDeviceName ?: "de tv"}",
                        color = Color.White, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.isLive) {
                        Button(onClick = onFullscreen, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) { Text("Bediening") }
                    } else {
                        Button(onClick = onPlayOnTv, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) { Text("Live op de tv") }
                    }
                }
            }

            else -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            // Live: geen spoelknoppen; de stream is altijd "nu".
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                            controllerShowTimeoutMs = 3_000
                        }
                    },
                    update = { view ->
                        if (view.player !== player) view.player = player
                        // De knop "schermvullend" van de speler zelf, rechtsonder.
                        view.setFullscreenButtonClickListener { onFullscreen() }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                if (player == null || (state.isLoading && player.playbackState == Player.STATE_IDLE)) {
                    CircularProgressIndicator(color = Family7Red)
                }
                if (state.error != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).padding(16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(state.error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onPlayOnTv, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) { Text("Opnieuw proberen") }
                    }
                }
            }
        }
    }
}

/** "Nu op Family7" met wat er daarna komt, onder de speler. */
@Composable
private fun NowOnAir(item: GuideItem?, next: GuideItem?, nowMinutes: Int, isToday: Boolean) {
    if (item == null || !isToday) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Family7Red))
            Spacer(Modifier.width(6.dp))
            Text("NU LIVE", style = MaterialTheme.typography.labelMedium, color = Family7Red, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(item.start, next?.start).joinToString(" – "),
                style = MaterialTheme.typography.labelMedium, color = TextSecondary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.episode.isNotBlank()) {
            Text(item.episode, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        progress(item, next, nowMinutes)?.let { fraction ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                color = Family7Red,
                trackColor = DarkSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
            )
        }
        if (next != null) {
            Spacer(Modifier.height(6.dp))
            Text("Straks: ${next.start}  ${next.title}", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Guide(
    days: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    entries: List<GuideItem>,
    isLoading: Boolean,
    error: String?,
    isOffline: Boolean,
    onRetry: () -> Unit,
    currentIndex: Int,
    nowMinutes: Int,
    onOpen: (GuideItem) -> Unit,
    header: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    val headerCount = if (header != null) 2 else 1
    // Vandaag: de uitzending van nu bovenaan in beeld, niet de nacht ervoor.
    LaunchedEffect(selected, entries.isNotEmpty()) {
        val target = if (currentIndex >= 0) headerCount + (currentIndex - 1).coerceAtLeast(0) else 0
        if (entries.isNotEmpty()) listState.scrollToItem(target)
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (header != null) item(key = "now") { header() }
        // De dagen blijven bovenaan staan tijdens het scrollen.
        stickyHeader(key = "days") {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                StatusBanner(isOffline = isOffline, error = error.takeIf { entries.isNotEmpty() }, isRefreshing = false, onRetry = onRetry)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    itemsIndexed(days) { index, label ->
                        FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(label) })
                    }
                }
            }
        }
        when {
            entries.isEmpty() && isLoading -> items(6) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ShimmerBox(Modifier.width(44.dp).height(16.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(12.dp))
                    ShimmerBox(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp)))
                }
            }

            entries.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "Voor deze dag staat er nog niets in de gids.", color = TextSecondary)
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) { Text("Opnieuw proberen") }
                    }
                }
            }

            else -> itemsIndexed(entries, key = { index, item -> "$index-${item.start}" }) { index, item ->
                GuideRow(
                    item = item,
                    isNow = index == currentIndex,
                    isPast = currentIndex >= 0 && index < currentIndex,
                    progress = if (index == currentIndex) progress(item, entries.getOrNull(index + 1), nowMinutes) else null,
                    onClick = if (item.programSlug.isNotBlank()) ({ onOpen(item) }) else null
                )
            }
        }
    }
}

@Composable
private fun GuideRow(item: GuideItem, isNow: Boolean, isPast: Boolean, progress: Float?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (isNow) DarkSurface else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (isPast) 0.55f else 1f),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.width(52.dp)) {
            Text(item.start, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (isNow) Family7Red else MaterialTheme.colorScheme.onBackground)
            if (isNow) Text("NU", style = MaterialTheme.typography.labelSmall, color = Family7Red, fontWeight = FontWeight.Bold)
        }
        if (item.imageUrl.isNotBlank()) {
            RemoteImage(item.imageUrl, null, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.episode.isNotBlank()) {
                Text(item.episode, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = Family7Red,
                    trackColor = DarkSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** Hoe ver de uitzending is, tot het begin van de volgende (anders een half uur). */
private fun progress(item: GuideItem, next: GuideItem?, nowMinutes: Int): Float? {
    val end = next?.startMinutes?.takeIf { it > item.startMinutes } ?: (item.startMinutes + 30)
    if (nowMinutes < item.startMinutes || nowMinutes >= end) return null
    return (nowMinutes - item.startMinutes).toFloat() / (end - item.startMinutes)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
