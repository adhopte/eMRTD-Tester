package io.github.adhopte.emrtdwallet.ui

import androidx.compose.ui.graphics.Color

/**
 * IN Groupe brand palette (from the CSS custom properties and logo on ingroupe.com).
 *
 * This is the `ingroupe` product flavor's implementation of [Brand]; every UI file references
 * `Brand.*` and the Gradle flavor's source set (`src/ingroupe` vs `src/anipBenin`) picks which
 * palette actually compiles in.
 */
object Brand {
    val Blue = Color(0xFF002F87)        // logo blue
    val Navy = Color(0xFF192C70)        // --brand-blue
    val MediumBlue = Color(0xFF0D3E96)  // --brand-medium-blue
    val SkyBlue = Color(0xFF43B2ED)     // accent
    val Red = Color(0xFFEA0029)         // logo red
    val DarkRed = Color(0xFF9C2539)     // --brand-red
    val Grey = Color(0xFFAEBBCE)        // --brand-grey
    val LightGrey = Color(0xFFECEFF3)   // --brand-grey-2
    val Surface = Color(0xFFF7F9FB)     // --brand-light-grey
    val CardGradient = listOf(Navy, MediumBlue)
}
