package com.achappell.hermesrelay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The small Night Console icon set; callers supply a label or mark each icon decorative. */
internal object HermesIcons {
    val microphone = icon("microphone") {
        strokePath {
            moveTo(9f, 14f)
            lineTo(9f, 6f)
            lineTo(10f, 4f)
            lineTo(12f, 3f)
            lineTo(14f, 4f)
            lineTo(15f, 6f)
            lineTo(15f, 14f)
            lineTo(14f, 16f)
            lineTo(12f, 17f)
            lineTo(10f, 16f)
            close()
            moveTo(5f, 11f)
            lineTo(5f, 13f)
            lineTo(7f, 17f)
            lineTo(12f, 19f)
            lineTo(17f, 17f)
            lineTo(19f, 13f)
            lineTo(19f, 11f)
            moveTo(12f, 19f)
            lineTo(12f, 22f)
            moveTo(9f, 22f)
            lineTo(15f, 22f)
        }
    }

    val waveform = icon("waveform") {
        strokePath {
            moveTo(3f, 13f)
            lineTo(5f, 13f)
            lineTo(7f, 7f)
            lineTo(9f, 18f)
            lineTo(12f, 5f)
            lineTo(15f, 19f)
            lineTo(17f, 9f)
            lineTo(19f, 15f)
            lineTo(21f, 15f)
        }
    }

    val thinkingDots = icon("thinkingDots") {
        fillPath {
            moveTo(4f, 12f)
            lineTo(6f, 10f)
            lineTo(8f, 12f)
            lineTo(6f, 14f)
            close()
            moveTo(10f, 12f)
            lineTo(12f, 10f)
            lineTo(14f, 12f)
            lineTo(12f, 14f)
            close()
            moveTo(16f, 12f)
            lineTo(18f, 10f)
            lineTo(20f, 12f)
            lineTo(18f, 14f)
            close()
        }
    }

    val buffering = icon("buffering") {
        strokePath {
            moveTo(12f, 3f)
            lineTo(12f, 6f)
            moveTo(12f, 18f)
            lineTo(12f, 21f)
            moveTo(3f, 12f)
            lineTo(6f, 12f)
            moveTo(18f, 12f)
            lineTo(21f, 12f)
            moveTo(5.5f, 5.5f)
            lineTo(7.5f, 7.5f)
            moveTo(16.5f, 16.5f)
            lineTo(18.5f, 18.5f)
            moveTo(18.5f, 5.5f)
            lineTo(16.5f, 7.5f)
            moveTo(7.5f, 16.5f)
            lineTo(5.5f, 18.5f)
        }
    }

    val speaker = icon("speaker") {
        strokePath {
            moveTo(4f, 10f)
            lineTo(8f, 10f)
            lineTo(13f, 6f)
            lineTo(13f, 18f)
            lineTo(8f, 14f)
            lineTo(4f, 14f)
            close()
            moveTo(16f, 9f)
            lineTo(18f, 12f)
            lineTo(16f, 15f)
            moveTo(18f, 6f)
            lineTo(21f, 12f)
            lineTo(18f, 18f)
        }
    }

    val check = icon("check") {
        strokePath {
            moveTo(4f, 13f)
            lineTo(9f, 18f)
            lineTo(20f, 6f)
        }
    }

    val pause = icon("pause") {
        fillPath {
            moveTo(6f, 4f)
            lineTo(10f, 4f)
            lineTo(10f, 20f)
            lineTo(6f, 20f)
            close()
            moveTo(14f, 4f)
            lineTo(18f, 4f)
            lineTo(18f, 20f)
            lineTo(14f, 20f)
            close()
        }
    }

    val warning = icon("warning") {
        strokePath {
            moveTo(12f, 3f)
            lineTo(22f, 20f)
            lineTo(2f, 20f)
            close()
            moveTo(12f, 9f)
            lineTo(12f, 14f)
        }
        fillPath {
            moveTo(11f, 17f)
            lineTo(13f, 17f)
            lineTo(12f, 19f)
            close()
        }
    }

    val interruptHand = icon("interruptHand") {
        strokePath {
            moveTo(8f, 13f)
            lineTo(8f, 6f)
            lineTo(9f, 4f)
            lineTo(11f, 4f)
            lineTo(12f, 6f)
            lineTo(12f, 12f)
            lineTo(12f, 5f)
            lineTo(13f, 3f)
            lineTo(15f, 4f)
            lineTo(15f, 12f)
            lineTo(15f, 7f)
            lineTo(17f, 6f)
            lineTo(18f, 8f)
            lineTo(18f, 13f)
            lineTo(20f, 11f)
            lineTo(22f, 12f)
            lineTo(20f, 18f)
            lineTo(16f, 21f)
            lineTo(11f, 21f)
            lineTo(7f, 18f)
            lineTo(4f, 15f)
            lineTo(5f, 13f)
            lineTo(8f, 15f)
            close()
        }
    }

    val listenEar = icon("listenEar") {
        strokePath {
            moveTo(18f, 11f)
            lineTo(18f, 8f)
            lineTo(16f, 5f)
            lineTo(13f, 3f)
            lineTo(9f, 4f)
            lineTo(6f, 7f)
            lineTo(6f, 11f)
            lineTo(9f, 14f)
            lineTo(9f, 17f)
            lineTo(11f, 20f)
            lineTo(14f, 21f)
            lineTo(17f, 19f)
            moveTo(13f, 7f)
            lineTo(11f, 8f)
            lineTo(10f, 10f)
            lineTo(12f, 12f)
            lineTo(13f, 15f)
            lineTo(15f, 16f)
            lineTo(16f, 14f)
        }
    }

    val stop = icon("stop") {
        fillPath {
            moveTo(6f, 6f)
            lineTo(18f, 6f)
            lineTo(18f, 18f)
            lineTo(6f, 18f)
            close()
        }
    }

    val settings = icon("settings") {
        strokePath {
            moveTo(10f, 3f)
            lineTo(14f, 3f)
            lineTo(15f, 6f)
            lineTo(17f, 7f)
            lineTo(20f, 6f)
            lineTo(22f, 10f)
            lineTo(20f, 12f)
            lineTo(20f, 14f)
            lineTo(22f, 16f)
            lineTo(20f, 20f)
            lineTo(17f, 19f)
            lineTo(15f, 20f)
            lineTo(14f, 23f)
            lineTo(10f, 23f)
            lineTo(9f, 20f)
            lineTo(7f, 19f)
            lineTo(4f, 20f)
            lineTo(2f, 16f)
            lineTo(4f, 14f)
            lineTo(4f, 12f)
            lineTo(2f, 10f)
            lineTo(4f, 6f)
            lineTo(7f, 7f)
            lineTo(9f, 6f)
            close()
        }
        strokePath {
            moveTo(15f, 12f)
            lineTo(14f, 14f)
            lineTo(12f, 15f)
            lineTo(10f, 14f)
            lineTo(9f, 12f)
            lineTo(10f, 10f)
            lineTo(12f, 9f)
            lineTo(14f, 10f)
            close()
        }
    }

    val history = icon("history") {
        strokePath {
            moveTo(4f, 8f)
            lineTo(4f, 4f)
            lineTo(8f, 4f)
            moveTo(4f, 4f)
            lineTo(8f, 7f)
            lineTo(12f, 5f)
            lineTo(17f, 6f)
            lineTo(20f, 10f)
            lineTo(20f, 15f)
            lineTo(17f, 19f)
            lineTo(12f, 21f)
            lineTo(7f, 19f)
            lineTo(4f, 15f)
            moveTo(12f, 8f)
            lineTo(12f, 13f)
            lineTo(16f, 15f)
        }
    }

    val home = icon("home") {
        strokePath {
            moveTo(3f, 11f)
            lineTo(12f, 4f)
            lineTo(21f, 11f)
            moveTo(5f, 10f)
            lineTo(5f, 20f)
            lineTo(19f, 20f)
            lineTo(19f, 10f)
            moveTo(10f, 20f)
            lineTo(10f, 14f)
            lineTo(14f, 14f)
            lineTo(14f, 20f)
        }
    }

    val diagnostics = icon("diagnostics") {
        strokePath {
            moveTo(4f, 18f)
            lineTo(4f, 6f)
            lineTo(20f, 6f)
            lineTo(20f, 15f)
            moveTo(6f, 12f)
            lineTo(9f, 12f)
            lineTo(11f, 9f)
            lineTo(13f, 15f)
            lineTo(15f, 11f)
            lineTo(18f, 11f)
            moveTo(14f, 19f)
            lineTo(17f, 22f)
            lineTo(22f, 16f)
        }
    }

    val qr = icon("qr") {
        fillPath {
            moveTo(3f, 3f)
            lineTo(10f, 3f)
            lineTo(10f, 10f)
            lineTo(3f, 10f)
            close()
            moveTo(5f, 5f)
            lineTo(8f, 5f)
            lineTo(8f, 8f)
            lineTo(5f, 8f)
            close()
            moveTo(14f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 10f)
            lineTo(14f, 10f)
            close()
            moveTo(16f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 8f)
            lineTo(16f, 8f)
            close()
            moveTo(3f, 14f)
            lineTo(10f, 14f)
            lineTo(10f, 21f)
            lineTo(3f, 21f)
            close()
            moveTo(5f, 16f)
            lineTo(8f, 16f)
            lineTo(8f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(14f, 14f)
            lineTo(17f, 14f)
            lineTo(17f, 17f)
            lineTo(14f, 17f)
            close()
            moveTo(19f, 14f)
            lineTo(21f, 14f)
            lineTo(21f, 16f)
            lineTo(19f, 16f)
            close()
            moveTo(18f, 18f)
            lineTo(21f, 18f)
            lineTo(21f, 21f)
            lineTo(18f, 21f)
            close()
            moveTo(13f, 19f)
            lineTo(16f, 19f)
            lineTo(16f, 21f)
            lineTo(13f, 21f)
            close()
        }
    }

    val overflow = icon("overflow") {
        strokePath {
            moveTo(12f, 5f)
            lineTo(12f, 5.01f)
            moveTo(12f, 12f)
            lineTo(12f, 12.01f)
            moveTo(12f, 19f)
            lineTo(12f, 19.01f)
        }
    }

    val all: List<ImageVector> = listOf(
        microphone,
        waveform,
        thinkingDots,
        buffering,
        speaker,
        check,
        pause,
        warning,
        interruptHand,
        listenEar,
        stop,
        settings,
        history,
        home,
        diagnostics,
        qr,
        overflow,
    )

    private fun icon(name: String, draw: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(draw).build()

    private fun ImageVector.Builder.strokePath(draw: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }

    private fun ImageVector.Builder.fillPath(draw: PathBuilder.() -> Unit) {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
            pathBuilder = draw,
        )
    }
}
