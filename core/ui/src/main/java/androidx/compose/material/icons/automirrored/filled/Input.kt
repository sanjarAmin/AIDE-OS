/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.automirrored.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.Input: ImageVector
    get() {
        if (_input != null) {
            return _input!!
        }
        _input = materialIcon(name = "AutoMirrored.Filled.Input", autoMirror = true) {
            materialPath {
                moveTo(21.0f, 3.01f)
                horizontalLineTo(3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(4.99f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(14.03f)
                horizontalLineTo(3.0f)
                verticalLineTo(15.0f)
                horizontalLineTo(1.0f)
                verticalLineToRelative(4.01f)
                curveToRelative(0.0f, 1.1f, 0.9f, 1.98f, 2.0f, 1.98f)
                horizontalLineToRelative(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.88f, 2.0f, -1.98f)
                verticalLineToRelative(-14.0f)
                curveToRelative(0.0f, -1.11f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(11.0f, 16.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(1.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(3.0f)
                close()
            }
        }
        return _input!!
    }

private var _input: ImageVector? = null
