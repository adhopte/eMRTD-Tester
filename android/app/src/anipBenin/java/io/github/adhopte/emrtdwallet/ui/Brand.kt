package io.github.adhopte.emrtdwallet.ui

import androidx.compose.ui.graphics.Color

/**
 * ANIP Bénin brand palette: the Republic of Bénin's national flag colors (green / yellow / red,
 * official hex per the flag's Pantone-to-RGB conversion), plus the ochre and blue accents added
 * for the country's 2025 "marque-pays" visual identity. ANIP (Agence Nationale d'Identification
 * des Personnes) has no separately published brand palette, so this skin uses the national one —
 * consistent with ANIP being the national identification agency — for a TEST-only demo build.
 *
 * This is the `anipBenin` product flavor's implementation of [Brand]; see the `ingroupe` flavor's
 * `Brand.kt` for the sibling palette. Every UI file references `Brand.*` and only one flavor's
 * source set compiles in per build.
 */
object Brand {
    val Blue = Color(0xFF008850)        // flag green (primary)
    val Navy = Color(0xFF00512F)        // deep green
    val MediumBlue = Color(0xFF00703F)  // medium green
    val SkyBlue = Color(0xFFD9A404)     // ochre / gold accent
    val Red = Color(0xFFE90929)         // flag red
    val DarkRed = Color(0xFFA10620)     // deep red
    val Grey = Color(0xFFB7C2B6)        // sage grey
    val LightGrey = Color(0xFFE9F0E9)   // sage grey (light)
    val Surface = Color(0xFFF4FAF5)     // pale green-tinted surface
    val CardGradient = listOf(Navy, MediumBlue)
}
