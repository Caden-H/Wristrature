package io.github.cadenh.wristrature.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons drawn on the 24 x 24 grid with rounded strokes. Tinted by `Icon`, so the color here does not matter. */
object AppIcons {
    private fun stroked(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Book = stroked(
        "Book",
        "M12,7v14",
        "M3,18a1,1 0,0 1,-1 -1V4a1,1 0,0 1,1 -1h5a4,4 0,0 1,4 4 4,4 0,0 1,4 -4h5a1,1 0,0 1,1 1v13a1,1 0,0 1,-1 1h-6a3,3 0,0 0,-3 3 3,3 0,0 0,-3 -3z",
    )
    val List = stroked("List", "M3,12h.01", "M3,18h.01", "M3,6h.01", "M8,12h13", "M8,18h13", "M8,6h13")
    val Locate = stroked("Locate", "M12,22a10,10 0,1 0,0 -20 10,10 0,0 0,0 20", "M22,12h-4", "M6,12H2", "M12,6V2", "M12,22v-4")
    val Sync = stroked(
        "Sync",
        "M3,12a9,9 0,0 1,9 -9 9.75,9.75 0,0 1,6.74 2.74L21,8",
        "M21,3v5h-5",
        "M21,12a9,9 0,0 1,-9 9 9.75,9.75 0,0 1,-6.74 -2.74L3,16",
        "M8,16H3v5",
    )
    val Search = stroked("Search", "M21,21l-4.34,-4.34", "M11,19a8,8 0,1 0,0 -16 8,8 0,0 0,0 16")
    val Download = stroked("Download", "M12,4v12", "M7,11l5,5 5,-5", "M5,20h14")
    val Check = stroked("Check", "M20,6L9,17l-5,-5", width = 2.6f)
    val ChevronRight = stroked("ChevronRight", "M9,6l6,6 -6,6")
    val ChevronLeft = stroked("ChevronLeft", "M15,6l-6,6 6,6")
    val Library = stroked("Library", "M16,6l4,14", "M12,6v14", "M8,8v12", "M4,4v16")
    val Clock = stroked("Clock", "M12,8v4l3,2", "M21,12a9,9 0,1 1,-18 0 9,9 0,0 1,18 0")
    val User = stroked("User", "M19,21v-2a4,4 0,0 0,-4 -4H9a4,4 0,0 0,-4 4v2", "M12,11a4,4 0,1 0,0 -8 4,4 0,0 0,0 8")
    val Layers = stroked("Layers", "M12,3l9,5 -9,5 -9,-5z", "M3,13l9,5 9,-5")
    val Bookmark = stroked("Bookmark", "M19,21l-7,-4 -7,4V5a2,2 0,0 1,2 -2h10a2,2 0,0 1,2 2v16z")
    val Tag = stroked("Tag", "M20.6,13.4l-7.2,7.2a2,2 0,0 1,-2.8 0L3,13V3h10l7.6,7.6a2,2 0,0 1,0 2.8z", "M7.5,7.5h.01")
    val CloudOff = stroked(
        "CloudOff",
        "M3,3l18,18",
        "M17,17H7a4,4 0,0 1,-0.9 -7.9 6,6 0,0 1,2 -3.1",
        "M10.7,5.1A6,6 0,0 1,18 10a4,4 0,0 1,2 6.3",
    )
    val Undo = stroked("Undo", "M9,14L4,9l5,-5", "M4,9h11a5,5 0,0 1,0 10h-3")
    val Mic = stroked("Mic", "M12,2a3,3 0,0 0,-3 3v7a3,3 0,0 0,6 0V5a3,3 0,0 0,-3 -3z", "M19,10v2a7,7 0,0 1,-14 0v-2", "M12,19v3")
    val Sliders = stroked(
        "Sliders",
        "M10,5H3",
        "M12,19H3",
        "M14,3v4",
        "M16,17v4",
        "M21,12h-9",
        "M21,19h-5",
        "M21,5h-7",
        "M8,10v4",
        "M8,12H3",
    )
    val Phone = stroked("Phone", "M7,2h10a2,2 0,0 1,2 2v16a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2z", "M12,18h.01")
    val Trash = stroked("Trash", "M3,6h18", "M19,6v14a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V6", "M8,6V4a2,2 0,0 1,2 -2h4a2,2 0,0 1,2 2v2")
    val Plus = stroked("Plus", "M12,5v14", "M5,12h14")
    val Play = stroked("Play", "M6,4l14,8 -14,8z")
    val Zap = stroked("Zap", "M13,2L4,14h7l-1,8 9,-12h-7z")
    val Hand = stroked(
        "Hand",
        "M9,11V5a2,2 0,0 1,4 0v6",
        "M13,11a2,2 0,0 1,4 0v3a6,6 0,0 1,-6 6h-1a6,6 0,0 1,-5 -3l-2,-4a2,2 0,0 1,3 -2l1,1",
    )
    val Rows = stroked("Rows", "M4,7h16", "M4,17h16")
    val Columns = stroked("Columns", "M3,4h7v16H3z", "M14,4h7v16h-7z")
    val Info = stroked("Info", "M12,16v-4", "M12,8h.01", "M22,12a10,10 0,1 1,-20 0 10,10 0,0 1,20 0")
    val Alert = stroked("Alert", "M21.73,18l-8,-14a2,2 0,0 0,-3.48 0l-8,14A2,2 0,0 0,4 21h16a2,2 0,0 0,1.73 -3", "M12,9v4", "M12,17h.01")
    val Cloud = stroked("Cloud", "M17.5,19H9a7,7 0,1 1,6.71 -9h1.79a4.5,4.5 0,1 1,0 9Z")
    val Palette = stroked("Palette", "M12,22a10,10 0,1 1,0 -20 10,10 0,0 1,0 20z", "M12,2v20")
}
