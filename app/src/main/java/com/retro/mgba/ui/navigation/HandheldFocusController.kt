package com.retro.mgba.ui.navigation

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.retro.mgba.ui.theme.FocusBorderGlow

/**
 * Modifier for Android Handhelds (Mangmi Air X, Odin 2, Retroid) and Gamepads.
 *
 * Provides:
 * 1. Visual focus indicator (glowing border + 1.04x scale animation).
 * 2. Hardware Button mapping:
 *    - KEYCODE_BUTTON_A / DPAD_CENTER / ENTER -> onActivate()
 *    - KEYCODE_BUTTON_X / KEYCODE_MENU -> onAltAction() (e.g. Context Menu)
 *    - KEYCODE_BUTTON_Y -> onSecondaryAction() (e.g. Toggle Favorite)
 */
@Composable
fun Modifier.handheldFocusable(
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.04f,
    focusedBorderColor: Color = FocusBorderGlow,
    borderWidth: Dp = 2.5.dp,
    onActivate: (() -> Unit)? = null,
    onAltAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onFocusStateChanged: ((Boolean) -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "handheldFocusScale"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) focusedBorderColor else Color.Transparent,
        animationSpec = spring(stiffness = 300f),
        label = "handheldFocusBorder"
    )

    var modifier = this
        .scale(animatedScale)
        .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
            onFocusStateChanged?.invoke(focusState.isFocused)
        }

    if (focusRequester != null) {
        modifier = modifier.focusRequester(focusRequester)
    }

    modifier = modifier.focusable()

    if (isFocused) {
        modifier = modifier.border(BorderStroke(borderWidth, animatedBorderColor), shape)
    }

    modifier = modifier.onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (keyEvent.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_ENTER -> {
                if (onActivate != null) {
                    onActivate()
                    true
                } else false
            }
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_MENU -> {
                if (onAltAction != null) {
                    onAltAction()
                    true
                } else false
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (onSecondaryAction != null) {
                    onSecondaryAction()
                    true
                } else false
            }
            else -> false
        }
    }

    return modifier
}
