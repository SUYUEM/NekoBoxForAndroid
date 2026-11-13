package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.applyStatusBarTopPadding

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val flHeader = view.findViewById<View>(R.id.header_dashboard)
            ?: view.findViewById<View>(R.id.header_proxies)
            ?: view.findViewById<View>(R.id.header_profiles)
            ?: view.findViewById<View>(R.id.header_tools)
        flHeader?.applyStatusBarTopPadding(10)

        val appbar = view.findViewById<View>(R.id.appbar)
        if (appbar != null && flHeader == null) {
            appbar.applyStatusBarTopPadding(0)
        }

        toolbar = view.findViewById(R.id.toolbar)
        val isSubTool = this is RouteFragment || this is SettingsFragment ||
                this is LogcatFragment || this is WebviewFragment || this is AboutFragment
        if (isSubTool) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_tools)
            }
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
