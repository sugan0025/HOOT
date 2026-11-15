package app.hoot.ui.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

@Composable
fun RenameDialog(
    originalName: String,
    targetFormat: String,
    slice: app.hoot.model.DonutSlice? = null,
    onDismiss: () -> Unit,
    onConfirm: (finalFileName: String) -> Unit
) {
    val rawBaseName = originalName.substringBeforeLast('.')
    val targetExt = if (targetFormat.lowercase(Locale.ROOT) == "same") {
        originalName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
    } else if (targetFormat.equals("extract", ignoreCase = true)) {
        ""
    } else {
        targetFormat.lowercase(Locale.ROOT)
    }

    val suggestedName = when {
        slice?.id == "bg-remove" -> "${rawBaseName}_nobg"
        slice?.resizePercent == 50 -> "${rawBaseName}_50"
        slice?.targetWidth == 1080 -> "${rawBaseName}_1080p"
        slice?.targetMaxMb != null -> "${rawBaseName}_${slice.targetMaxMb}mb"
        slice?.id == "size-compress" -> "${rawBaseName}_compressed"
        else -> rawBaseName
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = suggestedName, selection = TextRange(0, suggestedName.length)))
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(0.92f)
                .shadow(28.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x668471E2), spotColor = Color(0x888471E2))
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEE1E1A33),
                            Color(0xF5131024),
                            Color(0xFC0E0C1A)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.5.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x80FFFFFF),
                                Color(0x40A499F7),
                                Color(0x15A499F7)
                            )
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Liquid header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Convert to ${targetFormat.uppercase(Locale.ROOT)}",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33A499F7))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                        text = if (targetExt.isBlank()) "Folder" else ".${targetExt}",
                            color = Color(0xFFD4CCFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Name your converted file before starting:",
                    color = Color(0xFFB0ACC8),
                    fontSize = 13.5.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Frosted Liquid Input Field
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        errorText = if (it.text.trim().isEmpty()) "File name cannot be empty" else null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color(0xFFE2DFFF),
                        focusedBorderColor = Color(0xFFA499F7),
                        unfocusedBorderColor = Color(0x44FFFFFF),
                        focusedContainerColor = Color(0x26FFFFFF),
                        unfocusedContainerColor = Color(0x18FFFFFF),
                        cursorColor = Color(0xFFA499F7)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val trimmed = textFieldValue.text.trim()
                            if (trimmed.isNotEmpty()) {
                                onConfirm(if (targetExt.isBlank()) trimmed else "$trimmed.$targetExt")
                            } else {
                                errorText = "File name cannot be empty"
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Actions: Cancel | Convert
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color(0xFFB0ACC8),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            val trimmed = textFieldValue.text.trim()
                            if (trimmed.isEmpty()) {
                                errorText = "File name cannot be empty"
                                return@Button
                            }
                            onConfirm(if (targetExt.isBlank()) trimmed else "$trimmed.$targetExt")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8471E2)),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = "Convert",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
