package attendance.help.device.presentation.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.utils.AppLocaleHelper

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var selectedLanguage by rememberSaveable {
        mutableStateOf(AppLocaleHelper.currentLanguageTag(context))
    }

    // Recompose entire screen when locale changes after activity recreate.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_avatar),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text(stringResource(R.string.choose_language), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageButton(
                label = stringResource(R.string.language_english),
                selected = selectedLanguage == "en",
                modifier = Modifier.weight(1f),
                onClick = {
                    if (selectedLanguage != "en") {
                        AppLocaleHelper.setLanguage(context, "en")
                        selectedLanguage = "en"
                    }
                }
            )
            LanguageButton(
                label = stringResource(R.string.language_arabic),
                selected = selectedLanguage == "ar",
                modifier = Modifier.weight(1f),
                onClick = {
                    if (selectedLanguage != "ar") {
                        AppLocaleHelper.setLanguage(context, "ar")
                        selectedLanguage = "ar"
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(36.dp))
        Button(
            onClick = {
                AppLocaleHelper.setLanguage(context, selectedLanguage)
                onContinue()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.continue_action))
        }
    }
}

@Composable
private fun LanguageButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
