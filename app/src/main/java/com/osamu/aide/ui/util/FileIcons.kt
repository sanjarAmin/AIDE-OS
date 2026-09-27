package com.osamu.aide.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Css
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import com.osamu.aide.R
import java.io.File

data class FileIconInfo(
    val icon: ImageVector,
    val tint: Color,
)

object FileIcons {

    @Composable
    fun infoFor(file: File, isDirectory: Boolean, isExpanded: Boolean = false): FileIconInfo {
        if (isDirectory) {
            val folderName = file.name.lowercase()
            val (icon, tint) = when {
                folderName in setOf("src", "app", "core") ->
                    ImageVector.vectorResource(
                        if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ) to Color(0xFF38BDF8) // Cyan / Blue accent
                folderName in setOf("res", "drawable", "layout", "values", "mipmap", "raw", "color", "anim") ->
                    ImageVector.vectorResource(
                        if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ) to Color(0xFF34D399) // Emerald / Android Green
                folderName in setOf("cpp", "jni", "native") ->
                    ImageVector.vectorResource(
                        if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ) to Color(0xFF60A5FA) // Native Blue
                folderName in setOf("test", "androidtest") ->
                    ImageVector.vectorResource(
                        if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ) to Color(0xFFA78BFA) // Test Purple
                else ->
                    ImageVector.vectorResource(
                        if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ) to colorResource(R.color.folder)
            }
            return FileIconInfo(icon, tint)
        }

        val name = file.name.lowercase()
        val extension = file.extension.lowercase()

        val (icon, tint) = when {
            // Kotlin
            extension in setOf("kt", "kts") ->
                ImageVector.vectorResource(R.drawable.ic_file_kotlin) to colorResource(R.color.file_kotlin)

            // Java
            extension in setOf("java", "class") ->
                ImageVector.vectorResource(R.drawable.ic_file_java) to colorResource(R.color.file_java)

            // JavaScript & TypeScript
            extension in setOf("js", "mjs", "cjs", "jsx") ->
                ImageVector.vectorResource(R.drawable.ic_file_js) to colorResource(R.color.file_js)
            extension in setOf("ts", "tsx") ->
                ImageVector.vectorResource(R.drawable.ic_file_js) to Color(0xFF3178C6)

            // C#
            extension == "cs" ->
                ImageVector.vectorResource(R.drawable.ic_file_cs) to colorResource(R.color.file_cs)

            // C / C++
            extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp") ->
                Icons.Default.Code to Color(0xFF38BDF8)

            // Python. `.pyi` is a stub file -- Python syntax with the bodies
            // elided -- and the editor highlights it with the same grammar, so
            // showing it as an unknown file would be the odd one out.
            extension in setOf("py", "pyw", "pyi") ->
                Icons.Default.Code to Color(0xFF3B82F6)

            // Gradle
            extension == "gradle" || name.startsWith("gradlew") ->
                ImageVector.vectorResource(R.drawable.ic_file_gradle) to colorResource(R.color.file_gradle)

            // XML
            extension == "xml" ->
                ImageVector.vectorResource(R.drawable.ic_file_xml) to colorResource(R.color.file_xml)

            // JSON
            extension == "json" ->
                ImageVector.vectorResource(R.drawable.ic_file_json) to colorResource(R.color.file_json)

            // YAML / TOML / Config
            extension in setOf("yaml", "yml", "toml") ->
                Icons.Default.DataObject to Color(0xFFF59E0B)

            // Properties / Env
            extension in setOf("properties", "env", "ini", "conf") ->
                Icons.Default.Tune to Color(0xFF14B8A6)

            // HTML & Web
            extension in setOf("html", "htm") ->
                Icons.Default.Html to Color(0xFFF97316)

            // CSS / Styles
            extension in setOf("css", "scss", "sass", "less") ->
                Icons.Default.Css to Color(0xFF06B6D4)

            // Markdown & Documentation
            extension in setOf("md", "markdown") ->
                Icons.AutoMirrored.Filled.Article to Color(0xFF38BDF8)
            extension in setOf("txt", "log") ->
                Icons.Default.Description to Color(0xFF94A3B8)

            // Shell scripts
            extension in setOf("sh", "bash", "zsh") ->
                Icons.Default.Terminal to Color(0xFF34D399)

            // Git configs
            name.startsWith(".git") ->
                Icons.Default.Commit to Color(0xFFF05032)

            // Security / Proguard
            extension == "pro" || name.contains("proguard") ->
                Icons.Default.Security to Color(0xFFA78BFA)

            // Images
            extension in setOf("png", "jpg", "jpeg", "svg", "webp", "gif", "ico") ->
                Icons.Default.Image to Color(0xFFEC4899)

            // Archives / Native binaries
            extension in setOf("jar", "aar", "so", "a", "zip", "tar", "gz") ->
                Icons.Default.Inventory2 to Color(0xFFD97706)

            // Fallback
            else ->
                ImageVector.vectorResource(R.drawable.ic_file_generic) to colorResource(R.color.file_generic)
        }
        return FileIconInfo(icon, tint)
    }
}
