@file:OptIn(ExperimentalTextApi::class)

package app.auriel.cobalt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.auriel.cobalt.R

/**
 * Three faces, each with one job.
 *
 * - **Open Sans** for the interface. It is a workhorse: unremarkable at a glance,
 *   which is what a browser chrome should be, and legible at the small sizes a
 *   tab list needs.
 * - **EB Garamond** for display text — the wordmark, section titles, and headings
 *   in rendered documents. A serif at large sizes gives the browser a voice
 *   without making the interface harder to read.
 * - **JetBrains Mono** for code and preformatted text. Wide apertures and a tall
 *   x-height keep `l`, `1`, and `I` distinct, which matters more in a `<pre>`
 *   block than anywhere else on a page.
 *
 * All three are variable fonts. The weight axis is set explicitly rather than
 * left to synthetic bolding, which on a variable face produces smeared strokes.
 * Variation settings need API 26; below that Android uses the default instance,
 * so text stays correct and merely loses some weight contrast.
 */
private fun weight(value: Int) = FontVariation.Settings(FontVariation.weight(value))

val OpenSans = FontFamily(
    Font(R.font.open_sans, FontWeight.Light, variationSettings = weight(300)),
    Font(R.font.open_sans, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.open_sans, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.open_sans, FontWeight.SemiBold, variationSettings = weight(600)),
    Font(R.font.open_sans, FontWeight.Bold, variationSettings = weight(700)),
)

val EbGaramond = FontFamily(
    Font(R.font.eb_garamond, FontWeight.Light, variationSettings = weight(400)),
    Font(R.font.eb_garamond, FontWeight.Normal, variationSettings = weight(450)),
    Font(R.font.eb_garamond, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.eb_garamond, FontWeight.SemiBold, variationSettings = weight(600)),
    Font(R.font.eb_garamond, FontWeight.Bold, variationSettings = weight(700)),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.jetbrains_mono, FontWeight.Bold, variationSettings = weight(700)),
)

/**
 * Material 3's scale, respaced.
 *
 * The default line heights are tuned for Roboto. Open Sans has a larger
 * x-height and needs a little more room; EB Garamond at display sizes needs
 * tighter tracking than a sans would.
 */
val CobaltTypography = Typography().let { default ->
    Typography(
        displayLarge = default.displayLarge.copy(fontFamily = EbGaramond, letterSpacing = (-0.5).sp),
        displayMedium = default.displayMedium.copy(fontFamily = EbGaramond, letterSpacing = (-0.5).sp),
        displaySmall = default.displaySmall.copy(fontFamily = EbGaramond),

        headlineLarge = default.headlineLarge.copy(fontFamily = EbGaramond),
        headlineMedium = default.headlineMedium.copy(fontFamily = EbGaramond),
        headlineSmall = default.headlineSmall.copy(fontFamily = EbGaramond),

        titleLarge = default.titleLarge.copy(fontFamily = EbGaramond, fontSize = 24.sp),
        titleMedium = default.titleMedium.copy(fontFamily = OpenSans),
        titleSmall = default.titleSmall.copy(fontFamily = OpenSans),

        bodyLarge = default.bodyLarge.copy(fontFamily = OpenSans, lineHeight = 25.sp),
        bodyMedium = default.bodyMedium.copy(fontFamily = OpenSans, lineHeight = 21.sp),
        bodySmall = default.bodySmall.copy(fontFamily = OpenSans),

        labelLarge = default.labelLarge.copy(fontFamily = OpenSans),
        labelMedium = default.labelMedium.copy(fontFamily = OpenSans),
        labelSmall = default.labelSmall.copy(fontFamily = OpenSans, fontSize = 11.sp),
    )
}

/** Styles used by the document renderer, kept beside the rest of the type. */
object DocumentType {

    /** Body prose in a rendered page. */
    val body = TextStyle(fontFamily = OpenSans, fontSize = 16.sp, lineHeight = 26.sp)

    /** Headings in a rendered page. */
    val heading = TextStyle(fontFamily = EbGaramond, fontWeight = FontWeight.SemiBold)

    /** `<pre>` and `<code>`. */
    val code = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 20.sp)
}
