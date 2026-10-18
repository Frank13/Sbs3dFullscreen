package fr.camera3d.camera.common.ui_components

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable counterparts of ParameterComposable.kt/OkCancelDialog.kt: same behavior, but every
// label/dialog-title string is an explicit String parameter instead of an R.string id resolved
// internally via stringResource(Int) - that overload only exists on Android, so a portable file
// can't call it. Named Portable*/Portable-prefixed to avoid JVM signature collisions with the
// originals in the same package (which stay untouched - many other screens call them, relying on
// e.g. OkCancelDialog's stringResource(R.string.cancel) default). SwitchParameterComposable and
// EditParameterComposable below don't collide (different parameter types/arity from the
// originals), so they keep their original names. Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Widget describing a parameter with its name, its value, and its documentation.
 * See [EditParameterComposable] to add an editable value in a dialog.
 */
@Composable
fun PortableParameterComposable(
    modifier: Modifier = Modifier,
    parameterName: String,
    parameterValue: String,
    documentation: String = "",
    defaultValue: String = "",
    onClick: () -> Unit = {},
) {
    Column(modifier = modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            text = parameterName,
            style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.clickable { onClick() },
        )
        if (parameterValue != "") {
            Text(
                text = parameterValue,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color(51, 181, 229)),
                modifier = Modifier.clickable { onClick() },
            )
        } else {
            Text(
                text = defaultValue,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color(100, 100, 100)),
                modifier = Modifier.clickable { onClick() },
            )
        }
        if (documentation != "") {
            PortableDocumentationComposable(documentation = documentation)
        }
    }
}

@Composable
fun PortableDocumentationComposable(modifier: Modifier = Modifier, documentation: String) {
    Text(
        text = documentation,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.padding(top = 8.dp, start = 8.dp),
    )
}

/**
 * Parameter name and a switch in a row.
 */
@Composable
fun SwitchParameterComposable(
    modifier: Modifier = Modifier,
    parameterName: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    documentation: String = "",
    topPadding: Dp = 16.dp,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    Column {
        Row(
            modifier = modifier.fillMaxWidth().padding(top = topPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                parameterName,
                style = MaterialTheme.typography.titleMedium.copy(color = textColor),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
        if (documentation != "") {
            PortableDocumentationComposable(documentation = documentation)
        }
    }
}

/**
 * Ok/Cancel dialog with arbitrary content.
 * @param dialogOkLabel : label of the OK button (e.g. "Save")
 * @param dialogCancelLabel : label of the Cancel button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortableOkCancelDialog(
    onDismissRequestCb: () -> Unit,
    dialogTitle: String = "",
    dialogOkLabel: String,
    dialogCancelLabel: String,
    okCallback: () -> Unit,
    cancelCallback: () -> Unit = { onDismissRequestCb() },
    content: @Composable () -> Unit,
) {
    // DialogProperties() is left at its defaults here (no decorFitsSystemWindows override):
    // that parameter is Android-only and this file is shared verbatim with the Desktop app's
    // DialogProperties, which has no such parameter. imePadding() below still shrinks the Box
    // for the keyboard on Android - redundant work if the platform already auto-resized the
    // window for the IME, but harmless (WindowInsets.ime reports 0 in that case).
    BasicAlertDialog(
        onDismissRequest = onDismissRequestCb,
        properties = DialogProperties(),
    ) {
        // imePadding() shrinks this Box's bounds by the keyboard height *before* centering the
        // Surface inside it, so the dialog is centered in the visible (non-keyboard) area rather
        // than growing the Surface itself - applying imePadding() directly to the Surface would
        // only shift it up by half the keyboard height, since BasicAlertDialog centers the Surface
        // as a whole (half the added height lands above, half below, still under the keyboard).
        Box(modifier = Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            BoxWithConstraints {
                Surface(
                    modifier = Modifier.wrapContentWidth().heightIn(max = maxHeight),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                ) {
                    val dialogPadding = 16.dp
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(dialogPadding),
                    ) {
                        if (dialogTitle.isNotEmpty()) {
                            Text(text = dialogTitle, style = MaterialTheme.typography.titleSmall)
                        }
                        // Only this middle section scrolls, so the title above and the buttons
                        // below stay pinned and are never hidden behind the keyboard.
                        val scrollState = rememberScrollState()
                        val mouseScrollbar = LocalMouseScrollbar.current
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(scrollState)
                                    // The scrollbar mostly sits in the dialog padding (see its offset
                                    // below), so only reserve the part of it that's wider than that.
                                    .padding(end = ((mouseScrollbar?.thickness ?: 0.dp) - dialogPadding).coerceAtLeast(0.dp)),
                            ) {
                                content()
                            }
                            // Offset by the dialog padding so it hugs the dialog's edge rather than the content's.
                            if (mouseScrollbar != null && scrollState.maxValue > 0) {
                                mouseScrollbar.scrollbar(scrollState, Modifier.align(Alignment.CenterEnd).offset(x = dialogPadding))
                            }
                        }
                        Row {
                            TextButton(onClick = cancelCallback) { Text(dialogCancelLabel) }
                            TextButton(onClick = okCallback) { Text(dialogOkLabel) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Widget with title, value, documentation and dialog for editing the value.
 * @param okLabel : label for the info-dialog's confirm button (e.g. "OK")
 * @param saveLabel : label for the edit-dialog's save button (e.g. "Save")
 * @param cancelLabel : label for the edit-dialog's cancel button (e.g. "Cancel")
 * @param onValidate : called with the trimmed new value before onDialogSave; return a non-null error message to keep the dialog open and display it under the field, or null to proceed
 * @param onDialogSave : callback when parameter value is changed in the dialog. Return true to close the dialog (default), false to keep it open (e.g. the save failed)
 * @param onNoChange : called instead of onValidate/onDialogSave when the trimmed value equals the current parameterValue
 */
@Composable
fun EditParameterComposable(
    modifier: Modifier = Modifier,
    parameterValue: String,
    suffix: String = "",
    defaultValue: String = "",
    parameterName: String,
    documentation: String = "",
    dialogTitle: String,
    okLabel: String,
    saveLabel: String,
    cancelLabel: String,
    dialogKeyboardOption: KeyboardOptions = KeyboardOptions(),
    documentationAsInfoIcon: Boolean = false,
    enabled: Boolean = true,
    onValidate: (String) -> String? = { null },
    onNoChange: () -> Unit = {},
    onDialogSave: (String) -> Boolean = { true },
) {
    var openDialog by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(parameterValue) }
    var openDocumentationDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PortableParameterComposable(
            parameterName = parameterName,
            documentation = if (documentationAsInfoIcon) "" else documentation,
            parameterValue = if (parameterValue != "") parameterValue + suffix else "",
            defaultValue = defaultValue,
            onClick = { if (enabled) openDialog = true },
        )
        if (documentationAsInfoIcon && documentation != "") {
            IconButton(onClick = { openDocumentationDialog = true }) {
                Icon(imageVector = Icons.Filled.Info, contentDescription = documentation)
            }
        }
        if (openDocumentationDialog) {
            AlertDialog(
                onDismissRequest = { openDocumentationDialog = false },
                title = { Text(dialogTitle) },
                text = { Text(documentation) },
                confirmButton = {
                    TextButton(onClick = { openDocumentationDialog = false }) { Text(okLabel) }
                },
            )
        }
        if (openDialog) {
            PortableOkCancelDialog(
                dialogTitle = dialogTitle,
                dialogOkLabel = saveLabel,
                dialogCancelLabel = cancelLabel,
                content = {
                    TextField(
                        value = textFieldValue,
                        suffix = { Text(suffix) },
                        keyboardOptions = dialogKeyboardOption,
                        onValueChange = {
                            textFieldValue = it
                            validationError = null
                        },
                        isError = validationError != null,
                        supportingText = validationError?.let { errorMessage -> { Text(errorMessage) } },
                    )
                },
                onDismissRequestCb = {
                    openDialog = false
                    textFieldValue = parameterValue
                    validationError = null
                },
                okCallback = {
                    val newParameter = textFieldValue.trim()
                    if (newParameter != parameterValue) {
                        val error = onValidate(newParameter)
                        if (error != null) {
                            validationError = error
                        } else if (onDialogSave(newParameter)) {
                            openDialog = false
                        }
                    } else {
                        onNoChange()
                        openDialog = false
                    }
                },
            )
        }
    }
}
