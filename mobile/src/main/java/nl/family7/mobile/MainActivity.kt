package nl.family7.mobile

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.saveable.rememberSaveable
import nl.family7.brand.Family7Splash
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import nl.family7.mobile.ui.isWideScreen
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import nl.family7.core.data.ProgramItem
import nl.family7.mobile.playback.PlayRequest
import nl.family7.mobile.ui.AppViewModel
import nl.family7.mobile.ui.AuthState
import nl.family7.mobile.ui.BrowseViewModel
import nl.family7.mobile.ui.GridSource
import nl.family7.mobile.ui.GridViewModel
import nl.family7.mobile.ui.GuideViewModel
import nl.family7.mobile.ui.HomeViewModel
import nl.family7.mobile.ui.SearchViewModel
import nl.family7.mobile.ui.MyListViewModel
import nl.family7.mobile.ui.ProgramViewModel
import nl.family7.mobile.ui.screens.BrowseScreen
import nl.family7.mobile.ui.screens.GridScreen
import nl.family7.mobile.ui.screens.HomeScreen
import nl.family7.mobile.ui.screens.LiveScreen
import nl.family7.mobile.ui.screens.LoginScreen
import nl.family7.mobile.ui.screens.MiniCastBar
import nl.family7.mobile.ui.screens.MyListScreen
import nl.family7.mobile.ui.screens.PlayerScreen
import nl.family7.mobile.ui.screens.ProgramScreen
import nl.family7.mobile.ui.screens.SearchScreen
import nl.family7.mobile.ui.theme.Family7MobileTheme

/**
 * AppCompatActivity en niet ComponentActivity: de Cast-knop opent zijn
 * apparatenkiezer als fragment-dialoog.
 */
class MainActivity : AppCompatActivity() {

    private val app get() = application as Family7MobileApp

    private var isInPip by mutableStateOf(false)
    private var pipEligible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // De app is altijd donker; lichte iconen in de systeembalken, ook als
        // het toestel zelf op een licht thema staat.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        isInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        nl.family7.mobile.cast.CastAvailability.discoverWhileStarted(this)

        setContent {
            Family7MobileTheme {
                Family7MobileRoot(
                    app = app,
                    isInPip = isInPip,
                    onPipEligibleChanged = ::setPipEligible
                )
            }
        }
    }

    /**
     * Speelt er een video op de telefoon, dan gaat hij bij het verlaten van de
     * app verder in een klein venster. Vanaf Android 12 gebeurt dat vloeiend
     * vanuit het systeem; daarvoor via [onUserLeaveHint].
     */
    private fun setPipEligible(eligible: Boolean) {
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { setPictureInPictureParams(pipParams(eligible)) }
        }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * Tijdens het casten regelen de volumeknoppen van de telefoon het volume
     * van de tv, zoals gebruikelijk bij Chromecast-apps.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val volumeKey = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey && app.playback.state.value.isCasting) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                app.playback.adjustCastVolume(up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }
}

private object Routes {
    const val HOME = "home"
    const val BROWSE = "browse"
    const val SEARCH = "search"
    const val MY_LIST = "mylist"
    const val KIDS = "kids"
    const val AZ = "az"
    const val PAGE = "page?url={url}&title={title}"
    const val PROGRAM = "program/{slug}?title={title}&image={image}"
    const val LIVE = "live"
    /** [keep]: geopend vanuit de kleine livespeler; sluiten laat hem doorspelen. */
    const val PLAYER = "player?keep={keep}"

    fun player(keep: Boolean = false) = "player?keep=$keep"

    fun page(url: String, title: String) = "page?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
    fun program(item: ProgramItem) =
        "program/${Uri.encode(item.slug)}?title=${Uri.encode(item.title)}&image=${Uri.encode(item.thumbnailUrl)}"
}

/** [railOnly]: alleen in de zijbalk op een tablet; onderin past er niet meer bij. */
private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val railOnly: Boolean = false
)

private val tabs = listOf(
    Tab(Routes.HOME, "Start", Icons.Filled.Home),
    Tab(Routes.LIVE, "Live", Icons.Filled.LiveTv),
    // Bladeren, zoals "On Demand" op tv.
    Tab(Routes.BROWSE, "Programma's", Icons.Filled.VideoLibrary),
    Tab(Routes.KIDS, "Kids", Icons.Filled.ChildCare, railOnly = true),
    Tab(Routes.MY_LIST, "Mijn lijst", Icons.AutoMirrored.Filled.List)
)

@Composable
private fun Family7MobileRoot(
    app: Family7MobileApp,
    isInPip: Boolean,
    onPipEligibleChanged: (Boolean) -> Unit
) {
    val appViewModel: AppViewModel = viewModel { AppViewModel(app) }
    val auth by appViewModel.auth.collectAsStateWithLifecycle()
    // Het geanimeerde splashscherm ligt over de app heen; die laadt eronder al door.
    var splashDone by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    when (auth) {
        AuthState.Checking -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AuthState.LoggedOut -> {
            val loggingIn by appViewModel.loggingIn.collectAsStateWithLifecycle()
            val error by appViewModel.loginError.collectAsStateWithLifecycle()
            LoginScreen(isLoggingIn = loggingIn, error = error, onLogin = appViewModel::login)
        }
        AuthState.LoggedIn -> MainNavigation(app, appViewModel, isInPip, onPipEligibleChanged)
    }
    if (!splashDone) {
        Family7Splash(ready = auth != AuthState.Checking, onFinished = { splashDone = true })
    }
    }
}

@Composable
private fun MainNavigation(
    app: Family7MobileApp,
    appViewModel: AppViewModel,
    isInPip: Boolean,
    onPipEligibleChanged: (Boolean) -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playback by app.playback.state.collectAsStateWithLifecycle()
    val castPlayer by app.playback.player.collectAsStateWithLifecycle()
    val myList by app.myList.items.collectAsStateWithLifecycle()
    val onPlayer = route == Routes.PLAYER
    val stack by nav.currentBackStack.collectAsStateWithLifecycle()
    val currentTab = currentTabRoute(stack)

    fun play(request: PlayRequest) {
        app.playback.play(request)
        // Tijdens het casten blijft de kijker waar hij is; de mini-balk toont wat er speelt.
        if (!app.playback.state.value.isCasting) nav.navigate(Routes.player()) { launchSingleTop = true }
    }

    val wide = isWideScreen()
    Row(Modifier.fillMaxSize()) {
        if (wide && !onPlayer) {
            Family7Rail(
                isSelected = { route -> route == currentTab },
                onSelect = { route -> nav.navigateToTab(route) },
                onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
            )
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                // Zoeken vult het scherm, met het toetsenbord; dan geen balk onderin.
                if (!onPlayer && route != Routes.SEARCH) {
                    Column {
                        // Tijdens het casten altijd de mini-balk, ook als er nog niets speelt.
                        if (playback.isCasting) {
                            MiniCastBar(
                                state = playback,
                                player = castPlayer,
                                onTogglePlay = app.playback::togglePlayPause,
                                onStop = app.playback::stopCasting,
                                onOpen = { nav.navigate(Routes.player()) { launchSingleTop = true } }
                            )
                        }
                        // Op een tablet staat de navigatie links, zoals op de TV.
                        if (!wide) NavigationBar {
                            tabs.filterNot { it.railOnly }.forEach { tab ->
                                val selected = tab.route == currentTab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { nav.navigateToTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    // Iets kleiner dan standaard, zodat "Programma's" op een smalle telefoon op één regel past.
                                    label = { Text(tab.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                // Alleen de onderkant: elk scherm regelt zelf de ruimte voor de statusbalk.
                modifier = if (onPlayer) Modifier else Modifier
                    .padding(bottom = padding.calculateBottomPadding())
                    // Liggend staan de navigatieknoppen of de camera-uitsparing opzij.
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                    )
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(app) },
                        castAvailable = playback.castAvailable,
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onOpenRow = { row -> nav.navigate(Routes.page(row.moreUrl, row.title)) },
                        onOpenKids = { nav.navigate(Routes.KIDS) },
                        onOpenAZ = { nav.navigate(Routes.AZ) },
                        onWatchLive = { nav.navigateToTab(Routes.LIVE) },
                        onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } },
                        onLogout = appViewModel::logout
                    )
                }
                composable(Routes.BROWSE) {
                    val browse = viewModel { BrowseViewModel(app) }
                    val rows by browse.rows.state.collectAsStateWithLifecycle()
                    BrowseScreen(
                        rows = rows.data.orEmpty(),
                        castAvailable = playback.castAvailable,
                        gridViewModel = { key, source -> viewModel(key = key) { GridViewModel(app, source) } },
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(app) },
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Routes.LIVE) {
                    LiveScreen(
                        playback = app.playback,
                        viewModel = viewModel { GuideViewModel(app) },
                        onFullscreen = { nav.navigate(Routes.player(keep = true)) { launchSingleTop = true } },
                        onOpenGuideItem = { item ->
                            nav.navigate(Routes.program(ProgramItem(id = item.programSlug, slug = item.programSlug, title = item.title, thumbnailUrl = item.imageUrl)))
                        },
                        onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
                    )
                }
                composable(Routes.MY_LIST) {
                    MyListScreen(
                        viewModel = viewModel { MyListViewModel(app) },
                        castAvailable = playback.castAvailable,
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
                    )
                }
                composable(Routes.KIDS) {
                    GridScreen(
                        title = "Kids",
                        viewModel = viewModel(key = "kids") { GridViewModel(app, GridSource.Kids) },
                        castAvailable = playback.castAvailable,
                        emptyMessage = "Er zijn op dit moment geen kinderprogramma's gevonden.",
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        // Op een tablet staat Kids in de zijbalk: dan geen terugpijl.
                        onBack = if (wide) null else ({ nav.popBackStack() })
                    )
                }
                composable(Routes.AZ) {
                    GridScreen(
                        title = "Alle programma's",
                        viewModel = viewModel(key = "az") { GridViewModel(app, GridSource.AZ) },
                        castAvailable = playback.castAvailable,
                        emptyMessage = "Er zijn geen programma's gevonden.",
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(
                    Routes.PAGE,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    val url = entry.arguments?.getString("url").orEmpty()
                    GridScreen(
                        title = entry.arguments?.getString("title").orEmpty(),
                        viewModel = viewModel(key = "page-$url") { GridViewModel(app, GridSource.Page(url)) },
                        castAvailable = playback.castAvailable,
                        emptyMessage = "Hier staan nu geen programma's.",
                        onOpenProgram = { nav.navigate(Routes.program(it)) },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(
                    Routes.PROGRAM,
                    arguments = listOf(
                        navArgument("slug") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("image") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    val slug = entry.arguments?.getString("slug").orEmpty()
                    val preview = ProgramItem(
                        id = slug,
                        slug = slug,
                        title = entry.arguments?.getString("title").orEmpty(),
                        thumbnailUrl = entry.arguments?.getString("image").orEmpty()
                    )
                    ProgramScreen(
                        viewModel = viewModel(key = "program-$slug") { ProgramViewModel(app, slug) },
                        preview = preview,
                        myList = myList,
                        castAvailable = playback.castAvailable,
                        castDeviceName = playback.castDeviceName.takeIf { playback.isCasting },
                        onPlay = { episode, detail -> play(PlayRequest.Episode(episode, detail)) },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(
                    Routes.PLAYER,
                    arguments = listOf(navArgument("keep") { type = NavType.BoolType; defaultValue = false })
                ) { entry ->
                    PlayerScreen(
                        playback = app.playback,
                        keepPlayingOnClose = entry.arguments?.getBoolean("keep") == true,
                        isInPip = isInPip,
                        onPipEligibleChanged = onPipEligibleChanged,
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}

/**
 * De navigatie op een tablet: een smalle balk links met het Family7-embleem,
 * zoals de zijbalk van de TV-app.
 */
@Composable
private fun Family7Rail(isSelected: (String) -> Boolean, onSelect: (String) -> Unit, onSearch: () -> Unit) {
    NavigationRail(
        containerColor = nl.family7.mobile.ui.theme.DarkSurface,
        header = {
            Image(
                painter = painterResource(nl.family7.brand.R.drawable.family7_mark),
                contentDescription = "Family7",
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 24.dp)
                    .size(width = 48.dp, height = 43.dp)
            )
            // Zoeken als rond knopje bovenin de zijbalk.
            FilledTonalIconButton(onClick = onSearch, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "Zoeken")
            }
        },
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
    ) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = isSelected(tab.route),
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1, softWrap = false) }
            )
        }
    }
}

/** De tab waar het huidige scherm bij hoort: de laatste tab in de stapel. */
private fun currentTabRoute(stack: List<androidx.navigation.NavBackStackEntry>): String? =
    stack.lastOrNull { entry -> tabs.any { it.route == entry.destination.route } }?.destination?.route

/** Naar een tab van de onderbalk, zonder een stapel van dezelfde schermen op te bouwen. */
private fun NavHostController.navigateToTab(route: String) {
    // Nog eens op de tab waar je al bent (bijvoorbeeld vanuit een programma dat
    // via de gids geopend is): terug naar het begin van die tab, in plaats van
    // de opgeslagen stapel met dat programma te herstellen.
    if (currentTabRoute(currentBackStack.value) == route) {
        if (currentDestination?.route != route) popBackStack(route, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
