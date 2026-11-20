package app.rkn.vpn.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rkn.vpn.R
import app.rkn.vpn.ui.components.SingleLineLabel

private data class OnboardingPage(val icon: Int, val titleRes: Int, val textRes: Int)

private val PAGES = listOf(
    OnboardingPage(R.drawable.ic_shield, R.string.onboarding_1_title, R.string.onboarding_1_text),
    OnboardingPage(R.drawable.ic_notification, R.string.onboarding_2_title, R.string.onboarding_2_text),
    OnboardingPage(R.drawable.ic_settings, R.string.onboarding_3_title, R.string.onboarding_3_text)
)

/** Первый запуск: коротко объясняем разрешения и локальное хранение данных. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val current = PAGES[page]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(current.icon),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(current.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(current.textRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PAGES.indices.forEach { index ->
                val color = if (index == page) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (page == PAGES.lastIndex) onFinish() else page++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            SingleLineLabel(
                text = stringResource(
                    if (page == PAGES.lastIndex) R.string.onboarding_start else R.string.onboarding_next
                )
            )
        }
        TextButton(onClick = onFinish) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}
