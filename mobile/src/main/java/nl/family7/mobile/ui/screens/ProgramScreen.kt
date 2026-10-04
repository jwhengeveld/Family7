package nl.family7.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.family7.core.data.EpisodeItem
import nl.family7.core.data.ProgramDetail
import nl.family7.core.data.ProgramItem
import nl.family7.core.data.displayLabel
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.ui.ProgramViewModel
import nl.family7.mobile.ui.components.FullScreenError
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.components.bannerHeight
import nl.family7.mobile.ui.components.ShimmerBox
import nl.family7.mobile.ui.components.StatusBanner
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(
    viewModel: ProgramViewModel,
    /** Wat er al bekend is van de kaart waarop getikt werd, voor een meteen gevulde kop. */
    preview: ProgramItem,
    myList: List<ProgramItem>,
    castAvailable: Boolean,
    castDeviceName: String?,
    onPlay: (EpisodeItem, ProgramDetail) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.detail.state.collectAsStateWithLifecycle()
    val detail = state.data
    // De lijst van het account is leidend; is die nog niet geladen, dan wat de
    // programmapagina van Family7 zelf aangeeft.
    val inMyList = if (myList.isNotEmpty()) myList.any { it.slug == preview.slug } else detail?.isInMyList == true
    // De knop reageert meteen; Family7 bevestigt op de achtergrond.
    var optimisticInList by remember(inMyList) { mutableStateOf(inMyList) }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.detail.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Header(
                        title = detail?.title ?: preview.title,
                        imageUrl = detail?.posterUrl?.takeIf { it.isNotBlank() } ?: preview.thumbnailUrl
                    )
                }
                item(key = "banner") {
                    StatusBanner(
                        isOffline = state.isOffline,
                        error = state.error.takeIf { detail != null },
                        isRefreshing = false,
                        onRetry = { viewModel.detail.refresh() }
                    )
                }

                when {
                    detail == null && state.showFullError -> item(key = "error") {
                        FullScreenError(state.error.orEmpty(), onRetry = { viewModel.detail.refresh() })
                    }

                    detail == null -> item(key = "skeleton") { DetailSkeleton() }

                    else -> {
                        val episodes = detail.seasons.flatMap { it.episodes }
                        item(key = "actions") {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                if (detail.category.isNotBlank()) {
                                    Text(detail.category, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    Spacer(Modifier.height(8.dp))
                                }
                                Row {
                                    episodes.firstOrNull()?.let { first ->
                                        Button(
                                            onClick = { onPlay(first, detail) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Family7Red),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                if (castDeviceName != null) Icons.Filled.Cast else Icons.Filled.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (castDeviceName != null) "Afspelen op $castDeviceName" else "Afspelen",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (detail.nodeId.isNotBlank()) {
                                        OutlinedButton(onClick = {
                                            optimisticInList = !optimisticInList
                                            viewModel.setInMyList(detail.nodeId, optimisticInList)
                                        }) {
                                            Icon(
                                                if (optimisticInList) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (optimisticInList) "In mijn lijst" else "Mijn lijst")
                                        }
                                    }
                                }
                                if (detail.description.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(detail.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                }
                            }
                        }

                        detail.seasons.forEach { season ->
                            item(key = "season-${season.seasonNumber}") {
                                Text(
                                    season.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                                )
                            }
                            items(season.episodes, key = { "ep-" + it.videoSlug }) { episode ->
                                EpisodeRow(episode = episode, onClick = { onPlay(episode, detail) })
                            }
                        }

                        if (episodes.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "Er staan nog geen afleveringen van dit programma online.",
                                    color = TextSecondary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Een zachte schaduw onder de statusbalk, zodat de klok leesbaar blijft
        // als de tekst eronder door schuift.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
        )

        // Terug en casten liggen over de afbeelding heen.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp)
                .fillMaxWidth()
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
            }
            Spacer(Modifier.weight(1f))
            if (castAvailable) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) { CastButton() }
            }
        }
    }
}

@Composable
private fun Header(title: String, imageUrl: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(bannerHeight())
    ) {
        RemoteImage(imageUrl, title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.4f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background
                    )
                )
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        )
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            RemoteImage(episode.thumbnailUrl, null, Modifier.fillMaxSize())
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(4.dp)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                episode.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Family7 zet bij sommige series "00 m"; een nul-duur zegt niets.
            if (episode.duration.any { it in '1'..'9' }) {
                Text(episode.duration, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (episode.description.isNotBlank()) {
                Text(
                    episode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DetailSkeleton() {
    Column(Modifier.padding(16.dp)) {
        ShimmerBox(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
        )
        Spacer(Modifier.height(16.dp))
        repeat(4) {
            Row(Modifier.padding(vertical = 8.dp)) {
                ShimmerBox(
                    Modifier
                        .width(132.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                )
                Column(Modifier.padding(start = 12.dp)) {
                    ShimmerBox(
                        Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(
                        Modifier
                            .width(80.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}
