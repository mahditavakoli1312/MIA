// Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/).
//
// The design tokens every screen in this project measures itself with.
//
// They exist because a small model asked to "add a card" will otherwise invent 13.dp of padding
// on one screen and 20.dp on the next, and nobody reviewing the diff will notice until the app
// looks like four people built it. A screen that only ever names tokens cannot drift.
//
// Rule: a screen file never writes a raw number. If a value is missing here, add it here.
package mia.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Four steps and two extremes, on a 4dp grid.
 *
 * Deliberately small: a scale with ten steps is a scale nobody remembers, and the choice between
 * 14 and 18 is exactly the choice that makes screens inconsistent.
 */
object Spacing {
    /** Hairline gaps — between an icon and its own label. */
    val xs = 4.dp

    /** Inside a chip or a dense row. */
    val sm = 8.dp

    /** The default gap between two related things. */
    val md = 12.dp

    /** Screen padding, and the gap between two unrelated blocks. */
    val lg = 16.dp

    /** Between sections of a screen. */
    val xl = 24.dp

    /** Around an empty state, so it doesn't read as a mistake. */
    val xxl = 32.dp
}

/** Corner radii. Cards and sheets are soft; chips are pills. */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp

    /** Anything that should read as a pill regardless of its height. */
    val pill = 999.dp
}

/**
 * Elevation, used sparingly: this is a dark, flat design where a lifted surface reads as a lighter
 * one rather than as a shadow.
 */
object Elevation {
    val flat = 0.dp
    val card = 1.dp
    val sheet = 3.dp
}

/** Icon and control sizes, so two screens never disagree about how big "an icon" is. */
object Sizes {
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp

    /** The smallest comfortable tap target. Never make a tappable thing smaller than this. */
    val touchTarget = 48.dp

    val dividerThickness = 1.dp
    val borderThickness = 1.dp
}

/**
 * Motion. One duration for state changes, one for things entering or leaving, one easing curve.
 *
 * Everything animated in this app uses one of these, so nothing feels faster or slower than the
 * rest of it for no reason.
 */
object Motion {
    const val QUICK_MS = 150
    const val STANDARD_MS = 250
    const val SLOW_MS = 400

    /** Material's standard easing: fast out, slow in. */
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> quick() = tween<T>(durationMillis = QUICK_MS, easing = Easing)
    fun <T> standard() = tween<T>(durationMillis = STANDARD_MS, easing = Easing)
    fun <T> slow() = tween<T>(durationMillis = SLOW_MS, easing = Easing)
}
