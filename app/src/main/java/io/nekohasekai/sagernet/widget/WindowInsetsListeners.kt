package io.nekohasekai.sagernet.widget

import android.content.Context
import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.ktx.dp2px

fun Context.getStatusBarHeight(): Int {
    val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
}

fun Context.getNavigationBarHeight(): Int {
    val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
}

fun View.applyStatusBarTopPadding(extraDp: Int = 10) {
    val fallbackHeight = context.getStatusBarHeight()
    val extraPx = dp2px(extraDp)
    if (fallbackHeight > 0) {
        updatePadding(top = fallbackHeight + extraPx)
    }
    ViewCompat.getRootWindowInsets(this)?.let { insets ->
        val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
        val topInsets = maxOf(statusBars, displayCutout)
        if (topInsets > 0) {
            updatePadding(top = topInsets + extraPx)
        }
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
        val topInsets = maxOf(statusBars, displayCutout)
        val finalTop = if (topInsets > 0) topInsets else fallbackHeight
        v.updatePadding(top = finalTop + extraPx)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

object ListListener : OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
    }
}
