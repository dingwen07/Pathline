package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun VisitCenterDot(color: Color, alpha: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color.White.copy(alpha = alpha), radius = size.minDimension / 2f, center = center)
        drawCircle(color.copy(alpha = alpha), radius = size.minDimension / 2f - 2.dp.toPx(), center = center)
    }
}
