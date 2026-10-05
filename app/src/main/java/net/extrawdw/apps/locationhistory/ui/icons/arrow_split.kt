package net.extrawdw.apps.locationhistory.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val arrow_split: ImageVector
  get() {
    if (_arrow_split != null) {
      return _arrow_split!!
    }
    _arrow_split =
      ImageVector.Builder(
          name = "arrow_split",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(4f, 13f)
            verticalLineTo(11f)
            horizontalLineToRelative(7.6f)
            lineToRelative(5f, -5f)
            horizontalLineTo(14f)
            verticalLineTo(4f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineTo(18f)
            verticalLineTo(7.4f)
            lineTo(12.4f, 13f)
            horizontalLineTo(4f)
            close()
            moveToRelative(10f, 7f)
            verticalLineTo(18f)
            horizontalLineToRelative(2.6f)
            lineTo(13.4f, 14.85f)
            lineTo(14.85f, 13.4f)
            lineTo(18f, 16.6f)
            verticalLineTo(14f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineTo(14f)
            close()
          }
        }
        .build()
    return _arrow_split!!
  }

private var _arrow_split: ImageVector? = null
