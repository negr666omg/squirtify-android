package org.squidify.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/** Custom player/control icons (standard Material Symbols geometry). */
object SqIcons {

    private fun icon(name: String, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) { build() }
        }.build()

    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(6f, 19f); lineTo(10f, 19f); lineTo(10f, 5f); lineTo(6f, 5f); close()
            moveTo(14f, 5f); lineTo(14f, 19f); lineTo(18f, 19f); lineTo(18f, 5f); close()
        }
    }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext") {
            moveTo(6f, 18f); lineTo(14.5f, 12f); lineTo(6f, 6f); close()
            moveTo(16f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(16f, 18f); close()
        }
    }

    val SkipPrevious: ImageVector by lazy {
        icon("SkipPrevious") {
            moveTo(6f, 6f); lineTo(8f, 6f); lineTo(8f, 18f); lineTo(6f, 18f); close()
            moveTo(9.5f, 12f); lineTo(18f, 6f); lineTo(18f, 18f); close()
        }
    }

    val Shuffle: ImageVector by lazy {
        icon("Shuffle") {
            moveTo(10.59f, 9.17f)
            lineTo(5.41f, 4f)
            lineTo(4f, 5.41f)
            lineTo(9.17f, 10.58f)
            lineTo(10.59f, 9.17f)
            close()
            moveTo(14.5f, 4f)
            lineTo(16.54f, 6.04f)
            lineTo(4f, 18.59f)
            lineTo(5.41f, 20f)
            lineTo(17.96f, 7.46f)
            lineTo(20f, 9.5f)
            lineTo(20f, 4f)
            close()
            moveTo(14.83f, 13.41f)
            lineTo(13.42f, 14.82f)
            lineTo(16.55f, 17.95f)
            lineTo(14.5f, 20f)
            lineTo(20f, 20f)
            lineTo(20f, 14.5f)
            lineTo(17.96f, 16.54f)
            lineTo(14.83f, 13.41f)
            close()
        }
    }

    val Repeat: ImageVector by lazy {
        icon("Repeat") {
            moveTo(7f, 7f)
            horizontalLineTo(17f)
            verticalLineTo(10f)
            lineTo(21f, 6f)
            lineTo(17f, 2f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(7f)
            close()
            moveTo(17f, 17f)
            horizontalLineTo(7f)
            verticalLineTo(14f)
            lineTo(3f, 18f)
            lineTo(7f, 22f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            horizontalLineTo(17f)
            close()
        }
    }

    val RepeatOne: ImageVector by lazy {
        icon("RepeatOne") {
            moveTo(7f, 7f)
            horizontalLineTo(17f)
            verticalLineTo(10f)
            lineTo(21f, 6f)
            lineTo(17f, 2f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(7f)
            close()
            moveTo(17f, 17f)
            horizontalLineTo(7f)
            verticalLineTo(14f)
            lineTo(3f, 18f)
            lineTo(7f, 22f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            horizontalLineTo(17f)
            close()
            moveTo(13f, 15f)
            verticalLineTo(9f)
            horizontalLineTo(12f)
            lineTo(10f, 10f)
            verticalLineTo(11f)
            horizontalLineTo(11.5f)
            verticalLineTo(15f)
            close()
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            moveTo(19f, 9f)
            horizontalLineTo(15f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            horizontalLineTo(5f)
            lineTo(12f, 16f)
            close()
            moveTo(5f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(19f)
            verticalLineTo(18f)
            close()
        }
    }

    val MusicNote: ImageVector by lazy {
        icon("MusicNote") {
            moveTo(12f, 3f)
            verticalLineTo(13.55f)
            curveTo(11.41f, 13.21f, 10.73f, 13f, 10f, 13f)
            curveTo(7.79f, 13f, 6f, 14.79f, 6f, 17f)
            reflectiveCurveTo(7.79f, 21f, 10f, 21f)
            reflectiveCurveTo(14f, 19.21f, 14f, 17f)
            verticalLineTo(7f)
            horizontalLineTo(18f)
            verticalLineTo(3f)
            close()
        }
    }

    val PlaylistAdd: ImageVector by lazy {
        icon("PlaylistAdd") {
            moveTo(3f, 5f); horizontalLineTo(15f); verticalLineTo(7f); horizontalLineTo(3f); close()
            moveTo(3f, 10f); horizontalLineTo(13f); verticalLineTo(12f); horizontalLineTo(3f); close()
            moveTo(3f, 15f); horizontalLineTo(11f); verticalLineTo(17f); horizontalLineTo(3f); close()
            moveTo(18f, 13f); horizontalLineTo(20f); verticalLineTo(17f)
            horizontalLineTo(24f); verticalLineTo(19f); horizontalLineTo(20f)
            verticalLineTo(23f); horizontalLineTo(18f); verticalLineTo(19f)
            horizontalLineTo(14f); verticalLineTo(17f); horizontalLineTo(18f); close()
        }
    }

    val Disc: ImageVector by lazy {
        icon("Disc") {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveTo(6.48f, 22f, 12f, 22f)
            reflectiveCurveTo(22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            reflectiveCurveTo(7.59f, 4f, 12f, 4f)
            reflectiveCurveTo(20f, 7.59f, 20f, 12f)
            reflectiveCurveTo(16.41f, 20f, 12f, 20f)
            close()
            moveTo(12f, 7.5f)
            curveTo(9.51f, 7.5f, 7.5f, 9.51f, 7.5f, 12f)
            reflectiveCurveTo(9.51f, 16.5f, 12f, 16.5f)
            reflectiveCurveTo(16.5f, 14.49f, 16.5f, 12f)
            reflectiveCurveTo(14.49f, 7.5f, 12f, 7.5f)
            close()
        }
    }

    val MoreHoriz: ImageVector by lazy {
        icon("MoreHoriz") {
            moveTo(6f, 10f)
            curveTo(4.9f, 10f, 4f, 10.9f, 4f, 12f)
            reflectiveCurveTo(4.9f, 14f, 6f, 14f)
            reflectiveCurveTo(8f, 13.1f, 8f, 12f)
            reflectiveCurveTo(7.1f, 10f, 6f, 10f)
            close()
            moveTo(18f, 10f)
            curveTo(16.9f, 10f, 16f, 10.9f, 16f, 12f)
            reflectiveCurveTo(16.9f, 14f, 18f, 14f)
            reflectiveCurveTo(20f, 13.1f, 20f, 12f)
            reflectiveCurveTo(19.1f, 10f, 18f, 10f)
            close()
            moveTo(12f, 10f)
            curveTo(10.9f, 10f, 10f, 10.9f, 10f, 12f)
            reflectiveCurveTo(10.9f, 14f, 12f, 14f)
            reflectiveCurveTo(14f, 13.1f, 14f, 12f)
            reflectiveCurveTo(13.1f, 10f, 12f, 10f)
            close()
        }
    }

    val Lyrics: ImageVector by lazy {
        icon("Lyrics") {
            moveTo(3f, 18f)
            horizontalLineTo(15f)
            verticalLineTo(16f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 6f)
            verticalLineTo(8f)
            horizontalLineTo(21f)
            verticalLineTo(6f)
            close()
            moveTo(3f, 13f)
            horizontalLineTo(21f)
            verticalLineTo(11f)
            horizontalLineTo(3f)
            close()
        }
    }
}
