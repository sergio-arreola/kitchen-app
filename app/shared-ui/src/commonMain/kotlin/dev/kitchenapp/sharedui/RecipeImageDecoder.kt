package dev.kitchenapp.sharedui

import androidx.compose.ui.graphics.ImageBitmap

internal expect fun decodeRecipeImage(bytes: ByteArray): ImageBitmap?
