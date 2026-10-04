package nl.family7.tv.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.family7.core.data.ProgramItem
import nl.family7.tv.ui.components.Family7Mark
import nl.family7.tv.ui.components.SkeletonGrid
import nl.family7.tv.ui.components.TVButton
import nl.family7.tv.ui.components.TVProgramCard
import nl.family7.tv.ui.theme.Family7BlueDark
import nl.family7.tv.ui.theme.TextPrimary
import nl.family7.tv.ui.theme.TextSecondary

/**
 * Gedeeld rasteroverzicht van programma's, gebruikt door de kidssectie en
 * "Mijn lijst". Toont een plaatshouderraster tijdens het laden, een nette
 * lege staat en een herstelbare foutmelding.
 */
@Composable
fun ProgramGridScreen(
    title: String,
    subtitle: String,
    programs: List<ProgramItem>,
    isLoading: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    onSelectProgram: (ProgramItem) -> Unit,
    onRetry: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Family7BlueDark)
            .padding(horizontal = 36.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TVButton(
                text = "TERUG",
                onClick = onBack,
                isPrimary = false,
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLoading) subtitle else {
                        "${programs.size} ${if (programs.size == 1) "programma" else "programma's"}"
                    },
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }

            Family7Mark(size = 40.dp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
            isLoading -> SkeletonGrid(rows = 3, columns = 4)

            errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = errorMessage, color = Color.White, fontSize = 16.sp)
                    if (onRetry != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TVButton(text = "OPNIEUW PROBEREN", onClick = onRetry)
                    }
                }
            }

            programs.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Family7Mark(size = 64.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = emptyMessage,
                        color = TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(programs) { item ->
                    TVProgramCard(
                        item = item,
                        onClick = { onSelectProgram(item) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
