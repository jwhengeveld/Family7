package nl.family7.tv

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.saveable.rememberSaveable
import nl.family7.brand.Family7Splash
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import nl.family7.core.data.EpisodeItem
import nl.family7.core.data.Family7AuthRepository
import nl.family7.core.data.Family7CatalogRepository
import nl.family7.core.data.Family7LiveRepository
import nl.family7.core.data.Family7MyListRepository
import nl.family7.core.data.Family7VideoRepository
import nl.family7.core.data.ProgramDetail
import nl.family7.core.data.ProgramItem
import nl.family7.core.data.UserSession
import nl.family7.tv.ui.screens.HomeScreen
import nl.family7.tv.ui.screens.LiveTVScreen
import nl.family7.tv.ui.screens.LoginScreen
import nl.family7.tv.ui.screens.PlayerScreen
import nl.family7.tv.ui.screens.ProgramDetailScreen
import nl.family7.tv.ui.screens.ProgramGridScreen
import nl.family7.tv.ui.screens.SearchScreen
import kotlinx.coroutines.launch
import nl.family7.tv.ui.components.Family7Logo
import nl.family7.tv.ui.theme.Family7Blue
import nl.family7.tv.ui.theme.Family7BlueDark
import nl.family7.tv.ui.theme.Family7Red
import nl.family7.tv.ui.theme.Family7TVTheme

sealed class ScreenState {
    object Splash : ScreenState()
    object Login : ScreenState()
    object Home : ScreenState()
    object Live : ScreenState()
    data class ProgramDetailView(val program: ProgramItem) : ScreenState()
    data class VideoPlayer(val episode: EpisodeItem, val program: ProgramDetail) : ScreenState()
    object Search : ScreenState()
    object Kids : ScreenState()
    object MyList : ScreenState()
}

class MainActivity : ComponentActivity() {
    private lateinit var authRepo: Family7AuthRepository
    private lateinit var liveRepo: Family7LiveRepository
    private lateinit var catalogRepo: Family7CatalogRepository
    private lateinit var videoRepo: Family7VideoRepository
    private lateinit var myListRepo: Family7MyListRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        authRepo = Family7AuthRepository(this)
        liveRepo = Family7LiveRepository(this)
        catalogRepo = Family7CatalogRepository(this)
        videoRepo = Family7VideoRepository(this)
        myListRepo = Family7MyListRepository(this)

        setContent {
            Family7TVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Family7TVApp(
                        authRepo = authRepo,
                        liveRepo = liveRepo,
                        catalogRepo = catalogRepo,
                        videoRepo = videoRepo,
                        myListRepo = myListRepo
                    )
                }
            }
        }
    }
}

@Composable
fun Family7TVApp(
    authRepo: Family7AuthRepository,
    liveRepo: Family7LiveRepository,
    catalogRepo: Family7CatalogRepository,
    videoRepo: Family7VideoRepository,
    myListRepo: Family7MyListRepository
) {
    var screenState by remember { mutableStateOf<ScreenState>(ScreenState.Splash) }
    // Het geanimeerde splashscherm ligt over de app heen; die laadt eronder al door.
    var splashDone by rememberSaveable { mutableStateOf(false) }
    var currentSession by remember { mutableStateOf<UserSession?>(null) }
    val myList by myListRepo.items.collectAsState()
    val scope = rememberCoroutineScope()

    // Bestaande sessie controleren en de lijst van het account ophalen bij Family7
    LaunchedEffect(Unit) {
        // De catalogus van de vorige keer staat dan al klaar als het
        // startscherm verschijnt; ophalen bij Family7 gebeurt daarna stil.
        launch { catalogRepo.restoreSnapshots() }
        val session = authRepo.checkSession()
        currentSession = session
        if (session.isLoggedIn) {
            myListRepo.refresh()
            screenState = ScreenState.Home
        } else {
            screenState = ScreenState.Login
        }
    }

    fun toggleMyList(detail: ProgramDetail, add: Boolean) {
        scope.launch { myListRepo.setInList(detail.nodeId, add) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (val state = screenState) {
        ScreenState.Splash -> Box(modifier = Modifier.fillMaxSize().background(Family7BlueDark))
        ScreenState.Login -> {
            LoginScreen(
                authRepo = authRepo,
                onLoginSuccess = { session ->
                    currentSession = session
                    scope.launch { myListRepo.refresh() }
                    screenState = ScreenState.Home
                }
            )
        }
        ScreenState.Home -> {
            HomeScreen(
                catalogRepo = catalogRepo,
                myList = myList,
                onNavigateToLive = { screenState = ScreenState.Live },
                onSelectProgram = { program ->
                    screenState = ScreenState.ProgramDetailView(program)
                },
                onNavigateToSearch = { screenState = ScreenState.Search },
                onNavigateToAZ = { screenState = ScreenState.Search },
                onNavigateToKids = { screenState = ScreenState.Kids },
                onNavigateToMyList = { screenState = ScreenState.MyList },
                onLogout = {
                    authRepo.logout()
                    currentSession = null
                    myListRepo.clear()
                    catalogRepo.clearMemoryCache()
                    screenState = ScreenState.Login
                }
            )
        }
        ScreenState.Live -> {
            BackHandler {
                screenState = ScreenState.Home
            }
            LiveTVScreen(
                liveRepo = liveRepo,
                onBack = { screenState = ScreenState.Home }
            )
        }
        is ScreenState.ProgramDetailView -> {
            BackHandler {
                screenState = ScreenState.Home
            }
            ProgramDetailScreen(
                programItem = state.program,
                videoRepo = videoRepo,
                isInMyList = myList.any { it.slug == state.program.slug },
                onToggleMyList = ::toggleMyList,
                onPlayEpisode = { episode, detail ->
                    screenState = ScreenState.VideoPlayer(episode, detail)
                },
                onBack = { screenState = ScreenState.Home }
            )
        }
        is ScreenState.VideoPlayer -> {
            BackHandler {
                screenState = ScreenState.ProgramDetailView(
                    ProgramItem(
                        id = state.program.slug,
                        slug = state.program.slug,
                        title = state.program.title,
                        thumbnailUrl = state.program.posterUrl
                    )
                )
            }
            PlayerScreen(
                episode = state.episode,
                program = state.program,
                videoRepo = videoRepo,
                onBack = {
                    screenState = ScreenState.ProgramDetailView(
                        ProgramItem(
                            id = state.program.slug,
                            slug = state.program.slug,
                            title = state.program.title,
                            thumbnailUrl = state.program.posterUrl
                        )
                    )
                }
            )
        }
        ScreenState.Search -> {
            BackHandler {
                screenState = ScreenState.Home
            }
            SearchScreen(
                catalogRepo = catalogRepo,
                onSelectProgram = { program ->
                    screenState = ScreenState.ProgramDetailView(program)
                },
                onBack = { screenState = ScreenState.Home }
            )
        }
        ScreenState.Kids -> {
            BackHandler {
                screenState = ScreenState.Home
            }
            KidsScreen(
                catalogRepo = catalogRepo,
                onSelectProgram = { program ->
                    screenState = ScreenState.ProgramDetailView(program)
                },
                onBack = { screenState = ScreenState.Home }
            )
        }
        ScreenState.MyList -> {
            BackHandler {
                screenState = ScreenState.Home
            }
            LaunchedEffect(Unit) { myListRepo.refresh() }
            ProgramGridScreen(
                title = "Mijn lijst",
                subtitle = "Uw opgeslagen programma's",
                programs = myList,
                isLoading = false,
                errorMessage = null,
                emptyMessage = "Uw lijst is nog leeg. Open een programma en kies \"Mijn lijst\"; " +
                    "die lijst deelt u met de website van Family7.",
                onSelectProgram = { program ->
                    screenState = ScreenState.ProgramDetailView(program)
                },
                onBack = { screenState = ScreenState.Home }
            )
        }
    }
    if (!splashDone) {
        Family7Splash(
            ready = screenState != ScreenState.Splash,
            onFinished = { splashDone = true }
        )
    }
    }
}

@Composable
private fun KidsScreen(
    catalogRepo: Family7CatalogRepository,
    onSelectProgram: (ProgramItem) -> Unit,
    onBack: () -> Unit
) {
    val cachedKids = remember { catalogRepo.kidsCache.snapshot() ?: emptyList() }
    var programs by remember { mutableStateOf(cachedKids) }
    var isLoading by remember { mutableStateOf(cachedKids.isEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        if (programs.isEmpty()) isLoading = true
        errorMessage = null
        catalogRepo.getKidsPrograms(forceRefresh = reloadKey > 0)
            .onSuccess {
                programs = it
                isLoading = false
            }
            .onFailure {
                errorMessage = it.message ?: "Kon de kinderprogramma's niet laden."
                isLoading = false
            }
    }

    ProgramGridScreen(
        title = "Kids",
        subtitle = "Kinderprogramma's laden...",
        programs = programs,
        isLoading = isLoading,
        errorMessage = errorMessage,
        emptyMessage = "Er zijn op dit moment geen kinderprogramma's gevonden.",
        onSelectProgram = onSelectProgram,
        onRetry = { reloadKey++ },
        onBack = onBack
    )
}
