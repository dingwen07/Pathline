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
public val merge_type: ImageVector
  get() {
    if (_merge_type != null) {
      return _merge_type!!
    }
    _merge_type =
      ImageVector.Builder(
          name = "merge_type",
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
            moveTo(16.6f, 20f)
            lineTo(11f, 14.4f)
            verticalLineTo(6.88f)
            lineTo(8.4f, 9.48f)
            lineTo(6.98f, 8.05f)
            lineTo(12f, 3.02f)
            lineToRelative(5f, 5f)
            lineTo(15.58f, 9.45f)
            lineTo(13f, 6.88f)
            verticalLineTo(13.6f)
            lineToRelative(5f, 5f)
            lineTo(16.6f, 20f)
            close()
            moveTo(7.4f, 20.02f)
            lineTo(6f, 18.63f)
            lineToRelative(3.18f, -3.2f)
            lineToRelative(1.43f, 1.43f)
            lineTo(7.4f, 20.02f)
            close()
          }
        }
        .build()
    return _merge_type!!
  }

private var _merge_type: ImageVector? = null
