package com.example.core.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Play-Policy-compliant zero-permission Photo Picker button (`PickVisualMedia.ImageOnly`).
 * Requires zero storage permissions in AndroidManifest.xml.
 */
@Composable
fun ProductImagePickerButton(
  onImageSelected: (Uri) -> Unit,
  enabled: Boolean = true,
  label: String = "Select Product Photo",
  modifier: Modifier = Modifier,
) {
  val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
    onResult = { uri ->
      if (uri != null) {
        onImageSelected(uri)
      }
    },
  )

  Button(
    onClick = {
      launcher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
      )
    },
    enabled = enabled,
    modifier = modifier
      .minimumInteractiveComponentSize()
      .testTag("pick_product_image_button"),
  ) {
    Icon(
      imageVector = Icons.Filled.AddPhotoAlternate,
      contentDescription = null,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(label)
  }
}
