package nl.family7.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import nl.family7.mobile.ui.SearchViewModel
import nl.family7.mobile.ui.suggestionsFor
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import nl.family7.core.data.CategoryRow
import nl.family7.mobile.ui.GridSource
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
 * Zoeken met suggesties, zoals gebruikelijk op Android: een zoekveld bovenin
 * met het toetsenbord meteen open, daaronder suggesties terwijl je typt. Zonder
 * zoekwoord staan de laatste zoekopdrachten er. Gefilterd wordt op het toestel
 * zelf, dus ook zonder netwerk zodra de lijst er één keer is.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenProgram: (ProgramItem) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.programs.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    val suggestions = remember(state.data, query) { state.data.orEmpty().suggestionsFor(query) }

    fun open(program: ProgramItem) {
        viewModel.remember(query.ifBlank { program.title })
        keyboard?.hide()
        onOpenProgram(program)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
            TextField(
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
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    suggestions.firstOrNull()?.let(::open) ?: keyboard?.hide()
                }),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
        }
        StatusBanner(isOffline = state.isOffline, error = state.error.takeIf { state.data != null }, isRefreshing = false, onRetry = { viewModel.programs.refresh() })

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                query.isBlank() && recent.isNotEmpty() -> {
                    item(key = "recent-title") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                            Text("Recent gezocht", style = MaterialTheme.typography.titleSmall, color = TextSecondary, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearRecent) { Text("Wissen") }
                        }
                    }
                    items(recent, key = { "recent-$it" }) { text ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { query = text }.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = TextSecondary)
                            Spacer(Modifier.width(16.dp))
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                query.isBlank() -> item(key = "hint") {
                    Text(
                        "Typ de naam van een programma. Suggesties verschijnen terwijl je typt.",
                        color = TextSecondary,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                state.data == null && state.showFullError -> item(key = "error") {
                    FullScreenError(state.error.orEmpty(), onRetry = { viewModel.programs.refresh() })
                }

                state.data == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Family7Red)
                    }
                }

                suggestions.isEmpty() -> item(key = "none") {
                    Text("Niets gevonden voor \"${query.trim()}\".", color = TextSecondary, modifier = Modifier.padding(24.dp))
                }

                else -> items(suggestions, key = { it.slug }) { program ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { open(program) }.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        RemoteImage(program.thumbnailUrl, null, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(16.dp))
                        Text(highlighted(program.title, query.trim()), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Het getypte deel van de titel vet, zodat te zien is waarom iets past. */
private fun highlighted(title: String, query: String): AnnotatedString = buildAnnotatedString {
    val start = if (query.isEmpty()) -1 else title.indexOf(query, ignoreCase = true)
    if (start < 0) {
        append(title)
    } else {
        append(title.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(title.substring(start, start + query.length)) }
        append(title.substring(start + query.length))
    }
}

/** Eén keuze bovenin het bladerscherm. */
private sealed interface BrowseChoice {
    val key: String
    val label: String

    data class Source(override val key: String, override val label: String, val source: GridSource) : BrowseChoice
    /** Een rubriek zonder eigen "Alles"-pagina: dan de programma's uit de rij zelf. */
    data class Items(override val key: String, override val label: String, val items: List<ProgramItem>) : BrowseChoice
}

/**
 * Bladeren door alle programma's, zoals "On Demand" op tv: A-Z, Kids en de
 * rubrieken die op de site staan. Die rubrieken komen van de site zelf, dus
 * een nieuwe rubriek verschijnt hier vanzelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    rows: List<CategoryRow>,
    castAvailable: Boolean,
    gridViewModel: @Composable (key: String, source: GridSource) -> GridViewModel,
    onOpenProgram: (ProgramItem) -> Unit,
    onSearch: () -> Unit
) {
    val choices = remember(rows) {
        val fixed = listOf(
            BrowseChoice.Source("az", "A-Z", GridSource.AZ),
            BrowseChoice.Source("kids", "Kids", GridSource.Kids)
        )
        val fromSite = rows
            .filter { it.id != "uitgelicht" && (it.moreUrl.isNotBlank() || it.items.isNotEmpty()) }
            .distinctBy { it.moreUrl.ifBlank { it.id } }
            .map { row ->
                if (row.moreUrl.isNotBlank()) BrowseChoice.Source("page-${row.moreUrl}", row.title, GridSource.Page(row.moreUrl))
                else BrowseChoice.Items("row-${row.id}", row.title, row.items)
            }
        fixed + fromSite
    }
    var selectedKey by rememberSaveable { mutableStateOf("az") }
    val selected = choices.firstOrNull { it.key == selectedKey } ?: choices.first()

    // De gekozen rubriek in beeld houden, ook na draaien of terugkomen.
    val chipState = rememberLazyListState()
    LaunchedEffect(choices.size) {
        val index = choices.indexOfFirst { it.key == selected.key }
        if (index > 0) chipState.scrollToItem((index - 1).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenBar("Programma's", castAvailable, onBack = null, onSearch = onSearch)
        LazyRow(
            state = chipState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            items(choices, key = { it.key }) { choice ->
                FilterChip(
                    selected = choice.key == selected.key,
                    onClick = { selectedKey = choice.key },
                    label = { Text(choice.label) }
                )
            }
        }
        // Elke keuze een eigen raster, zodat wisselen bovenaan begint.
        key(selected.key) {
            when (selected) {
                is BrowseChoice.Source -> {
                    val viewModel = gridViewModel(selected.key, selected.source)
                    val state by viewModel.programs.state.collectAsStateWithLifecycle()
                    ProgramGrid(
                        state = state,
                        emptyMessage = "Hier staan nu geen programma's.",
                        onRetry = { viewModel.programs.refresh() },
                        onOpenProgram = onOpenProgram
                    )
                }
                is BrowseChoice.Items -> ProgramGrid(
                    state = LoadState(data = selected.items),
                    emptyMessage = "Hier staan nu geen programma's.",
                    onRetry = {},
                    onOpenProgram = onOpenProgram
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    viewModel: MyListViewModel,
    castAvailable: Boolean,
    onOpenProgram: (ProgramItem) -> Unit,
    onSearch: () -> Unit
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Mijn lijst", castAvailable, onBack = null, onSearch = onSearch)
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
private fun ScreenBar(title: String, castAvailable: Boolean, onBack: (() -> Unit)?, onSearch: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                }
            }
        },
        actions = {
            // Zoeken als rond knopje rechtsboven, zoals gebruikelijk op Android.
            if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Zoeken") }
            if (castAvailable) CastButton()
        },
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
