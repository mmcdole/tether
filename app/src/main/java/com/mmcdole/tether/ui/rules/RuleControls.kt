package com.mmcdole.tether.ui.rules

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.ui.theme.AnsiColors
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors

@Composable
internal fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, fontSize = 12.sp, letterSpacing = 1.sp, color = TetherColors.TextDim)
}

@Composable
internal fun SheetField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    mono: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** Returns the text to keep, or null to reject the edit. */
    accept: (String) -> String? = { it },
) {
    // The ViewModel's state arrives a frame late, so the field keeps its own copy; otherwise fast typing can move the cursor.
    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetLabel(label)
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                val kept = accept(new.text) ?: return@OutlinedTextField
                text = if (kept == new.text) new else TextFieldValue(kept, TextRange(kept.length))
                if (kept != value) onValueChange(kept)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, fontSize = 12.sp, color = TetherColors.Danger) } },
            textStyle = TextStyle(fontSize = 16.sp, fontFamily = if (mono) MudFont else null),
            placeholder = placeholder?.let { { Text(it, fontSize = 16.sp, fontFamily = if (mono) MudFont else null, color = TetherColors.TextDim) } },
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TetherColors.Text,
                unfocusedTextColor = TetherColors.Text,
                errorTextColor = TetherColors.Text,
                focusedContainerColor = TetherColors.Background,
                unfocusedContainerColor = TetherColors.Background,
                errorContainerColor = TetherColors.Background,
                focusedBorderColor = TetherColors.Accent,
                unfocusedBorderColor = TetherColors.Line,
                errorBorderColor = TetherColors.Danger,
                cursorColor = TetherColors.AccentText,
                errorCursorColor = TetherColors.AccentText,
            ),
        )
    }
}

@Composable
internal fun RuleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 14.sp) },
        leadingIcon = leading,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = TetherColors.Background,
            labelColor = TetherColors.Text,
            selectedContainerColor = TetherColors.Selected,
            selectedLabelColor = TetherColors.AccentText,
            disabledContainerColor = TetherColors.Background,
            disabledLabelColor = TetherColors.TextDim,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = TetherColors.Line,
            selectedBorderColor = TetherColors.SelectedBorder,
            disabledBorderColor = TetherColors.Line,
            disabledSelectedBorderColor = TetherColors.Line,
        ),
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp)
        RuleSwitch(checked, onCheckedChange)
    }
}

@Composable
internal fun RuleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = TetherColors.AccentText,
            checkedTrackColor = TetherColors.SelectedBorder,
            checkedBorderColor = TetherColors.SelectedBorder,
            uncheckedThumbColor = TetherColors.TextDim,
            uncheckedTrackColor = TetherColors.Tag,
            uncheckedBorderColor = TetherColors.Tag,
        ),
    )
}

/** Small ring around a selected swatch. */
internal fun Modifier.selectedRing(selected: Boolean, color: Color) =
    if (selected) border(2.dp, color, RoundedCornerShape(50)) else this

/** Colors `%1`–`%9` in a plain pattern, like the mockup. */
internal class WildcardTransformation(private val enabled: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (!enabled) return TransformedText(text, OffsetMapping.Identity)
        val source = text.text
        val styled = buildAnnotatedString {
            append(source)
            var i = 0
            while (i < source.length - 1) {
                val next = source[i + 1]
                if (source[i] == '%') {
                    if (next in '1'..'9') addStyle(SpanStyle(color = AnsiColors.palette[11]), i, i + 2)
                    i++
                }
                i++
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is WildcardTransformation && other.enabled == enabled

    override fun hashCode() = enabled.hashCode()
}

/** The globe from the mockup's All worlds header. */
internal val GlobeIcon: ImageVector by lazy {
    ImageVector.Builder("Globe", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3f, y1 = 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 21f, y1 = 12f)
        close()
        moveTo(3f, 12f)
        lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(15f, 6.5f, 15f, 17.5f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(9f, 6.5f, 9f, 17.5f, 12f, 21f)
    }.build()
}
