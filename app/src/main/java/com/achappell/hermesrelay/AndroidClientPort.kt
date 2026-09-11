package com.achappell.hermesrelay

import androidx.annotation.StringRes

internal data class AndroidClientSnapshot(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val boundaryRes: Int,
)

/** Typed seam for future Hermes session and device adapters. */
internal interface AndroidClientPort {
    fun snapshot(): AndroidClientSnapshot
}

internal object BootstrapClientPort : AndroidClientPort {
    override fun snapshot() = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
    )
}
