package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap

internal actual fun ImageBitmap.resourceIdentity(): Any = asSkiaBitmap()
