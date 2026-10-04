package nl.family7.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.family7.core.data.ProgramItem
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.ui.GridViewModel
import nl.family7.mobile.ui.LoadState
import nl.family7.mobile.ui.gridMinCellWidth
import nl.family7.mobile.ui.MyListViewModel
import nl.family7.mobile.ui.components.FullScreenError
import nl.family7.mobile.ui.components.ProgramCard
import nl.family7.mobile.ui.components.ShimmerBox
import nl.family7.mobile.ui.components.StatusBanner
import nl.family7.mobile.ui.theme.TextSecondary

/** Programma-overzicht: Kids, A-Z of de "Alles"-pagina van een rij. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(
    title: String,
    viewModel: GridViewModel,
    castAvailable: Boolean,
    emptyMessage: String,
    onOpenProgram: (ProgramItem) -> Unit,
    onBack: (() -> Unit)?
) {
    val state by viewModel.programs.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenBar(title, castAvailable, onBack)
        ProgramGrid(
            state = state,
            emptyMessage = emptyMessage,
            onRetry = { viewModel.programs.refresh() },
            onOpenProgram = onOpenProgram
        )
    }
}

/**
 * Zoeken filtert de complete A-Z-lijst op het toestel zelf: geen wachttijd
 * per letter, en ook zonder netwerk doorzoekbaar zodra de lijst er één keer is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: GridViewModel,
    castAvailable: Boolean,
    onOpenProgram: (ProgramItem) -> Unit
) {
    val state by viewModel.programs.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(state.data, query) {
        val all = state.data.orEmpty()
        val q = query.trim().lowercase()
        if (q.isEmpty()) all
        else all.filter { it.title.lowercase().contains(q) || it.slug.contains(q.replace(' ', '-')) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenBar("Zoeken", castAvailable, onBack = null)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Zoek een programma") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Wissen") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        ProgramGrid(
            state = state.copy(data = state.data?.let { filtered }),
            emptyMessage = if (query.isBlank()) "Er zijn geen programma's gevonden." else "Niets gevonden voor \"$query\".",
            onRetry = { viewModel.programs.refresh() },
            onOpenProgram = onOpenProgram
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    viewModel: MyListViewModel,
    castAvailable: Boolean,
    onOpenProgram: (ProgramItem) -> Unit
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Mijn lijst", castAvailable, onBack = null)
        ProgramGrid(
            state = LoadState(
                data = items.takeIf { it.isNotEmpty() || state.data != null },
                isLoading = state.isLoading,
                error = state.error,
                isOffline = state.isOffline
            ),
            emptyMessage = "Uw lijst is nog leeg. Open een programma en kies \"Mijn lijst\"; " +
                "die lijst deelt u met de website van Family7.",
            onRetry = viewModel::refresh,
            onOpenProgram = onOpenProgram
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenBar(title: String, castAvailable: Boolean, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                }
            }
        },
        actions = { if (castAvailable) CastButton() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramGrid(
    state: LoadState<List<ProgramItem>>,
    emptyMessage: String,
    onRetry: () -> Unit,
    onOpenProgram: (ProgramItem) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        StatusBanner(
            isOffline = state.isOffline,
            error = state.error.takeIf { state.data != null },
            isRefreshing = false,
            onRetry = onRetry
        )
        when {
            state.showFullError -> FullScreenError(state.error.orEmpty(), onRetry = onRetry)
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRetry,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = gridMinCellWidth()),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val data = state.data
                    when {
                        data == null -> items(8) {
                            ShimmerBox(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }

                        data.isEmpty() && !state.isLoading -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                emptyMessage,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }

                        else -> items(data, key = { it.slug }) { program ->
                            ProgramCard(program, onClick = { onOpenProgram(program) }, width = null)
                        }
                    }
                }
            }
        }
    }
}
