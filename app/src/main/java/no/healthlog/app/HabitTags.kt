package no.healthlog.app

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_HABIT_TAGS = "habit_tag_definitions"
private const val SEPARATOR = "|"
private const val MAX_TAG_LENGTH = 30

/**
 * Listen over vane-merker du kan velge mellom (f.eks. "Alkohol"). Selve valgene per dag
 * ligger i HealthDay.habitTags - fjerner du et merke her, forsvinner det bare fra
 * valglisten, ikke fra dagene der det allerede er brukt.
 */
object HabitTagPreferences {

    fun getTags(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_HABIT_TAGS, null)
        if (stored == null) {
            // Første gang: noen forslag å starte med, på brukerens eget språk.
            val defaults = listOf(
                context.getString(R.string.habit_default_alcohol),
                context.getString(R.string.habit_default_late_caffeine),
                context.getString(R.string.habit_default_screen_in_bed),
                context.getString(R.string.habit_default_late_dinner)
            )
            setTags(context, defaults)
            return defaults
        }
        return stored.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun setTags(context: Context, tags: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HABIT_TAGS, tags.joinToString(SEPARATOR))
            .apply()
    }

    /** Rensker et nytt navn: ingen skilletegn, ikke for langt, ikke tomt. */
    fun sanitize(name: String): String? =
        name.replace(SEPARATOR, "/").trim().take(MAX_TAG_LENGTH).takeIf { it.isNotEmpty() }
}

/**
 * Merkevelgeren i registreringsdialogene: ett trykk slår et merke av/på for dagen,
 * "Rediger merker" åpner en liste der du kan legge til og fjerne merker. Merker som er
 * valgt på dagen, men ikke lenger står i listen din, vises likevel - så du ser hva som
 * faktisk er lagret.
 */
@Composable
fun HabitTagSelector(selected: List<String>, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    var definedTags by remember { mutableStateOf(HabitTagPreferences.getTags(context)) }
    var showEditor by remember { mutableStateOf(false) }

    val allShown = (definedTags + selected.filter { it !in definedTags })

    Text(
        text = stringResource(R.string.habit_section_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        allShown.forEach { tag ->
            val isOn = tag in selected
            FilterChip(
                selected = isOn,
                onClick = { onChange(if (isOn) selected - tag else selected + tag) },
                label = { Text(tag) }
            )
        }
        AssistChip(
            onClick = { showEditor = true },
            label = { Text(stringResource(R.string.habit_edit_tags)) }
        )
    }

    if (showEditor) {
        HabitTagEditorDialog(
            tags = definedTags,
            onDismiss = { showEditor = false },
            onChange = { newTags ->
                definedTags = newTags
                HabitTagPreferences.setTags(context, newTags)
            }
        )
    }
}

@Composable
private fun HabitTagEditorDialog(tags: List<String>, onDismiss: () -> Unit, onChange: (List<String>) -> Unit) {
    var newTag by remember { mutableStateOf("") }

    fun addTag() {
        val clean = HabitTagPreferences.sanitize(newTag) ?: return
        if (tags.none { it.equals(clean, ignoreCase = true) }) onChange(tags + clean)
        newTag = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.habit_editor_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.habit_editor_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tag, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(tags - tag) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.habit_remove_tag, tag))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text(stringResource(R.string.habit_new_tag_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { addTag() }, enabled = newTag.isNotBlank()) {
                        Text(stringResource(R.string.habit_add_tag))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
