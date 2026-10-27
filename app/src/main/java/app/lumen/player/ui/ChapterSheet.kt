package app.lumen.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * 0.4.0: the chapter sheet, opened by a long press on the bookmark chip of a speech item. The
 * Label sheet's look: the 22 dp sheet on the elevated surface, a mono field label, one 48 dp
 * name field with the ink focus border on light, and the Save pill. The position was taken
 * when the sheet opened, so a sermon that keeps playing does not move the chapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(
    positionMs: Long,
    itemTitle: String,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val c = Lumen.colours
    val view = LocalView.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(positionMs) { mutableStateOf("") }
    var error by remember(positionMs) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val save: () -> Unit = {
        if (onSave(title)) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            error = "Give the chapter a title"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.elevated,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = HeroRadius, topEnd = HeroRadius),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(c.line, RoundedCornerShape(2.dp)))
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gutter)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("New chapter", style = Lumen.type.displayS, color = c.text, modifier = Modifier.weight(1f))
                Text(formatTime(positionMs), style = Lumen.type.timeL, color = c.accentInk)
            }
            if (itemTitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(itemTitle, style = Lumen.type.bodyS, color = c.text2, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            Text("Title", style = Lumen.type.label, color = c.text2)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TouchTarget)
                    .clip(RoundedCornerShape(CardRadius))
                    .background(c.surface)
                    .then(
                        when {
                            error != null -> Modifier.border(1.dp, c.danger, RoundedCornerShape(CardRadius))
                            focused && !c.isDark -> Modifier.border(1.5.dp, c.accentInk, RoundedCornerShape(CardRadius))
                            else -> Modifier.border(1.dp, c.line, RoundedCornerShape(CardRadius))
                        },
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (title.isEmpty()) Text("Chapter title", style = Lumen.type.body, color = c.text3)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    singleLine = true,
                    textStyle = Lumen.type.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accentInk),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error.orEmpty(), style = Lumen.type.bodyS, color = c.danger)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPill("Save chapter", icon = LumenIcons.Check) { save() }

            // The field takes focus once the sheet is up, so the keyboard comes with it.
            LaunchedEffect(positionMs) {
                try {
                    focus.requestFocus()
                } catch (_: IllegalStateException) {
                    // The field was not attached yet; the owner can tap it.
                }
            }
        }
    }
}
