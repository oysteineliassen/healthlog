package no.healthlog.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import no.healthlog.app.coach.AiAnswerBlock
import no.healthlog.app.coach.AiCoachGoals
import no.healthlog.app.coach.AiCoachPreferences
import no.healthlog.app.coach.buildAiCoachPrompt
import no.healthlog.app.coach.parseAiAnswer
import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val AI_PERIOD_CHOICES = listOf(14, 28, 90)

private fun buildPrompt(context: Context, all: List<HealthDay>, periodDays: Int): String =
    buildAiCoachPrompt(
        all,
        LocalDate.now(),
        AiCoachGoals(
            sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context),
            stepsGoal = GoalPreferences.getStepsGoal(context),
            trainingGoalMinutes = GoalPreferences.getTrainingGoalMinutes(context),
            weightGoalKg = GoalPreferences.getWeightGoalKg(context)
        ),
        periodDays
    )

/**
 * Ekstern AI-trener: lag prompt -> lim inn i Claude/ChatGPT/Copilot -> lim svaret inn
 * her. Vises bare når det er slått på i Innstillinger (se AiCoachPreferences).
 */
@Composable
fun AiCoachCard(all: List<HealthDay>) {
    val context = LocalContext.current
    var answer by remember { mutableStateOf(AiCoachPreferences.getAnswer(context)) }
    var periodDays by remember { mutableIntStateOf(AiCoachPreferences.getPeriodDays(context)) }
    var showPaste by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.ai_coach_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            val current = answer
            if (current == null) {
                Text(
                    stringResource(R.string.ai_coach_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                current.second?.let { date ->
                    val age = ChronoUnit.DAYS.between(date, LocalDate.now())
                    Text(
                        stringResource(R.string.ai_coach_answer_from, date.format(DateTimeFormatter.ofPattern("d.M.yyyy"))) +
                            if (age >= 7) " · " + stringResource(R.string.ai_coach_answer_old) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                AiAnswerView(parseAiAnswer(current.first), maxBlocks = if (expanded) Int.MAX_VALUE else 4)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(if (expanded) R.string.ai_coach_show_less else R.string.ai_coach_show_more))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ai_coach_period_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AI_PERIOD_CHOICES.forEach { days ->
                    FilterChip(
                        selected = periodDays == days,
                        onClick = {
                            periodDays = days
                            AiCoachPreferences.setPeriodDays(context, days)
                        },
                        label = { Text(stringResource(R.string.ai_coach_period_days, days)) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val prompt = buildPrompt(context, all, periodDays)
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("HealthLog AI-prompt", prompt))
                    info = context.getString(R.string.ai_coach_prompt_copied)
                }) {
                    Text(stringResource(R.string.ai_coach_copy_prompt))
                }
                OutlinedButton(onClick = {
                    val prompt = buildPrompt(context, all, periodDays)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, prompt)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.ai_coach_share_title)))
                }) {
                    Text(stringResource(R.string.ai_coach_share_prompt))
                }
            }
            OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_coach_paste_answer))
            }
            info?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showPaste) {
        AiAnswerPasteDialog(
            onDismiss = { showPaste = false },
            onSave = { text ->
                AiCoachPreferences.setAnswer(context, text)
                answer = AiCoachPreferences.getAnswer(context)
                expanded = true
                info = null
                showPaste = false
            }
        )
    }
}

@Composable
private fun AiAnswerView(blocks: List<AiAnswerBlock>, maxBlocks: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.take(maxBlocks).forEach { block ->
            when (block) {
                is AiAnswerBlock.Heading -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                is AiAnswerBlock.Bullet -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }
                is AiAnswerBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (maxBlocks == Int.MAX_VALUE) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AiAnswerPasteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    // Forhåndsutfyll med utklippstavlen - vanligste flyt er "Kopier" i AI-appen og så hit.
    var text by remember {
        mutableStateOf(
            context.getSystemService(ClipboardManager::class.java)
                ?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
                ?.takeIf { !it.startsWith("Du er en erfaren trener") } // ikke vår egen prompt
                ?: ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.ai_coach_paste_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.ai_coach_paste_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
