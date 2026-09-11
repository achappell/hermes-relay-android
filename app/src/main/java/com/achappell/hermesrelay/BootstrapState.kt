package com.achappell.hermesrelay

import androidx.annotation.StringRes

internal object BootstrapState {
    @get:StringRes
    val titleRes: Int
        get() = R.string.bootstrap_title

    @get:StringRes
    val descriptionRes: Int
        get() = R.string.bootstrap_description

    @get:StringRes
    val boundaryRes: Int
        get() = R.string.bootstrap_boundary
}
