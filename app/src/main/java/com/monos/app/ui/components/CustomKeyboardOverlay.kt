package com.monos.app.ui.components

import android.view.KeyEvent
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monos.app.ui.DisplayState
import com.monos.app.ui.ScreenOrientation
import com.monos.app.ui.X11DisplayViewModel
import com.monos.app.ui.theme.*

private const val TAG = "CustomKeyboardOverlay"

// JNI Endpoint mapping keysym injections to the X11 server instance
external fun sendKeySymNative(keySym: Long, action: Int)

@Composable
fun CustomKeyboardOverlay(
    displayState: DisplayState,
    viewModel: X11DisplayViewModel,
    modifier: Modifier = Modifier
) {
    val isLandscape = displayState.orientation == ScreenOrientation.LANDSCAPE

    if (isLandscape) {
        // Landscape: entire overlay is transparent/floating on top of canvas
        // The Box wraps both the macro grid and the optional slide-up full keyboard
        Box(modifier = modifier.fillMaxSize()) {
            // Fixed transparent 2x8 macro grid (top-right corner)
            LandscapeKeyboardLayout(
                state = displayState,
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(360.dp)
                    .alpha(displayState.keyboardOpacity)
            )

            // Optional slide-up 75% keyboard panel (triggered via Slide macro button)
            AnimatedVisibility(
                visible = displayState.keyboardVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xEE0D0E15))
                        .border(1.dp, SurfaceLight, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .padding(8.dp)
                ) {
                    TextPreviewBar(
                        content = displayState.textPreviewContent,
                        onClear = { viewModel.updateTextPreviewContent("") }
                    )
                    Keyboard75Percent(
                        onKeyPressed = { keysym ->
                            handleTextPreview(keysym, displayState, viewModel)
                            sendKeySymNative(keysym, 0)
                            sendKeySymNative(keysym, 1)
                        }
                    )
                }
            }
        }
    } else {
        // Portrait: CanvasTab controls visibility and animation via AnimatedVisibility.
        // This composable simply fills its parent container with the keyboard content.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(BackgroundDark)
                .border(1.dp, SurfaceLight, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Close/Hide strip at the top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { viewModel.updateKeyboardVisibility(false) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Hide Keyboard",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "HIDE KEYBOARD",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Text preview bar
            TextPreviewBar(
                content = displayState.textPreviewContent,
                onClear = { viewModel.updateTextPreviewContent("") }
            )

            // Main 75% key grid — takes remaining space
            Keyboard75Percent(
                onKeyPressed = { keysym ->
                    handleTextPreview(keysym, displayState, viewModel)
                    sendKeySymNative(keysym, 0)
                    sendKeySymNative(keysym, 1)
                }
            )
        }
    }
}


@Composable
fun TextPreviewBar(
    content: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .border(1.dp, PrimaryNeon.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (content.isEmpty()) "Text Preview: (Type on custom keyboard)" else content,
            color = if (content.isEmpty()) TextMuted else TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        if (content.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Clear Preview",
                tint = ErrorNeon,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onClear() }
            )
        }
    }
}

@Composable
fun Keyboard75Percent(
    onKeyPressed: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyRows = remember {
        listOf(
            // Row 1: System and Function Keys
            listOf(
                KeyDef("Esc", 0xff1bL), KeyDef("F1", 0xffbeL), KeyDef("F2", 0xffbfL),
                KeyDef("F3", 0xffc0L), KeyDef("F4", 0xffc1L), KeyDef("F5", 0xffc2L),
                KeyDef("F6", 0xffc3L), KeyDef("F7", 0xffc4L), KeyDef("F8", 0xffc5L),
                KeyDef("F9", 0xffc6L), KeyDef("F10", 0xffc7L), KeyDef("F11", 0xffc8L),
                KeyDef("F12", 0xffc9L), KeyDef("Del", 0xffffL)
            ),
            // Row 2: Numbers & Symbols
            listOf(
                KeyDef("~", 0x007eL), KeyDef("1", 0x0031L), KeyDef("2", 0x0032L),
                KeyDef("3", 0x0033L), KeyDef("4", 0x0034L), KeyDef("5", 0x0035L),
                KeyDef("6", 0x0036L), KeyDef("7", 0x0037L), KeyDef("8", 0x0038L),
                KeyDef("9", 0x0039L), KeyDef("0", 0x0030L), KeyDef("-", 0x002dL),
                KeyDef("=", 0x003dL), KeyDef("Bksp", 0xff08L)
            ),
            // Row 3: Tab & Alpha Q-P
            listOf(
                KeyDef("Tab", 0xff09L), KeyDef("q", 0x0071L), KeyDef("w", 0x0077L),
                KeyDef("e", 0x0065L), KeyDef("r", 0x0072L), KeyDef("t", 0x0074L),
                KeyDef("y", 0x0079L), KeyDef("u", 0x0075L), KeyDef("i", 0x0069L),
                KeyDef("o", 0x006fL), KeyDef("p", 0x0070L), KeyDef("[", 0x005bL),
                KeyDef("]", 0x005dL), KeyDef("\\", 0x005cL)
            ),
            // Row 4: Modifiers & Alpha A-L
            listOf(
                KeyDef("Ctrl", 0xffe3L, weight = 1.3f), KeyDef("a", 0x0061L), KeyDef("s", 0x0073L),
                KeyDef("d", 0x0064L), KeyDef("f", 0x0066L), KeyDef("g", 0x0067L),
                KeyDef("h", 0x0068L), KeyDef("j", 0x006aL), KeyDef("k", 0x006bL),
                KeyDef("l", 0x006cL), KeyDef(";", 0x003bL), KeyDef("'", 0x0027L),
                KeyDef("Enter", 0xff0dL, weight = 1.7f)
            ),
            // Row 5: Shift & Alpha Z-M
            listOf(
                KeyDef("Shift", 0xffe1L, weight = 1.8f), KeyDef("z", 0x007aL), KeyDef("x", 0x0078L),
                KeyDef("c", 0x0063L), KeyDef("v", 0x0076L), KeyDef("b", 0x0062L),
                KeyDef("n", 0x006eL), KeyDef("m", 0x006dL), KeyDef(",", 0x002cL),
                KeyDef(".", 0x002eL), KeyDef("/", 0x002fL), KeyDef("Up", 0xff52L)
            ),
            // Row 6: Alt/Meta/Space
            listOf(
                KeyDef("Alt", 0xffe9L, weight = 1.2f), KeyDef("Super", 0xffebL, weight = 1.2f),
                KeyDef("Space", 0x0020L, weight = 5.0f), KeyDef("Left", 0xff51L),
                KeyDef("Down", 0xff54L), KeyDef("Right", 0xff53L)
            )
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keyRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(key.weight)
                            .height(38.dp)
                            .background(SurfaceLight, RoundedCornerShape(4.dp))
                            .border(1.dp, SurfaceLight.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable { onKeyPressed(key.keysym) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = key.label,
                            color = if (key.keysym in listOf(0xffe3L, 0xffe9L, 0xffe1L, 0xffebL)) PrimaryNeon else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LandscapeKeyboardLayout(
    state: DisplayState,
    viewModel: X11DisplayViewModel,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .border(1.dp, SurfaceLight.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Advanced Macro Overlay",
                color = PrimaryNeon,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            
            // 2x8 Grid Layout (16 Keys total)
            // Row index calculations
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.height(86.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Fixed Button 0: Settings Dialogue
                item {
                    MacroButton(
                        label = "Set",
                        onClick = {
                            // Simple dialog callback or configuration trigger
                            Log.i(TAG, "Macro settings opened.")
                        },
                        isFixed = true
                    )
                }

                // Fixed Button 1: Toggle Slide Panel
                item {
                    MacroButton(
                        label = "Slide",
                        onClick = {
                            viewModel.updateKeyboardVisibility(!state.keyboardVisible)
                        },
                        isFixed = true
                    )
                }

                // 14 customizable keys mapping
                items(14) { index ->
                    val macroLabel = state.macros.getOrElse(index) { "M${index + 1}" }
                    MacroButton(
                        label = macroLabel,
                        onClick = {
                            val keysym = getKeysymForMacro(macroLabel)
                            sendKeySymNative(keysym, 0)
                            sendKeySymNative(keysym, 1)
                            handleTextPreview(keysym, state, viewModel)
                        },
                        isFixed = false
                    )
                }
            }
        }
    }
}

@Composable
fun MacroButton(
    label: String,
    onClick: () -> Unit,
    isFixed: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(
                color = if (isFixed) SecondaryNeon.copy(alpha = 0.6f) else SurfaceLight.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = if (isFixed) SecondaryNeon else PrimaryNeon.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private data class KeyDef(
    val label: String,
    val keysym: Long,
    val weight: Float = 1.0f
)

private fun handleTextPreview(keysym: Long, state: DisplayState, viewModel: X11DisplayViewModel) {
    if (keysym in 0x20L..0x7eL) {
        val newChar = keysym.toChar().toString()
        viewModel.updateTextPreviewContent(state.textPreviewContent + newChar)
    } else if (keysym == 0xff08L) { // Backspace
        if (state.textPreviewContent.isNotEmpty()) {
            viewModel.updateTextPreviewContent(state.textPreviewContent.dropLast(1))
        }
    } else if (keysym == 0xff0dL) { // Enter
        viewModel.updateTextPreviewContent("") // Clear on submit/enter
    }
}

private fun getKeysymForMacro(label: String): Long {
    return when (label.lowercase()) {
        "esc" -> 0xff1bL
        "tab" -> 0xff09L
        "ctrl" -> 0xffe3L
        "alt" -> 0xffe9L
        "shift" -> 0xffe1L
        "super" -> 0xffebL
        "del" -> 0xffffL
        "home" -> 0xff50L
        "end" -> 0xff57L
        "pgup" -> 0xff55L
        "pgdn" -> 0xff56L
        "insert" -> 0xff63L
        "prtsc" -> 0xff61L
        else -> {
            if (label.length == 1) {
                label[0].toLong()
            } else {
                0x0020L // space default
            }
        }
    }
}
