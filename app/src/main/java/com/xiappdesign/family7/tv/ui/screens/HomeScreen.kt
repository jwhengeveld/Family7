package com.xiappdesign.family7.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiappdesign.family7.core.data.CategoryRow
import com.xiappdesign.family7.core.data.BACKGROUND_REFRESH_MS
import com.xiappdesign.family7.core.data.Family7CatalogRepository
import com.xiappdesign.family7.core.data.ProgramItem
import com.xiappdesign.family7.tv.ui.components.Family7Mark
import com.xiappdesign.family7.tv.ui.components.SkeletonHomeScreen
import com.xiappdesign.family7.tv.ui.components.TVButton
import com.xiappdesign.family7.tv.ui.components.TVProgramCard
import com.xiappdesign.family7.tv.ui.theme.DarkSurface
import com.xiappdesign.family7.tv.ui.theme.Family7BlueDark
import com.xiappdesign.family7.tv.ui.theme.Family7Red
import com.xiappdesign.family7.tv.ui.theme.TextPrimary
import com.xiappdesign.family7.tv.ui.theme.TextSecondary

enum class NavDestination {
    ON_DEMAND, LIVE_TV, KIDS, MY_LIST, AZ, SEARCH, LOGOUT
}

@Composable
fun HomeScreen(
    catalogRepo: Family7CatalogRepository,
    myList: List<ProgramItem>,
    onNavigateToLive: () -> Unit,
    onSelectProgram: (ProgramItem) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToAZ: () -> Unit,
    onNavigateToKids: () -> Unit,
    onNavigateToMyList: () -> Unit,
    onLogout: () -> Unit
) {
    // Meteen vullen uit de cache in het geheugen; alleen een laadscherm tonen als
    // er nog niets bekend is. Daarna wordt er stil op de achtergrond ververst.
    val cached = remember { catalogRepo.homeCache.snapshot() }
    var categoryRows by remember { mutableStateOf(cached?.filterNot { it.id == "uitgelicht" } ?: emptyList()) }
    var featuredProgram by remember {
        mutableStateOf(
            cached?.firstOrNull { it.id == "uitgelicht" }?.items?.firstOrNull()
                ?: cached?.firstOrNull()?.items?.firstOrNull()
        )
    }
    var isLoading by remember { mutableStateOf(cached == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedNav by remember { mutableStateOf(NavDestination.ON_DEMAND) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // De uitgelichte kop vult de banner en wordt daarom niet nog eens als rij getoond.
    fun applyRows(rows: List<CategoryRow>) {
        featuredProgram = rows.firstOrNull { it.id == "uitgelicht" }?.items?.firstOrNull()
            ?: rows.firstOrNull()?.items?.firstOrNull()
        categoryRows = rows.filterNot { it.id == "uitgelicht" }
    }

    LaunchedEffect(reloadKey) {
        if (categoryRows.isEmpty() && featuredProgram == null) isLoading = true
        catalogRepo.getOnDemandHome(forceRefresh = reloadKey > 0)
            .onSuccess { applyRows(it); isLoading = false }
            .onFailure {
                // Staat er al inhoud (uit het geheugen of van schijf), dan blijft
                // die staan; een foutscherm alleen als er niets te tonen is.
                if (categoryRows.isEmpty() && featuredProgram == null) errorMessage = it.message
                isLoading = false
            }
    }

    // Stil verversen zolang Home op de voorgrond staat, zodat nieuwe programma's
    // vanzelf verschijnen. repeatOnLifecycle pauzeert dit in de achtergrond en
    // hervat het bij terugkeer; dan wordt meteen ververst als de cache muf is.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (catalogRepo.homeCache.fresh() == null) {
                catalogRepo.getOnDemandHome(forceRefresh = true).onSuccess { applyRows(it) }
            }
            while (true) {
                delay(BACKGROUND_REFRESH_MS)
                catalogRepo.getOnDemandHome(forceRefresh = true).onSuccess { applyRows(it) }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Family7BlueDark)
    ) {
        // Left Side Navigation Bar
        TVSideNav(
            selected = selectedNav,
            onSelect = { nav ->
                selectedNav = nav
                when (nav) {
                    NavDestination.LIVE_TV -> onNavigateToLive()
                    NavDestination.SEARCH -> onNavigateToSearch()
                    NavDestination.AZ -> onNavigateToAZ()
                    NavDestination.KIDS -> onNavigateToKids()
                    NavDestination.MY_LIST -> onNavigateToMyList()
                    NavDestination.LOGOUT -> onLogout()
                    else -> {}
                }
            }
        )

        // Main Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            if (isLoading) {
                SkeletonHomeScreen()
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Family7Mark(size = 64.dp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Kon de catalogus niet laden.", color = TextPrimary, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(errorMessage!!, color = TextSecondary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        TVButton(text = "OPNIEUW PROBEREN", onClick = { reloadKey++ })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 48.dp)
                ) {
                    // Featured Banner
                    item {
                        if (featuredProgram != null) {
                            HeroBanner(
                                program = featuredProgram!!,
                                onWatchClick = { onSelectProgram(featuredProgram!!) },
                                onLiveClick = onNavigateToLive
                            )
                        }
                    }

                    // Mijn lijst bovenaan, zodat opgeslagen programma's direct in beeld staan
                    if (myList.isNotEmpty()) {
                        item {
                            CategorySwimlane(
                                row = CategoryRow(
                                    id = "mijn_lijst",
                                    title = "Mijn lijst",
                                    items = myList
                                ),
                                onProgramClick = onSelectProgram
                            )
                        }
                    }

                    // Category Rows
                    items(categoryRows) { row ->
                        CategorySwimlane(
                            row = row,
                            onProgramClick = onSelectProgram
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TVSideNav(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .fillMaxSize()
            .background(DarkSurface.copy(alpha = 0.95f))
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Beeldmerk bovenaan
        Family7Mark(size = 44.dp)

        // Navigation Items
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TVSideNavItem(
                icon = Icons.Default.Movie,
                label = "On Demand",
                isSelected = selected == NavDestination.ON_DEMAND,
                onClick = { onSelect(NavDestination.ON_DEMAND) }
            )
            TVSideNavItem(
                icon = Icons.Default.LiveTv,
                label = "Live TV",
                isSelected = selected == NavDestination.LIVE_TV,
                onClick = { onSelect(NavDestination.LIVE_TV) }
            )
            TVSideNavItem(
                icon = Icons.Default.ChildCare,
                label = "Kids",
                isSelected = selected == NavDestination.KIDS,
                onClick = { onSelect(NavDestination.KIDS) }
            )
            TVSideNavItem(
                icon = Icons.Default.BookmarkBorder,
                label = "Mijn lijst",
                isSelected = selected == NavDestination.MY_LIST,
                onClick = { onSelect(NavDestination.MY_LIST) }
            )
            TVSideNavItem(
                icon = Icons.Default.SortByAlpha,
                label = "A-Z",
                isSelected = selected == NavDestination.AZ,
                onClick = { onSelect(NavDestination.AZ) }
            )
            TVSideNavItem(
                icon = Icons.Default.Search,
                label = "Zoeken",
                isSelected = selected == NavDestination.SEARCH,
                onClick = { onSelect(NavDestination.SEARCH) }
            )
        }

        // Logout Bottom
        TVSideNavItem(
            icon = Icons.AutoMirrored.Filled.ExitToApp,
            label = "Uitloggen",
            isSelected = selected == NavDestination.LOGOUT,
            onClick = { onSelect(NavDestination.LOGOUT) }
        )
    }
}

@Composable
fun TVSideNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(if (isFocused) 1.15f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> Family7Red
                    isSelected -> Color(0xFF03326C)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isFocused || isSelected) Color.White else TextSecondary
        )
    }
}

@Composable
fun HeroBanner(
    program: ProgramItem,
    onWatchClick: () -> Unit,
    onLiveClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        // Backdrop Image
        AsyncImage(
            model = program.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Family7BlueDark.copy(alpha = 0.95f),
                            Family7BlueDark.copy(alpha = 0.6f),
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
                        colors = listOf(
                            Color.Transparent,
                            Family7BlueDark
                        ),
                        startY = 140f
                    )
                )
        )

        // Details
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 36.dp, end = 48.dp)
        ) {
            if (program.badge.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Family7Red)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = program.badge.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = program.title,
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            if (program.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = program.description,
                    color = TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.52f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TVButton(
                    text = "BEKIJKEN",
                    onClick = onWatchClick,
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                )
                TVButton(
                    text = "LIVE TV",
                    onClick = onLiveClick,
                    isPrimary = false,
                    leadingIcon = {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White)
                    }
                )
            }
        }
    }
}

@Composable
fun CategorySwimlane(
    row: CategoryRow,
    onProgramClick: (ProgramItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            text = row.title,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 36.dp, bottom = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(row.items) { item ->
                TVProgramCard(
                    item = item,
                    onClick = { onProgramClick(item) }
                )
            }
        }
    }
}
