package com.xiappdesign.family7.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiappdesign.family7.core.data.EpisodeItem
import com.xiappdesign.family7.core.data.Family7VideoRepository
import com.xiappdesign.family7.core.data.ProgramDetail
import com.xiappdesign.family7.core.data.ProgramItem
import com.xiappdesign.family7.tv.ui.components.Family7Mark
import com.xiappdesign.family7.tv.ui.components.SkeletonProgramDetail
import com.xiappdesign.family7.tv.ui.components.TVButton
import com.xiappdesign.family7.tv.ui.components.TVEpisodeCard
import com.xiappdesign.family7.tv.ui.theme.Family7BlueDark
import com.xiappdesign.family7.tv.ui.theme.Family7Red
import com.xiappdesign.family7.tv.ui.theme.TextPrimary
import com.xiappdesign.family7.tv.ui.theme.TextSecondary

@Composable
fun ProgramDetailScreen(
    programItem: ProgramItem,
    videoRepo: Family7VideoRepository,
    isInMyList: Boolean,
    onToggleMyList: (ProgramDetail, Boolean) -> Unit,
    onPlayEpisode: (EpisodeItem, ProgramDetail) -> Unit,
    onBack: () -> Unit
) {
    // Eerder geopende programma's komen meteen uit de cache; anders een laadscherm.
    var programDetail by remember(programItem.slug) {
        mutableStateOf(videoRepo.cachedDetail(programItem.slug))
    }
    var isLoading by remember(programItem.slug) { mutableStateOf(programDetail == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingInList by remember(programItem.slug) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(programItem.slug) {
        if (programDetail == null) isLoading = true
        val res = videoRepo.getProgramDetail(programItem.slug)
        res.onSuccess {
            programDetail = it
            isLoading = false
        }.onFailure {
            errorMessage = it.message
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Family7BlueDark)
    ) {
        if (isLoading) {
            SkeletonProgramDetail()
        } else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Family7Mark(size = 64.dp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Kon dit programma niet laden.", color = TextPrimary, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(errorMessage!!, color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    TVButton(text = "TERUG", onClick = onBack)
                }
            }
        } else if (programDetail != null) {
            val detail = programDetail!!

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                // Program Header Hero
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        AsyncImage(
                            model = detail.posterUrl.ifEmpty { programItem.thumbnailUrl },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Dual Gradients
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Family7BlueDark.copy(alpha = 0.95f),
                                            Family7BlueDark.copy(alpha = 0.65f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Family7BlueDark),
                                        startY = 180f
                                    )
                                )
                        )

                        // Text & Buttons
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(horizontal = 48.dp)
                                .fillMaxWidth(0.7f)
                        ) {
                            TVButton(
                                text = "TERUG",
                                onClick = onBack,
                                isPrimary = false,
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = detail.title,
                                color = TextPrimary,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (detail.description.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = detail.description,
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 3,
                                    lineHeight = 20.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val firstEpisode = detail.seasons.firstOrNull()?.episodes?.firstOrNull()
                                if (firstEpisode != null) {
                                    TVButton(
                                        text = if (firstEpisode.episodeNumber.isBlank()) "AFSPELEN" else "AFSPELEN (Afl. ${firstEpisode.episodeNumber})",
                                        onClick = { onPlayEpisode(firstEpisode, detail) },
                                        leadingIcon = {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                        }
                                    )
                                }

                                // De site kent de lijststatus; na een wijziging telt
                                // de status die deze app zojuist heeft doorgegeven.
                                val inList = pendingInList ?: (isInMyList || detail.isInMyList)
                                if (detail.nodeId.isNotEmpty()) {
                                    TVButton(
                                        text = if (inList) "IN MIJN LIJST" else "MIJN LIJST",
                                        onClick = {
                                            pendingInList = !inList
                                            onToggleMyList(detail, !inList)
                                        },
                                        isPrimary = false,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (inList) Icons.Default.Check else Icons.Default.Add,
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Episodes Rows per Season
                items(detail.seasons) { season ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = season.title,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 42.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(season.episodes) { episode ->
                                TVEpisodeCard(
                                    episode = episode,
                                    onClick = { onPlayEpisode(episode, detail) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
