package com.achappell.hermesrelay

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat

/**
 * Compose 1.12's accessibility-focus action does not reveal a clipped target.
 * Its separate Show on screen action does, using the target's scroll ancestors.
 * Keep both actions on the original provider; never request keyboard focus.
 */
@Composable
internal fun RevealAccessibilityFocus() {
    val view = LocalView.current
    DisposableEffect(view) {
        val original = checkNotNull(ViewCompat.getAccessibilityDelegate(view))
        val provider = checkNotNull(original.getAccessibilityNodeProvider(view))
        val revealing = FocusRevealingDelegate(original, provider)
        ViewCompat.setAccessibilityDelegate(view, revealing)
        onDispose {
            if (ViewCompat.getAccessibilityDelegate(view) === revealing) {
                ViewCompat.setAccessibilityDelegate(view, original)
            }
        }
    }
}

private class FocusRevealingDelegate(
    private val original: AccessibilityDelegateCompat,
    provider: AccessibilityNodeProviderCompat,
) : AccessibilityDelegateCompat() {
    private val revealingProvider = object : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int) =
            provider.createAccessibilityNodeInfo(virtualViewId)

        override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int) =
            provider.findAccessibilityNodeInfosByText(text, virtualViewId)

        override fun findFocus(focus: Int) = provider.findFocus(focus)

        override fun addExtraDataToAccessibilityNodeInfo(
            virtualViewId: Int,
            info: AccessibilityNodeInfoCompat,
            extraDataKey: String,
            arguments: Bundle?,
        ) = provider.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val handled = provider.performAction(virtualViewId, action, arguments)
            if (handled && action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                provider.performAction(virtualViewId, android.R.id.accessibilityActionShowOnScreen, null)
            }
            return handled
        }
    }

    override fun getAccessibilityNodeProvider(host: View) = revealingProvider

    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.dispatchPopulateAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.onInitializeAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) =
        original.onInitializeAccessibilityNodeInfo(host, info)

    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.onPopulateAccessibilityEvent(host, event)

    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent) =
        original.onRequestSendAccessibilityEvent(host, child, event)

    override fun sendAccessibilityEvent(host: View, eventType: Int) =
        original.sendAccessibilityEvent(host, eventType)

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
        original.sendAccessibilityEventUnchecked(host, event)

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) =
        original.performAccessibilityAction(host, action, args)
}
