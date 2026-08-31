package com.mekromn.apkbox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Displays only a real icon extracted from this exact APK; otherwise uses a neutral file glyph. */
@Composable
fun StoredApkIcon(
    record: ApkRecord?,
    modifier: Modifier = Modifier,
    contentDescription: String? = record?.label,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = record?.id,
        key2 = record?.iconUpdatedAtEpochMs,
    ) {
        value = withContext(Dispatchers.IO) {
            record?.takeIf { it.iconUpdatedAtEpochMs > 0L }?.let { stored ->
                val file = File(context.filesDir, "apkbox-vault/icons/${stored.id}.png")
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
            }
        }
    }

    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.InsertDriveFile,
            contentDescription = "APK icon not cached yet",
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
