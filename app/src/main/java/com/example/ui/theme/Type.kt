package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

val FrauncesFamily = FontFamily(
  Font(R.font.fraunces, FontWeight.Normal),
  Font(R.font.fraunces, FontWeight.SemiBold),
  Font(R.font.fraunces, FontWeight.Bold),
)

val PlusJakartaSansFamily = FontFamily(
  Font(R.font.plus_jakarta_sans, FontWeight.Normal),
  Font(R.font.plus_jakarta_sans, FontWeight.Medium),
  Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
  Font(R.font.plus_jakarta_sans, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
  Font(R.font.jetbrains_mono, FontWeight.Normal),
  Font(R.font.jetbrains_mono, FontWeight.Medium),
  Font(R.font.jetbrains_mono, FontWeight.Bold),
)

private val PizzoraTypeScale = PizzoraTokens.typography

val PizzoraTypography = Typography(
  displaySmall = TextStyle(
    fontFamily = FrauncesFamily,
    fontWeight = FontWeight.Bold,
    fontSize = PizzoraTypeScale.displaySmallSize,
    lineHeight = PizzoraTypeScale.displaySmallLineHeight,
  ),
  headlineMedium = TextStyle(
    fontFamily = FrauncesFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = PizzoraTypeScale.headlineMediumSize,
    lineHeight = PizzoraTypeScale.headlineMediumLineHeight,
  ),
  headlineSmall = TextStyle(
    fontFamily = FrauncesFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = PizzoraTypeScale.headlineSmallSize,
    lineHeight = PizzoraTypeScale.headlineSmallLineHeight,
  ),
  titleLarge = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.Bold,
    fontSize = PizzoraTypeScale.titleLargeSize,
    lineHeight = PizzoraTypeScale.titleLargeLineHeight,
  ),
  titleMedium = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = PizzoraTypeScale.titleMediumSize,
    lineHeight = PizzoraTypeScale.titleMediumLineHeight,
  ),
  bodyLarge = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.Normal,
    fontSize = PizzoraTypeScale.bodyLargeSize,
    lineHeight = PizzoraTypeScale.bodyLargeLineHeight,
  ),
  bodyMedium = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.Normal,
    fontSize = PizzoraTypeScale.bodyMediumSize,
    lineHeight = PizzoraTypeScale.bodyMediumLineHeight,
  ),
  labelLarge = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = PizzoraTypeScale.labelLargeSize,
    lineHeight = PizzoraTypeScale.labelLargeLineHeight,
  ),
  labelMedium = TextStyle(
    fontFamily = PlusJakartaSansFamily,
    fontWeight = FontWeight.Medium,
    fontSize = PizzoraTypeScale.labelMediumSize,
    lineHeight = PizzoraTypeScale.labelMediumLineHeight,
  ),
)

val PizzoraMonoBody = TextStyle(
  fontFamily = JetBrainsMonoFamily,
  fontWeight = FontWeight.Medium,
  fontSize = PizzoraTypeScale.monoBodySize,
  lineHeight = PizzoraTypeScale.monoBodyLineHeight,
  fontFeatureSettings = "tnum",
)

val PizzoraMonoTitle = TextStyle(
  fontFamily = JetBrainsMonoFamily,
  fontWeight = FontWeight.Bold,
  fontSize = PizzoraTypeScale.monoTitleSize,
  lineHeight = PizzoraTypeScale.monoTitleLineHeight,
  fontFeatureSettings = "tnum",
)
