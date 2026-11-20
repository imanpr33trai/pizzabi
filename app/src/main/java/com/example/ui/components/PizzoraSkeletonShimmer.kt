package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated warm surfaceVariant shimmer block that degrades cleanly to a static
 * tonal placeholder when LocalReducedMotion.current is true.
 */
@Composable
fun PizzoraSkeletonShimmer(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(8.dp),
) {
  val reducedMotion = LocalReducedMotion.current
  val alpha = if (reducedMotion) {
    0.72f
  } else {
    val transition = rememberInfiniteTransition(label = "pizzora_skeleton_shimmer")
    val animated by transition.animateFloat(
      initialValue = 0.45f,
      targetValue = 0.90f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 900, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "shimmer_alpha",
    )
    animated
  }

  val baseColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
  Box(
    modifier = modifier
      .clip(shape)
      .background(baseColor),
  )
}

@Composable
fun PizzoraMenuCardSkeleton(
  modifier: Modifier = Modifier,
  thumbnailSize: Dp = 104.dp,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.35f).height(12.dp))
        PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.75f).height(18.dp))
        PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.95f).height(14.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.35f).height(18.dp))
          PizzoraSkeletonShimmer(modifier = Modifier.size(width = 88.dp, height = 36.dp))
        }
      }
      PizzoraSkeletonShimmer(
        modifier = Modifier.size(thumbnailSize),
        shape = MaterialTheme.shapes.medium,
      )
    }
  }
}

@Composable
fun PizzoraOrderCardSkeleton(
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.4f).height(18.dp))
        PizzoraSkeletonShimmer(modifier = Modifier.size(width = 84.dp, height = 22.dp))
      }
      PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.65f).height(14.dp))
      PizzoraSkeletonShimmer(modifier = Modifier.fillMaxWidth(0.45f).height(16.dp))
    }
  }
}
