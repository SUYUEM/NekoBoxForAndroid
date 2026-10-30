package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseSubscriptionUrl
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import okhttp3.internal.closeQuietly
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.zip.ZipInputStream

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    private val importConfigFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { fileUri ->
            if (fileUri != null) {
                runOnDefaultDispatcher {
                    handleImportConfigFile(fileUri)
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        // Top Header Actions:
        // 1. Update All Subscriptions (↻)
        view.findViewById<View>(R.id.btn_update_all)?.setOnClickListener {
            runOnDefaultDispatcher {
                val subs = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
                if (subs.isEmpty()) {
                    onMainDispatcher {
                        activity.snackbar("暂无远程订阅配置可更新").show()
                    }
                } else {
                    onMainDispatcher {
                        activity.snackbar("正在同步更新 ${subs.size} 个订阅配置...").show()
                    }
                    subs.forEach {
                        GroupUpdater.startUpdate(it, true)
                    }
                }
            }
        }

        // 2. Sort Profiles Bottom Sheet (☰)
        view.findViewById<View>(R.id.btn_sort_groups)?.setOnClickListener {
            showSortBottomSheet()
        }

        // 3. Add Profile Popup Menu (+)
        view.findViewById<View>(R.id.btn_add_group_popup)?.setOnClickListener { anchor ->
            showAddProfilePopup(anchor)
        }

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)
    }

    private fun showAddProfilePopup(anchor: View) {
        val ctx = context ?: return
        val popupView = LayoutInflater.from(ctx).inflate(R.layout.layout_group_add_popup, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp2px(12).toFloat()
        }

        // 1. 二维码
        popupView.findViewById<View>(R.id.item_add_qr).setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(ctx, ScannerActivity::class.java))
        }

        // 2. 文件
        popupView.findViewById<View>(R.id.item_add_file).setOnClickListener {
            popupWindow.dismiss()
            startFilesForResult(importConfigFile, "*/*")
        }

        // 3. 链接
        popupView.findViewById<View>(R.id.item_add_url).setOnClickListener {
            popupWindow.dismiss()
            showAddByUrlDialog()
        }

        // 4. 自定义
        popupView.findViewById<View>(R.id.item_add_custom).setOnClickListener {
            popupWindow.dismiss()
            val options = arrayOf("新建远程订阅配置", "新建本地自定义配置")
            MaterialAlertDialogBuilder(ctx)
                .setTitle("自定义配置")
                .setItems(options) { _, which ->
                    startActivity(Intent(ctx, GroupSettingsActivity::class.java).apply {
                        putExtra(GroupSettingsActivity.EXTRA_IS_SUBSCRIPTION, which == 0)
                    })
                }
                .show()
        }

        popupWindow.showAsDropDown(anchor, -dp2px(136), dp2px(4))
    }

    private fun showAddByUrlDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(22), dp2px(12), dp2px(22), dp2px(4))
        }

        val nameInput = EditText(ctx).apply {
            hint = "配置名称 (可选，自动识别)"
            setSingleLine()
        }
        val urlInput = EditText(ctx).apply {
            hint = "订阅链接或节点链接 (https://...)"
            maxLines = 4
        }

        val clip = SagerNet.getClipboardText().trim()
        if (clip.startsWith("http://", ignoreCase = true) ||
            clip.startsWith("https://", ignoreCase = true) ||
            clip.contains("://")
        ) {
            val subInfo = parseSubscriptionUrl(clip)
            if (subInfo != null) {
                urlInput.setText(subInfo.url)
                if (!subInfo.name.isNullOrBlank()) {
                    nameInput.setText(subInfo.name)
                }
            } else {
                urlInput.setText(clip)
            }
        }

        container.addView(nameInput)
        container.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp2px(10)
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("通过链接添加配置")
            .setView(container)
            .setPositiveButton("导入") { _, _ ->
                val rawUrl = urlInput.text?.toString()?.trim().orEmpty()
                val customName = nameInput.text?.toString()?.trim().orEmpty()
                if (rawUrl.isBlank()) {
                    activity.snackbar("请输入有效的订阅或节点链接").show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val subInfo = parseSubscriptionUrl(rawUrl)
                    val targetUrl = subInfo?.url ?: rawUrl
                    val targetName = customName.ifBlank {
                        subInfo?.name ?: guessNameFromUrl(targetUrl)
                    }
                    if (targetUrl.startsWith("http://", ignoreCase = true) ||
                        targetUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                            name = targetName
                            subscription = SubscriptionBean().apply {
                                link = targetUrl
                            }
                        }
                        val created = GroupManager.createGroup(group)
                        DataStore.selectedGroup = created.id
                        GroupUpdater.startUpdate(created, true)
                    } else {
                        // Node link(s)
                        try {
                            val proxies = RawUpdater.parseRaw(rawUrl)
                            if (!proxies.isNullOrEmpty()) {
                                val targetGroupId = if (customName.isNotBlank()) {
                                    val g = GroupManager.createGroup(ProxyGroup(name = customName, type = GroupType.BASIC))
                                    g.id
                                } else {
                                    DataStore.selectedGroupForImport()
                                }
                                for (p in proxies) {
                                    ProfileManager.createProfile(targetGroupId, p)
                                }
                                DataStore.selectedGroup = targetGroupId
                                onMainDispatcher {
                                    groupAdapter.reload()
                                    activity.snackbar("已成功导入 ${proxies.size} 个节点").show()
                                }
                            } else {
                                activity.importSubscription(Uri.parse(rawUrl))
                            }
                        } catch (e: SubscriptionFoundException) {
                            activity.importSubscription(Uri.parse(e.link))
                        } catch (e: Exception) {
                            onMainDispatcher {
                                activity.snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
            }
            .setNeutralButton("从剪贴板粘贴") { _, _ ->
                val text = SagerNet.getClipboardText().trim()
                if (text.isBlank()) {
                    activity.snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        activity.importSubscription(Uri.parse(text))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun handleImportConfigFile(fileUri: Uri) {
        try {
            val ctx = requireContext()
            val fileName = ctx.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: "local_profile.yaml"

            val proxies = mutableListOf<AbstractBean>()
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                val zip = ZipInputStream(ctx.contentResolver.openInputStream(fileUri)!!)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val text = zip.bufferedReader().readText()
                    RawUpdater.parseRaw(text, entry.name)?.let { proxies.addAll(it) }
                    zip.closeEntry()
                }
                zip.closeQuietly()
            } else {
                val text = ctx.contentResolver.openInputStream(fileUri)!!.use {
                    it.bufferedReader().readText()
                }
                RawUpdater.parseRaw(text, fileName)?.let { proxies.addAll(it) }
            }

            if (proxies.isEmpty()) {
                onMainDispatcher {
                    snackbar(getString(R.string.no_proxies_found_in_file)).show()
                }
                return
            }

            // Create a dedicated profile group named after the file (like FlClash's "qianlink.dalao233.yaml")
            val newGroup = GroupManager.createGroup(ProxyGroup(name = fileName, type = GroupType.BASIC))
            for (proxy in proxies) {
                ProfileManager.createProfile(newGroup.id, proxy)
            }
            DataStore.selectedGroup = newGroup.id
            onMainDispatcher {
                groupAdapter.reload()
                snackbar("已导入配置文件 $fileName (${proxies.size} 个节点)").show()
            }
        } catch (e: SubscriptionFoundException) {
            activity.importSubscription(Uri.parse(e.link))
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                snackbar(e.readableMessage).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSortBottomSheet() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val sheetView = LayoutInflater.from(ctx).inflate(R.layout.layout_group_sort_sheet, null)
        dialog.setContentView(sheetView)

        val rvSort = sheetView.findViewById<RecyclerView>(R.id.rv_sort_groups)
        rvSort.layoutManager = LinearLayoutManager(ctx)

        val tempList = ArrayList(groupAdapter.groupList)
        lateinit var touchHelper: ItemTouchHelper

        class SortViewHolder(itemView: View, val tvName: TextView, val ivHandle: ImageView, val divider: View) :
            RecyclerView.ViewHolder(itemView)

        val sortAdapter = object : RecyclerView.Adapter<SortViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortViewHolder {
                val root = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp2px(18), dp2px(15), dp2px(18), dp2px(15))
                }
                val tv = TextView(parent.context).apply {
                    textSize = 15.5f
                    setTextColor(ContextCompat.getColor(parent.context, R.color.fl_text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val handle = ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_fl_drag_handle)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(parent.context, R.color.fl_text_secondary)
                    )
                    layoutParams = LinearLayout.LayoutParams(dp2px(24), dp2px(24))
                }
                val div = View(parent.context).apply {
                    setBackgroundColor(ContextCompat.getColor(parent.context, R.color.fl_card_stroke))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)).apply {
                        marginStart = dp2px(18)
                        marginEnd = dp2px(18)
                    }
                }
                row.addView(tv)
                row.addView(handle)
                root.addView(row)
                root.addView(div)
                return SortViewHolder(root, tv, handle, div)
            }

            override fun onBindViewHolder(holder: SortViewHolder, position: Int) {
                val item = tempList[position]
                holder.tvName.text = item.displayName()
                holder.divider.isVisible = position < tempList.size - 1
                holder.ivHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        touchHelper.startDrag(holder)
                    }
                    false
                }
            }

            override fun getItemCount(): Int = tempList.size
        }

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from in tempList.indices && to in tempList.indices) {
                    Collections.swap(tempList, from, to)
                    sortAdapter.notifyItemMoved(from, to)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        rvSort.adapter = sortAdapter
        touchHelper.attachToRecyclerView(rvSort)

        sheetView.findViewById<View>(R.id.btn_sort_cancel).setOnClickListener {
            dialog.dismiss()
        }

        sheetView.findViewById<View>(R.id.btn_sort_confirm).setOnClickListener {
            runOnDefaultDispatcher {
                tempList.forEachIndexed { index, proxyGroup ->
                    proxyGroup.userOrder = (index + 1).toLong()
                    SagerDatabase.groupDao.updateGroup(proxyGroup)
                }
                groupAdapter.reload()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun guessNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: "远程订阅"
            host.removePrefix("www.").removePrefix("sub.").removePrefix("api.")
        } catch (_: Exception) {
            "Subscription #" + (System.currentTimeMillis() % 10000)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
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

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1) {
                val ungrouped = groups.find { it.ungrouped }
                if (ungrouped != null && SagerDatabase.proxyDao.countByGroup(ungrouped.id) == 0L) {
                    groups.removeAll { it.ungrouped }
                }
            }
            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()
                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }
    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val contentCard = binding.content
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupExpire = binding.groupExpire
        val optionsButton = binding.options
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress
        val groupCheckCircle: View = binding.root.findViewById(R.id.group_check_circle)
        val groupCheckIcon: ImageView = binding.root.findViewById(R.id.group_check_icon)

        override fun onMenuItemClick(item: MenuItem): Boolean {
            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_copy_subscription_link -> {
                    val link = proxyGroup.subscription?.link
                    if (!link.isNullOrBlank()) {
                        SagerNet.trySetPrimaryClip(link)
                        activity.snackbar(getString(R.string.subscription_link_copied)).show()
                    }
                }

                R.id.action_edit_group -> {
                    startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                        putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, proxyGroup.id)
                    })
                }

                R.id.action_test_group -> {
                    DataStore.selectedGroup = proxyGroup.id
                    activity.displayFragmentWithId(R.id.nav_configuration)
                }

                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                R.id.action_delete_group -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.delete_group_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.deleteGroup(proxyGroup.id)
                                groupAdapter.reload()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }

        @SuppressLint("SetTextI18n")
        fun bind(group: ProxyGroup) {
            proxyGroup = group

            val isActive = proxyGroup.id == DataStore.selectedGroup
            if (isActive) {
                groupCheckCircle.setBackgroundResource(R.drawable.bg_fl_check_active)
                groupCheckIcon.isVisible = true
                contentCard.strokeWidth = dp2px(2)
                contentCard.strokeColor = ContextCompat.getColor(itemView.context, R.color.fl_card_active_stroke)
                contentCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.fl_card_active_bg))
            } else {
                groupCheckCircle.setBackgroundResource(R.drawable.bg_fl_check_inactive)
                groupCheckIcon.isVisible = false
                contentCard.strokeWidth = dp2px(1)
                contentCard.strokeColor = ContextCompat.getColor(itemView.context, R.color.fl_card_stroke)
                contentCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.fl_card_bg))
            }

            itemView.setOnClickListener {
                DataStore.selectedGroup = proxyGroup.id
                groupAdapter.notifyDataSetChanged()
                activity.snackbar("已切换当前配置: ${proxyGroup.displayName()}").show()
            }

            itemView.setOnLongClickListener {
                showProfileQuickSheet(proxyGroup)
                true
            }

            optionsButton.setOnClickListener {
                showProfileQuickSheet(proxyGroup)
            }

            groupName.text = proxyGroup.displayName()

            val isUpdating = proxyGroup.id in GroupUpdater.updating
            subscriptionUpdateProgress.isVisible = isUpdating
            groupStatus.isVisible = !isUpdating

            groupExpire.isVisible = false
            val subscription = proxyGroup.subscription
            var trafficShown = false

            if (subscription != null && subscription.bytesUsed > 0L) {
                val total = subscription.bytesUsed + subscription.bytesRemaining
                groupTraffic.text = if (total > 0L && subscription.bytesRemaining > 0L) {
                    "${Formatter.formatFileSize(app, subscription.bytesUsed)} / ${Formatter.formatFileSize(app, total)}"
                } else {
                    "已用 ${Formatter.formatFileSize(app, subscription.bytesUsed)}"
                }
                trafficShown = true
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) {
                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply { used += toLong() }
                    get("download=([0-9]+)")?.apply { used += toLong() }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    if (used > 0 || total > 0) {
                        groupTraffic.text = if (total > 0) {
                            "${used.toBytesString()} / ${total.toBytesString()}"
                        } else {
                            "已用 ${used.toBytesString()}"
                        }
                        trafficShown = true
                    }
                    val expire = get("expire=([0-9]+)")?.toLongOrNull()
                    if (expire != null && expire > 0L) {
                        groupExpire.isVisible = true
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        groupExpire.text = sdf.format(Date(expire * 1000L))
                    }
                } catch (_: Exception) {
                }
            }

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    if (!trafficShown) {
                        groupTraffic.text = if (size == 0L) "空配置 (0 节点)" else "$size 个节点"
                    }
                    when (group.type) {
                        GroupType.BASIC -> {
                            groupStatus.text = "本地"
                        }
                        GroupType.SUBSCRIPTION -> {
                            val lastUp = group.subscription?.lastUpdated ?: 0
                            if (lastUp <= 0) {
                                groupStatus.text = "未同步"
                            } else {
                                groupStatus.text = formatRelativeTime(lastUp * 1000L)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showProfileQuickSheet(group: ProxyGroup) {
        selectedGroup = group
        val ctx = context ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        actions.add("设为当前活跃配置并查看节点" to {
            DataStore.selectedGroup = group.id
            groupAdapter.notifyDataSetChanged()
            activity.displayFragmentWithId(R.id.nav_configuration)
        })

        if (group.type == GroupType.SUBSCRIPTION) {
            actions.add("立即更新此订阅" to {
                GroupUpdater.startUpdate(group, true)
            })
            val link = group.subscription?.link
            if (!link.isNullOrBlank()) {
                actions.add("复制订阅链接" to {
                    SagerNet.trySetPrimaryClip(link)
                    activity.snackbar(getString(R.string.subscription_link_copied)).show()
                })
            }
        }

        if (!group.ungrouped) {
            actions.add("编辑配置属性" to {
                startActivity(Intent(ctx, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            })
        }

        actions.add("分享配置二维码" to {
            QRCodeDialog(group.toUniversalLink(), group.displayName())
                .showAllowingStateLoss(parentFragmentManager)
        })

        actions.add("导出所有节点到剪贴板" to {
            runOnDefaultDispatcher {
                val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                onMainDispatcher {
                    SagerNet.trySetPrimaryClip(links)
                    snackbar(getString(R.string.copy_toast_msg)).show()
                }
            }
        })

        if (!group.ungrouped) {
            actions.add("删除此配置" to {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.delete_group_prompt)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.deleteGroup(group.id)
                            groupAdapter.reload()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(group.displayName())
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .show()
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        if (diff < 60_000L) return "刚刚"
        val minutes = diff / 60_000L
        if (minutes < 60L) return "${minutes} 分钟前"
        val hours = minutes / 60L
        if (hours < 24L) return "${hours} 小时前"
        val days = hours / 24L
        return "${days} 天前"
    }
}
