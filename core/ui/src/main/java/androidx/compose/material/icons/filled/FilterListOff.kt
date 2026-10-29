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

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.FilterListOff: ImageVector
    get() {
        if (_filterListOff != null) {
            return _filterListOff!!
        }
        _filterListOff = materialIcon(name = "Filled.FilterListOff") {
            materialPath {
                moveTo(10.83f, 8.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(6.0f)
                horizontalLineTo(8.83f)
                lineTo(10.83f, 8.0f)
                close()
                moveTo(15.83f, 13.0f)
                horizontalLineTo(18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-4.17f)
                lineTo(15.83f, 13.0f)
                close()
                moveTo(14.0f, 16.83f)
                verticalLineTo(18.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(3.17f)
                lineToRelative(-3.0f, -3.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.17f)
                lineToRelative(-3.0f, -3.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(0.17f)
                lineTo(1.39f, 4.22f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(18.38f, 18.38f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(14.0f, 16.83f)
                close()
            }
        }
        return _filterListOff!!
    }

private var _filterListOff: ImageVector? = null
