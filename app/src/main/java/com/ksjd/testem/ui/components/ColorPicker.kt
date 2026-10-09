package com.ksjd.testem.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R

/** A colour swatch to tap; [selected] gets a ring in the theme's colour. */
@Composable
fun ColorSwatch(color: Color, selected: Boolean = false, size: Int = 30, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .padding(if (selected) 4.dp else 1.dp)
            .background(color, CircleShape)
            .clickable(onClick = onClick)
    )
}

/** The "own colour" swatch: a colour wheel look, opening [ColorPickDialog]. */
@Composable
fun CustomColorSwatch(current: Color?, size: Int = 30, onClick: () -> Unit) {
    val rainbow = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
    Box(
        Modifier.size(size.dp)
            .border(if (current != null) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(if (current != null) 4.dp else 0.dp)
            .background(rainbow, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (current != null) Box(Modifier.size((size / 2.6).dp).background(current, CircleShape))
    }
}

/** Ready colours offered in the picker (EM-0042): light and dark backgrounds, then brand-like colours. */
val PickerPresets = listOf(
    0xFFFFFFFF, 0xFFF3EEE4, 0xFFE8F0E6, 0xFFE6EEF7, 0xFFF6E8EC, 0xFFEDE7F6,
    0xFF1C2433, 0xFF1E2A22, 0xFF2B1F2E, 0xFF263238, 0xFF121212, 0xFF000000,
    0xFF1D4FB8, 0xFF0F766E, 0xFF15803D, 0xFFE0A100, 0xFFEA580C, 0xFFB91C1C, 0xFFBE185D, 0xFF7C3AED
)

/** "#1D4FB8" / "1D4FB8" / "#FF1D4FB8" as ARGB, or null. */
fun parseColorHex(raw: String): Long? {
    val cleaned = raw.trim().removePrefix("#")
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    if (!normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return normalized.toLongOrNull(16)
}

fun hexOf(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

/**
 * Pick any colour (EM-0042): the ready ones first, then a saturation/brightness square and a hue bar to drag,
 * and the code as hex or R, G, B for an exact one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickDialog(initial: Long?, title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val start = Color(initial ?: 0xFF1D4FB8)
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    var hexText by remember { mutableStateOf(hexOf(start)) }
    fun set(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), out)
        hue = out[0]; sat = out[1]; value = out[2]
        hexText = hexOf(c)
    }
    fun moved() { hexText = hexOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerPresets.forEach { p -> ColorSwatch(Color(p), selected = color.toArgb() == Color(p).toArgb(), size = 32) { set(Color(p)) } }
                }
                // Saturation left to right, brightness top to bottom, for the hue below.
                Canvas(
                    Modifier.fillMaxWidth().height(150.dp)
                        .pointerInput(Unit) {
                            fun at(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                moved()
                            }
                            detectTapGestures { at(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                moved()
                            }
                        }
                ) {
                    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 11.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                }
                Canvas(
                    Modifier.fillMaxWidth().height(28.dp)
                        .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; moved() } }
                        .pointerInput(Unit) { detectDragGestures { change, _ -> hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f; moved() } }
                ) {
                    drawRoundRect(
                        Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, size.height / 2f, Offset(x, size.height / 2f), style = Stroke(3.dp.toPx()))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(color, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)))
                    OutlinedTextField(
                        hexText,
                        { text -> if (text.length <= 9) { hexText = text; parseColorHex(text)?.let { set(Color(it)); hexText = text } } },
                        label = { Text(stringResource(R.string.color_hex)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f)
                    )
                }
                // R, G and B as numbers, for a colour known by its RGB code.
                val argb = color.toArgb()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) ->
                        OutlinedTextField(
                            ((argb shr shift) and 0xFF).toString(),
                            { text ->
                                val n = text.filter(Char::isDigit).take(3).toIntOrNull()?.coerceIn(0, 255) ?: 0
                                set(Color((argb and (0xFF shl shift).inv()) or (n shl shift) or (0xFF shl 24)))
                            },
                            label = { Text(label) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb().toLong() and 0xFFFFFFFFL) }) { Text(stringResource(R.string.color_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
