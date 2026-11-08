package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/** A soft rounded "cookie" shape with [sides] scallops, after the Material 3 Expressive shapes. Fills its clip box. */
fun cookieShape(sides: Int = 9, innerRadius: Float = 0.86f): Shape {
    val polygon = RoundedPolygon.star(
        sides,
        innerRadius = innerRadius,
        rounding = CornerRounding(0.28f),
        innerRounding = CornerRounding(0.28f),
    )
    return GenericShape { size: Size, _ ->
        val path: Path = polygon.toPath().asComposePath()
        // The polygon is centered on (0, 0) with radius 1: scale it to the box and move it to the center.
        val m = Matrix()
        m.translate(size.width / 2f, size.height / 2f)
        m.scale(size.width / 2f, size.height / 2f)
        path.transform(m)
        addPath(path)
    }
}
