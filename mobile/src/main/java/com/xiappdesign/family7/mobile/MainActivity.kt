package com.xiappdesign.family7.mobile

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
import com.xiappdesign.family7.brand.Family7Splash
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
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
import com.xiappdesign.family7.core.data.ProgramItem
import com.xiappdesign.family7.mobile.playback.PlayRequest
import com.xiappdesign.family7.mobile.ui.AppViewModel
import com.xiappdesign.family7.mobile.ui.AuthState
import com.xiappdesign.family7.mobile.ui.GridSource
import com.xiappdesign.family7.mobile.ui.GridViewModel
import com.xiappdesign.family7.mobile.ui.HomeViewModel
import com.xiappdesign.family7.mobile.ui.MyListViewModel
import com.xiappdesign.family7.mobile.ui.ProgramViewModel
import com.xiappdesign.family7.mobile.ui.screens.GridScreen
import com.xiappdesign.family7.mobile.ui.screens.HomeScreen
import com.xiappdesign.family7.mobile.ui.screens.LoginScreen
import com.xiappdesign.family7.mobile.ui.screens.MiniCastBar
import com.xiappdesign.family7.mobile.ui.screens.MyListScreen
import com.xiappdesign.family7.mobile.ui.screens.PlayerScreen
import com.xiappdesign.family7.mobile.ui.screens.ProgramScreen
import com.xiappdesign.family7.mobile.ui.screens.SearchScreen
import com.xiappdesign.family7.mobile.ui.theme.Family7MobileTheme

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
    const val SEARCH = "search"
    const val MY_LIST = "mylist"
    const val KIDS = "kids"
    const val AZ = "az"
    const val PAGE = "page?url={url}&title={title}"
    const val PROGRAM = "program/{slug}?title={title}&image={image}"
    const val PLAYER = "player"

    fun page(url: String, title: String) = "page?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
    fun program(item: ProgramItem) =
        "program/${Uri.encode(item.slug)}?title=${Uri.encode(item.title)}&image=${Uri.encode(item.thumbnailUrl)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Start", Icons.Filled.Home),
    Tab("live", "Live", Icons.Filled.LiveTv),
    Tab(Routes.SEARCH, "Zoeken", Icons.Filled.Search),
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
    val myList by app.myList.items.collectAsStateWithLifecycle()
    val onPlayer = route == Routes.PLAYER

    fun play(request: PlayRequest) {
        app.playback.play(request)
        // Tijdens het casten blijft de kijker waar hij is; de mini-balk toont wat er speelt.
        if (!app.playback.state.value.isCasting) nav.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            if (!onPlayer) {
                Column {
                    if (playback.isCasting && playback.request != null) {
                        MiniCastBar(
                            state = playback,
                            onTogglePlay = app.playback::togglePlayPause,
                            onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }
                        )
                    }
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = backStack?.destination?.hierarchy()?.contains(tab.route) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (tab.route == "live") play(PlayRequest.Live)
                                    else nav.navigateToTab(tab.route)
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) }
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
                    onWatchLive = { play(PlayRequest.Live) },
                    onLogout = appViewModel::logout
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    viewModel = viewModel(key = "az") { GridViewModel(app, GridSource.AZ) },
                    castAvailable = playback.castAvailable,
                    onOpenProgram = { nav.navigate(Routes.program(it)) }
                )
            }
            composable(Routes.MY_LIST) {
                MyListScreen(
                    viewModel = viewModel { MyListViewModel(app) },
                    castAvailable = playback.castAvailable,
                    onOpenProgram = { nav.navigate(Routes.program(it)) }
                )
            }
            composable(Routes.KIDS) {
                GridScreen(
                    title = "Kids",
                    viewModel = viewModel(key = "kids") { GridViewModel(app, GridSource.Kids) },
                    castAvailable = playback.castAvailable,
                    emptyMessage = "Er zijn op dit moment geen kinderprogramma's gevonden.",
                    onOpenProgram = { nav.navigate(Routes.program(it)) },
                    onBack = { nav.popBackStack() }
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
            composable(Routes.PLAYER) {
                PlayerScreen(
                    playback = app.playback,
                    isInPip = isInPip,
                    onPipEligibleChanged = onPipEligibleChanged,
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}

/** Naar een tab van de onderbalk, zonder een stapel van dezelfde schermen op te bouwen. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavDestination.hierarchy(): List<String?> =
    generateSequence(this) { it.parent }.map { it.route }.toList()
