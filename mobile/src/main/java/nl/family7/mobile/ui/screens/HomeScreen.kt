package nl.family7.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import nl.family7.core.data.CategoryRow
import nl.family7.core.data.LiveStreamInfo
import nl.family7.core.data.ProgramItem
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.ui.HomeViewModel
import nl.family7.mobile.ui.components.Family7Logo
import nl.family7.mobile.ui.components.FullScreenError
import nl.family7.mobile.ui.components.ProgramCard
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.components.bannerHeight
import nl.family7.mobile.ui.components.SectionTitle
import nl.family7.mobile.ui.components.SkeletonRow
import nl.family7.mobile.ui.components.StatusBanner
import nl.family7.mobile.ui.splitFeatured
import nl.family7.mobile.ui.isWideScreen
import nl.family7.mobile.ui.programCardWidth
import nl.family7.mobile.ui.theme.Family7BlueDark
import androidx.compose.material.icons.filled.LiveTv
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    castAvailable: Boolean,
    onOpenProgram: (ProgramItem) -> Unit,
    onOpenRow: (CategoryRow) -> Unit,
    onOpenKids: () -> Unit,
    onOpenAZ: () -> Unit,
    onWatchLive: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.rows.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    // Stil verversen zolang dit scherm zichtbaar is; gepauzeerd op de achtergrond.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refreshWhileVisible() }
    }

    // Op een tablet ligt de titelbalk doorzichtig over de kop, zoals op de TV.
    val topBar: @Composable (transparent: Boolean) -> Unit = { transparent ->
        TopAppBar(
            // Op een tablet staat het embleem al in de zijbalk, zoals op de TV.
            title = { if (!isWideScreen()) Family7Logo(height = 30.dp) },
            actions = {
                // Zoeken als rond knopje rechtsboven, zoals gebruikelijk op Android.
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Zoeken") }
                if (castAvailable) CastButton()
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Meer")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Uitloggen") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                            onClick = { menuOpen = false; onLogout() }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background
            )
        )
    }
    val wide = isWideScreen()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!wide) topBar(false)
            StatusBanner(
                isOffline = state.isOffline,
                error = state.error.takeIf { state.data != null },
                isRefreshing = false,
                onRetry = viewModel::refresh
            )

            when {
                state.showSkeleton -> HomeSkeleton()
                state.showFullError -> FullScreenError(state.error.orEmpty(), onRetry = viewModel::refresh)
                else -> {
                    val (featured, rows) = state.data.orEmpty().splitFeatured()
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            featured?.let { program ->
                                item(key = "hero") {
                                    if (isWideScreen()) {
                                        WideHero(program, onWatch = { onOpenProgram(program) }, onLive = onWatchLive)
                                    } else {
                                        HeroCard(program, onClick = { onOpenProgram(program) })
                                    }
                                }
                            }
                            item(key = "live") { LiveCard(live, onClick = onWatchLive) }
                            item(key = "chips") {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    AssistChip(
                                        onClick = onOpenKids,
                                        label = { Text("Kids") },
                                        leadingIcon = { Icon(Icons.Filled.ChildCare, null, Modifier.size(18.dp)) }
                                    )
                                    AssistChip(
                                        onClick = onOpenAZ,
                                        label = { Text("Alle programma's") },
                                        leadingIcon = { Icon(Icons.Filled.SortByAlpha, null, Modifier.size(18.dp)) }
                                    )
                                }
                            }
                            items(rows, key = { "row-" + it.id }) { row ->
                                ProgramRow(row, onOpenProgram = onOpenProgram, onOpenRow = { onOpenRow(row) })
                            }
                        }
                    }
                }
            }
        }
        if (wide) topBar(true)
    }
}

@Composable
private fun HeroCard(program: ProgramItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(bannerHeight(horizontalPadding = 16.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        RemoteImage(program.thumbnailUrl, program.title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text("UITGELICHT", style = MaterialTheme.typography.labelSmall, color = Family7Red, fontWeight = FontWeight.Bold)
            Text(
                program.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (program.description.isNotBlank()) {
                Text(
                    program.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Family7Red),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bekijken")
            }
        }
    }
}

/**
 * De uitgelichte kop op een tablet, zoals op de TV: de afbeelding over de
 * volle breedte, een verloop vanaf links, en titel, beschrijving en knoppen
 * op leesbare breedte.
 */
@Composable
private fun WideHero(program: ProgramItem, onWatch: () -> Unit, onLive: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clickable(onClick = onWatch)
    ) {
        RemoteImage(program.thumbnailUrl, program.title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Family7BlueDark.copy(alpha = 0.95f),
                        0.45f to Family7BlueDark.copy(alpha = 0.6f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Family7BlueDark))
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp, end = 48.dp)
                .fillMaxWidth(0.55f)
        ) {
            Text("UITGELICHT", style = MaterialTheme.typography.labelMedium, color = Family7Red, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(program.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (program.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(program.description, style = MaterialTheme.typography.bodyLarge, color = TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onWatch, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bekijken")
                }
                androidx.compose.material3.FilledTonalButton(onClick = onLive) {
                    Icon(Icons.Filled.LiveTv, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Live TV")
                }
            }
        }
    }
}

@Composable
private fun LiveCard(live: LiveStreamInfo?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Family7Red)
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text("Nu live op Family7", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                live?.currentProgram?.takeIf { it.isNotBlank() } ?: "Kijk de uitzending live",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            live?.timeRange?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "Live kijken", tint = Family7Red)
    }
}

@Composable
private fun ProgramRow(row: CategoryRow, onOpenProgram: (ProgramItem) -> Unit, onOpenRow: () -> Unit) {
    Column {
        SectionTitle(
            title = row.title,
            action = if (row.moreUrl.isNotBlank()) {
                { TextButton(onClick = onOpenRow) { Text("Alles") } }
            } else {
                null
            }
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(row.items, key = { it.slug }) { program ->
                ProgramCard(program, onClick = { onOpenProgram(program) }, width = programCardWidth())
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize()) {
        nl.family7.mobile.ui.components.ShimmerBox(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
        )
        repeat(3) { SkeletonRow() }
    }
}
