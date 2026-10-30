package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = requireActivity() as MainActivity

        view.findViewById<View>(R.id.tool_item_route)?.setOnClickListener {
            mainActivity.displayFragmentWithId(R.id.nav_route)
        }

        view.findViewById<View>(R.id.tool_item_apps)?.setOnClickListener {
            DataStore.proxyApps = true
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
        }

        view.findViewById<View>(R.id.tool_item_settings)?.setOnClickListener {
            mainActivity.displayFragmentWithId(R.id.nav_settings)
        }

        view.findViewById<View>(R.id.tool_item_logcat)?.setOnClickListener {
            mainActivity.displayFragmentWithId(R.id.nav_logcat)
        }

        view.findViewById<View>(R.id.tool_item_traffic)?.setOnClickListener {
            mainActivity.displayFragmentWithId(R.id.nav_traffic)
        }

        view.findViewById<View>(R.id.tool_item_about)?.setOnClickListener {
            mainActivity.displayFragmentWithId(R.id.nav_about)
        }

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

        val binding = LayoutToolsBinding.bind(view)
        binding.toolsPager.adapter = ToolsAdapter(tools)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener {
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
