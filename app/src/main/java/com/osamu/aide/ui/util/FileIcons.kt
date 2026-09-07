package com.osamu.aide.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import com.osamu.aide.R
import java.io.File

data class FileIconInfo(
    val icon: ImageVector,
    val tint: Color
)

object FileIcons {

    @Composable
    fun infoFor(file: File, isDirectory: Boolean, isExpanded: Boolean = false): FileIconInfo {
        return if (isDirectory) {
            FileIconInfo(
                icon = ImageVector.vectorResource(
                    if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder
                ),
                tint = colorResource(R.color.folder)
            )
        } else {
            val extension = file.extension.lowercase()
            val (iconRes, colorRes) = when (extension) {
                "java" -> R.drawable.ic_file_java to R.color.file_java
                "kt", "kts" -> R.drawable.ic_file_kotlin to R.color.file_kotlin
                // .mjs and .cjs are the same language; a Node project can
                // hold either the moment it declares a module type.
                "js", "mjs", "cjs" -> R.drawable.ic_file_js to R.color.file_js
                "xml" -> R.drawable.ic_file_xml to R.color.file_xml
                "json" -> R.drawable.ic_file_json to R.color.file_json
                "gradle" -> R.drawable.ic_file_gradle to R.color.file_gradle
                else -> R.drawable.ic_file_generic to R.color.file_generic
            }
            FileIconInfo(
                icon = ImageVector.vectorResource(iconRes),
                tint = colorResource(colorRes)
            )
        }
    }
}
