package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
                refreshStrategyAccordionHeader()
            }
        }
    }

    private var expandedStrategyMode = 0 // 0 = all collapsed (FlClash screenshot 5), 1 = Selector, 2 = URLTest, 3 = Fallback

    fun withCountryFlag(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "🌐 未命名节点"
        val firstCodePoint = Character.codePointAt(trimmed, 0)
        if (firstCodePoint in 0x1F1E6..0x1F1FF) return trimmed
        val upper = trimmed.uppercase()
        val flag = when {
            upper.contains("香港") || upper.contains("HK") || upper.contains("HONG KONG") -> "🇭🇰"
            upper.contains("台湾") || upper.contains("TW") || upper.contains("TAIWAN") || upper.contains("台北") -> "🇹🇼"
            upper.contains("日本") || upper.contains("JP") || upper.contains("JAPAN") || upper.contains("东京") || upper.contains("大阪") -> "🇯🇵"
            upper.contains("新加坡") || upper.contains("SG") || upper.contains("SINGAPORE") || upper.contains("狮城") -> "🇸🇬"
            upper.contains("美国") || upper.contains("US") || upper.contains("UNITED STATES") || upper.contains("洛杉矶") || upper.contains("硅谷") || upper.contains("西雅图") -> "🇺🇸"
            upper.contains("韩国") || upper.contains("KR") || upper.contains("KOREA") || upper.contains("首尔") -> "🇰🇷"
            upper.contains("英国") || upper.contains("UK") || upper.contains("GB") || upper.contains("伦敦") -> "🇬🇧"
            upper.contains("德国") || upper.contains("DE") || upper.contains("GERMANY") || upper.contains("法兰克福") -> "🇩🇪"
            upper.contains("法国") || upper.contains("FR") || upper.contains("巴黎") -> "🇫🇷"
            upper.contains("加拿大") || upper.contains("CA") || upper.contains("多伦多") -> "🇨🇦"
            upper.contains("澳大利亚") || upper.contains("澳洲") || upper.contains("AU") || upper.contains("悉尼") -> "🇦🇺"
            upper.contains("俄罗斯") || upper.contains("RU") || upper.contains("莫斯科") -> "🇷🇺"
            upper.contains("印度") || upper.contains("IN") || upper.contains("孟买") -> "🇮🇳"
            else -> "🌐"
        }
        return "$flag $trimmed"
    }

    private fun firstAvatarChar(name: String): String {
        for (ch in name) {
            if (Character.isLetterOrDigit(ch)) return ch.uppercaseChar().toString()
        }
        return name.take(1).ifBlank { "代" }
    }

    fun refreshStrategyAccordionHeader() {
        val rootView = view ?: return
        runOnDefaultDispatcher {
            val currentGroup = runCatching { DataStore.currentGroup() }.getOrNull()
            val profiles = currentGroup?.let { SagerDatabase.proxyDao.getByGroup(it.id) } ?: emptyList()
            val selectedProxy = profiles.find { it.id == DataStore.selectedProxy } ?: profiles.firstOrNull()
            val fastestProxy = profiles.filter { it.status == 1 && it.ping > 0 }.minByOrNull { it.ping }
                ?: profiles.getOrNull(1)
                ?: selectedProxy
            val fallbackProxy = profiles.filter { it.status == 1 }.getOrNull(1)
                ?: profiles.getOrNull(2)
                ?: profiles.lastOrNull()
                ?: selectedProxy

            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                val groupTitle = currentGroup?.displayName()?.takeIf { it.isNotBlank() } ?: "默认策略"
                rootView.findViewById<TextView>(R.id.tv_selector_avatar)?.text = firstAvatarChar(groupTitle)
                rootView.findViewById<TextView>(R.id.tv_selector_title)?.text = groupTitle
                rootView.findViewById<TextView>(R.id.tv_selector_current)?.text =
                    selectedProxy?.let { withCountryFlag(it.displayName()) } ?: "暂无节点，请先在「配置」中导入订阅"

                rootView.findViewById<TextView>(R.id.tv_urltest_current)?.text =
                    fastestProxy?.let {
                        val suffix = if (it.ping > 0) " (${it.ping} ms)" else ""
                        withCountryFlag(it.displayName()) + suffix
                    } ?: "⚡ 自动测速优选最低延迟节点"

                rootView.findViewById<TextView>(R.id.tv_fallback_current)?.text =
                    fallbackProxy?.let { withCountryFlag(it.displayName()) } ?: "🛡️ 节点不可用时自动切换"
            }
        }
    }

    private fun setStrategyAccordionExpanded(mode: Int) {
        val rootView = view ?: return
        expandedStrategyMode = if (expandedStrategyMode == mode && !select) 0 else mode

        val cardSelector = rootView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_group_selector)
        val cardUrlTest = rootView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_group_urltest)
        val cardFallback = rootView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_group_fallback)
        val arrowSelector = rootView.findViewById<ImageView>(R.id.iv_selector_arrow)
        val arrowUrlTest = rootView.findViewById<ImageView>(R.id.iv_urltest_arrow)
        val arrowFallback = rootView.findViewById<ImageView>(R.id.iv_fallback_arrow)
        val expandedPanel = rootView.findViewById<View>(R.id.layout_expanded_proxies)
        val expandedHint = rootView.findViewById<TextView>(R.id.tv_expanded_group_hint)

        val ctx = context ?: return
        val activeStroke = ContextCompat.getColor(ctx, R.color.fl_card_active_stroke)
        val normalStroke = ContextCompat.getColor(ctx, R.color.fl_card_stroke)
        val activeBg = ContextCompat.getColor(ctx, R.color.fl_card_active_bg)
        val normalBg = ContextCompat.getColor(ctx, R.color.fl_card_bg)

        when (expandedStrategyMode) {
            0 -> {
                cardSelector?.isVisible = true
                cardUrlTest?.isVisible = true
                cardFallback?.isVisible = true
                expandedPanel?.isVisible = false

                cardSelector?.strokeColor = normalStroke
                cardSelector?.setCardBackgroundColor(normalBg)
                cardUrlTest?.strokeColor = normalStroke
                cardUrlTest?.setCardBackgroundColor(normalBg)
                cardFallback?.strokeColor = normalStroke
                cardFallback?.setCardBackgroundColor(normalBg)

                arrowSelector?.setImageResource(R.drawable.ic_fl_chevron_down)
                arrowUrlTest?.setImageResource(R.drawable.ic_fl_chevron_down)
                arrowFallback?.setImageResource(R.drawable.ic_fl_chevron_down)
            }
            1 -> {
                cardSelector?.isVisible = true
                cardUrlTest?.isVisible = false
                cardFallback?.isVisible = false
                expandedPanel?.isVisible = true
                expandedHint?.text = "Selector · 手动选择节点"

                cardSelector?.strokeColor = activeStroke
                cardSelector?.setCardBackgroundColor(activeBg)
                arrowSelector?.setImageResource(R.drawable.ic_fl_chevron_up)
            }
            2 -> {
                cardSelector?.isVisible = false
                cardUrlTest?.isVisible = true
                cardFallback?.isVisible = false
                expandedPanel?.isVisible = true
                expandedHint?.text = "URLTest · 自动测速优选节点"

                cardUrlTest?.strokeColor = activeStroke
                cardUrlTest?.setCardBackgroundColor(activeBg)
                arrowUrlTest?.setImageResource(R.drawable.ic_fl_chevron_up)
            }
            3 -> {
                cardSelector?.isVisible = false
                cardUrlTest?.isVisible = false
                cardFallback?.isVisible = true
                expandedPanel?.isVisible = true
                expandedHint?.text = "Fallback · 故障转移节点列表"

                cardFallback?.strokeColor = activeStroke
                cardFallback?.setCardBackgroundColor(activeBg)
                arrowFallback?.setImageResource(R.drawable.ic_fl_chevron_up)
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
            view.findViewById<TextView>(R.id.tv_proxy_header_title)?.setText(titleRes)
            view.findViewById<View>(R.id.btn_proxy_close)?.apply {
                isVisible = true
                setOnClickListener { requireActivity().finish() }
            }
            view.findViewById<View>(R.id.btn_proxy_more)?.isVisible = false
        }

        val proxySearchCard = view.findViewById<View>(R.id.card_proxy_search)
        val proxySearchView = view.findViewById<SearchView>(R.id.proxy_search_view)
        proxySearchView?.setOnQueryTextListener(this)

        view.findViewById<View>(R.id.btn_proxy_search)?.setOnClickListener {
            val nowVisible = proxySearchCard?.isVisible != true
            proxySearchCard?.isVisible = nowVisible
            if (nowVisible) {
                if (expandedStrategyMode == 0) {
                    setStrategyAccordionExpanded(1)
                }
                proxySearchView?.requestFocus()
            } else {
                proxySearchView?.setQuery("", false)
                proxySearchView?.clearFocus()
            }
        }

        view.findViewById<View>(R.id.btn_proxy_more)?.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.add_profile_menu, popup.menu)
            popup.menu.removeItem(R.id.action_search)
            popup.setOnMenuItemClickListener { item ->
                onMenuItemClick(item)
            }
            popup.show()
        }

        // Strategy Group Accordion Cards
        view.findViewById<View>(R.id.card_group_selector)?.setOnClickListener {
            setStrategyAccordionExpanded(1)
        }
        view.findViewById<View>(R.id.card_group_urltest)?.setOnClickListener {
            setStrategyAccordionExpanded(2)
        }
        view.findViewById<View>(R.id.card_group_fallback)?.setOnClickListener {
            setStrategyAccordionExpanded(3)
        }

        view.findViewById<View>(R.id.btn_collapse_nodes)?.setOnClickListener {
            setStrategyAccordionExpanded(0)
        }

        view.findViewById<View>(R.id.btn_quick_urltest)?.setOnClickListener {
            urlTest()
        }

        if (select) {
            setStrategyAccordionExpanded(1)
        } else {
            setStrategyAccordionExpanded(0)
        }
        refreshStrategyAccordionHeader()

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener {
                if (adapter.groupList.size > position) {
                    showGroupQuickMenu(tab.view, adapter.groupList[position])
                }
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    fun showGroupQuickMenu(anchor: View, group: ProxyGroup) {
        val popup = PopupMenu(requireContext(), anchor)
        if (group.type == GroupType.SUBSCRIPTION) {
            popup.menu.add(0, 1, 0, R.string.group_update).setIcon(R.drawable.ic_baseline_refresh_24)
            if (!group.subscription?.link.isNullOrBlank()) {
                popup.menu.add(0, 2, 1, R.string.copy_subscription_link).setIcon(R.drawable.ic_baseline_link_24)
            }
        }
        popup.menu.add(0, 3, 2, R.string.group_edit).setIcon(R.drawable.ic_image_edit)
        popup.menu.add(0, 4, 3, R.string.test_group_proxies).setIcon(R.drawable.ic_baseline_speed_24)
        if (!group.ungrouped) {
            popup.menu.add(0, 5, 4, R.string.clear_profiles).setIcon(R.drawable.baseline_delete_sweep_24)
            popup.menu.add(0, 6, 5, R.string.delete_group).setIcon(R.drawable.ic_action_delete)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> GroupUpdater.startUpdate(group, true)
                2 -> {
                    val link = group.subscription?.link
                    if (!link.isNullOrBlank()) {
                        SagerNet.trySetPrimaryClip(link)
                        snackbar(getString(R.string.subscription_link_copied)).show()
                    }
                }
                3 -> startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
                4 -> urlTest()
                5 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher { GroupManager.clearGroup(group.id) }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                6 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.confirm)
                        .setMessage(R.string.delete_group_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher { GroupManager.deleteGroup(group.id) }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_create_subscription -> {
                startActivity(Intent(requireActivity(), GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_IS_SUBSCRIPTION, true)
                })
            }

            R.id.action_create_group_menu -> {
                startActivity(Intent(requireActivity(), GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_IS_SUBSCRIPTION, false)
                })
            }

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (ignored: UnknownHostException) {
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    socket.closeQuietly()
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                val best = test.results.filter { it.status == 1 && it.ping > 0 }.minByOrNull { it.ping }
                if (best != null && expandedStrategyMode == 2) {
                    val lastSelected = DataStore.selectedProxy
                    DataStore.selectedProxy = best.id
                    ProfileManager.postUpdate(lastSelected)
                    ProfileManager.postUpdate(best.id)
                    if (DataStore.serviceState.canStop) {
                        SagerNet.reloadService()
                    }
                    onMainDispatcher {
                        if (isAdded) {
                            snackbar("自动选择已切换至最低延迟: ${best.displayName()} (${best.ping} ms)").show()
                        }
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
                refreshStrategyAccordionHeader()
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest() // note: this is NOT in bg process
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0

                        try {
                            val result = urlTest.doTest(profile)
                            profile.status = 1
                            profile.ping = result
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = 2
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                val best = test.results.filter { it.status == 1 && it.ping > 0 }.minByOrNull { it.ping }
                if (best != null && expandedStrategyMode == 2) {
                    val lastSelected = DataStore.selectedProxy
                    DataStore.selectedProxy = best.id
                    ProfileManager.postUpdate(lastSelected)
                    ProfileManager.postUpdate(best.id)
                    if (DataStore.serviceState.canStop) {
                        SagerNet.reloadService()
                    }
                    onMainDispatcher {
                        if (isAdded) {
                            snackbar("自动选择已切换至最低延迟: ${best.displayName()} (${best.ping} ms)").show()
                        }
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
                refreshStrategyAccordionHeader()
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setHasFixedSize(true)
            configurationListView.setItemViewCacheSize(20)

            if (!select) {

                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    //
                    val oldProfile = configurationList[profile.id]
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    val index = configurationIdList.indexOf(data.id)
                    if (index != -1) {
                        val holder = layoutManager.findViewByPosition(index)
                            ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                        if (holder != null) {
                            onMainDispatcher {
                                holder.bind(holder.entity, data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() = runOnDefaultDispatcher {
                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles =
                            newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                val profileMap = newProfiles.associateBy { it.id }
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationList.clear()
                    configurationList.putAll(profileMap)
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    selectedView.visibility = View.VISIBLE
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                            pf.refreshStrategyAccordionHeader()
                        }

                    }
                }

                profileName.text = pf.withCountryFlag(proxyEntity.displayName())
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    // use new data
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

                // FlClash Latency Pill Badge
                when (proxyEntity.status) {
                    1 -> {
                        val ping = proxyEntity.ping
                        profileStatus.text = "$ping ms"
                        if (ping in 1..300) {
                            profileStatus.setBackgroundResource(R.drawable.bg_pill_fast)
                            profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_fast_text))
                        } else if (ping in 301..800) {
                            profileStatus.setBackgroundResource(R.drawable.bg_pill_medium)
                            profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_medium_text))
                        } else {
                            profileStatus.setBackgroundResource(R.drawable.bg_pill_slow)
                            profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_slow_text))
                        }
                        profileStatus.setOnClickListener(null)
                    }
                    2 -> {
                        profileStatus.text = proxyEntity.error ?: "Error"
                        profileStatus.setBackgroundResource(R.drawable.bg_pill_slow)
                        profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_slow_text))
                        profileStatus.setOnClickListener(null)
                    }
                    3 -> {
                        val err = proxyEntity.error ?: "Timeout"
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatus.text = if (msg != err) msg else "Timeout"
                        profileStatus.setBackgroundResource(R.drawable.bg_pill_slow)
                        profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_slow_text))
                        profileStatus.setOnClickListener {
                            alert(err).tryToShow()
                        }
                    }
                    0 -> {
                        profileStatus.text = "..."
                        profileStatus.setBackgroundResource(R.drawable.bg_pill_neutral)
                        profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_neutral_text))
                        profileStatus.setOnClickListener(null)
                    }
                    else -> {
                        if (proxyEntity.ping > 0) {
                            val ping = proxyEntity.ping
                            profileStatus.text = "$ping ms"
                            if (ping <= 300) {
                                profileStatus.setBackgroundResource(R.drawable.bg_pill_fast)
                                profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_fast_text))
                            } else if (ping <= 800) {
                                profileStatus.setBackgroundResource(R.drawable.bg_pill_medium)
                                profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_medium_text))
                            } else {
                                profileStatus.setBackgroundResource(R.drawable.bg_pill_slow)
                                profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_slow_text))
                            }
                        } else {
                            profileStatus.text = "-- ms"
                            profileStatus.setBackgroundResource(R.drawable.bg_pill_neutral)
                            profileStatus.setTextColor(ContextCompat.getColor(view.context, R.color.fl_pill_neutral_text))
                        }
                        profileStatus.setOnClickListener(null)
                    }
                }

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    adapter?.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        it.remove(index)
                        undoManager.remove(index to proxyEntity)
                    }
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select

                proxyEntity.nekoBean?.apply {
                    shareLayout.isGone = true
                }

                val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val started =
                    selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                selectedView.isVisible = selected

                // FlClash Active Card Highlighting
                val card = view as? com.google.android.material.card.MaterialCardView
                if (card != null) {
                    if (selected) {
                        card.strokeWidth = dp2px(2)
                        card.strokeColor = ContextCompat.getColor(view.context, R.color.fl_card_active_stroke)
                        card.setCardBackgroundColor(ContextCompat.getColor(view.context, R.color.fl_card_active_bg))
                    } else {
                        card.strokeWidth = dp2px(1)
                        card.strokeColor = ContextCompat.getColor(view.context, R.color.fl_card_stroke)
                        card.setCardBackgroundColor(ContextCompat.getColor(view.context, R.color.fl_card_bg))
                    }
                }

                if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                    shareLayer.setBackgroundColor(Color.TRANSPARENT)
                    shareButton.setImageResource(R.drawable.ic_social_share)
                    shareButton.setColorFilter(Color.GRAY)
                    shareButton.isVisible = true

                    shareLayout.setOnClickListener { anchor ->
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        if (proxyEntity.nekoBean != null) {
                            popup.menu.removeItem(R.id.action_group_configuration)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }
                }

            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

}
