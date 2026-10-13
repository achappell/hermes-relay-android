package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun DoorwayLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.safeDrawingPadding()) {
        val viewportHeight = constraints.maxHeight
        // Keep enough reading space for the state card even when the bars do not fit.
        val minimumRailHeight = with(LocalDensity.current) { 160.dp.roundToPx() }
        Layout(
            content = {
                Box { header() }
                Box { content() }
                Box { actions() }
            },
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { measurables, constraints ->
            val barConstraints = constraints.copy(minHeight = 0)
            val headerPlaceable = measurables[0].measure(barConstraints)
            val actionPlaceable = measurables[2].measure(barConstraints)
            val railHeight = (viewportHeight - headerPlaceable.height - actionPlaceable.height)
                .coerceAtLeast(minimumRailHeight)
            // The lazy rail must stay finite inside the outer overflow scroller.
            val railPlaceable = measurables[1].measure(
                constraints.copy(minHeight = railHeight, maxHeight = railHeight),
            )
            layout(constraints.maxWidth, headerPlaceable.height + railHeight + actionPlaceable.height) {
                headerPlaceable.placeRelative(0, 0)
                railPlaceable.placeRelative(0, headerPlaceable.height)
                actionPlaceable.placeRelative(0, headerPlaceable.height + railHeight)
            }
        }
    }
}
