package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutEditGroupBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*

class GroupSettingsActivity : ThemedActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "is_subscription"
    }

    private lateinit var binding: LayoutEditGroupBinding
    private var editingId: Long = 0L
    private var selectedOrder: Int = GroupOrder.ORIGIN
    private var selectedFrontProxyId: Long = -1L
    private var selectedLandingProxyId: Long = -1L
    private var isAdvancedExpanded = false

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
    }

    private val selectFrontProxy = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            selectedFrontProxyId = if (id > 0) id else -1L
            updateProxyButtons()
        }
    }

    private val selectLandingProxy = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            selectedLandingProxyId = if (id > 0) id else -1L
            updateProxyButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutEditGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
            setTitle(if (editingId == 0L) R.string.group_create else R.string.group_edit)
        }

        setupOrderDropdown()
        setupTypeToggle()
        setupAdvancedCard()
        setupActionButtons()

        if (editingId == 0L) {
            val isSub = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, true)
            if (isSub) {
                binding.groupTypeToggle.check(R.id.btn_type_subscription)
            } else {
                binding.groupTypeToggle.check(R.id.btn_type_basic)
            }
            updateModeUi(isSub)
            checkClipboardForLink()
        } else {
            loadExistingGroup(editingId)
        }
    }

    private fun setupOrderDropdown() {
        val orderEntries = resources.getStringArray(R.array.group_orders)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, orderEntries)
        binding.inputGroupOrder.setAdapter(adapter)
        binding.inputGroupOrder.setText(orderEntries.getOrNull(selectedOrder) ?: orderEntries[0], false)
        binding.inputGroupOrder.setOnItemClickListener { _, _, position, _ ->
            selectedOrder = position
        }
    }

    private fun setupTypeToggle() {
        binding.groupTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val isSub = (checkedId == R.id.btn_type_subscription)
                updateModeUi(isSub)
                if (isSub && editingId == 0L) {
                    checkClipboardForLink()
                }
            }
        }
    }

    private fun updateModeUi(isSubscription: Boolean) {
        binding.layoutSubscriptionSection.isVisible = isSubscription
        binding.layoutSubscriptionAdvanced.isVisible = isSubscription
        if (!isSubscription) {
            binding.cardClipboardHint.isVisible = false
            binding.btnSaveAndUpdate.text = getString(R.string.apply)
            binding.btnSaveAndUpdate.setIconResource(R.drawable.ic_action_done)
            binding.btnSaveOnly.isVisible = false
        } else {
            binding.btnSaveAndUpdate.text = getString(R.string.save_and_update)
            binding.btnSaveAndUpdate.setIconResource(R.drawable.ic_baseline_download_24)
            binding.btnSaveOnly.isVisible = true
        }
    }

    private fun setupAdvancedCard() {
        binding.advancedHeader.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            binding.advancedContent.isVisible = isAdvancedExpanded
            binding.advancedArrow.animate().rotation(if (isAdvancedExpanded) 180f else 0f).setDuration(200).start()
        }

        binding.btnFrontProxy.setOnClickListener {
            val options = arrayOf(
                getString(R.string.clear_proxy_selection),
                getString(R.string.choose_proxy)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.front_proxy)
                .setItems(options) { _, which ->
                    if (which == 0) {
                        selectedFrontProxyId = -1L
                        updateProxyButtons()
                    } else {
                        selectFrontProxy.launch(Intent(this, ProfileSelectActivity::class.java))
                    }
                }
                .show()
        }

        binding.btnLandingProxy.setOnClickListener {
            val options = arrayOf(
                getString(R.string.clear_proxy_selection),
                getString(R.string.choose_proxy)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.landing_proxy)
                .setItems(options) { _, which ->
                    if (which == 0) {
                        selectedLandingProxyId = -1L
                        updateProxyButtons()
                    } else {
                        selectLandingProxy.launch(Intent(this, ProfileSelectActivity::class.java))
                    }
                }
                .show()
        }
    }

    private fun updateProxyButtons() {
        runOnDefaultDispatcher {
            val frontName = if (selectedFrontProxyId > 0) {
                ProfileManager.getProfile(selectedFrontProxyId)?.displayName() ?: getString(R.string.none)
            } else getString(R.string.none)

            val landingName = if (selectedLandingProxyId > 0) {
                ProfileManager.getProfile(selectedLandingProxyId)?.displayName() ?: getString(R.string.none)
            } else getString(R.string.none)

            onMainDispatcher {
                binding.btnFrontProxy.text = frontName
                binding.btnLandingProxy.text = landingName
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnPasteClipboard.setOnClickListener {
            val clip = SagerNet.getClipboardText().trim()
            if (clip.isNotBlank()) {
                fillSubscriptionLink(clip)
            } else {
                snackbar(getString(R.string.clipboard_empty)).show()
            }
        }

        binding.btnScanQr.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }

        binding.btnSaveAndUpdate.setOnClickListener {
            save(autoUpdate = true)
        }

        binding.btnSaveOnly.setOnClickListener {
            save(autoUpdate = false)
        }
    }

    private fun checkClipboardForLink() {
        val clip = SagerNet.getClipboardText().trim()
        val lower = clip.lowercase()
        val isSubUrl = lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("mihomo://") ||
                lower.startsWith("clash") ||
                lower.startsWith("sub://") ||
                lower.startsWith("shadowrocket://") ||
                lower.startsWith("sing-box://") ||
                lower.startsWith("v2rayng://")
        if (isSubUrl && binding.inputSubscriptionLink.text.isNullOrBlank()) {
            binding.cardClipboardHint.isVisible = true
            binding.textClipboardUrl.text = clip
            binding.cardClipboardHint.setOnClickListener {
                fillSubscriptionLink(clip)
                binding.cardClipboardHint.isVisible = false
            }
        } else {
            binding.cardClipboardHint.isVisible = false
        }
    }

    private fun fillSubscriptionLink(rawText: String) {
        val subInfo = parseSubscriptionUrl(rawText)
        val actualUrl = subInfo?.url ?: rawText
        binding.inputSubscriptionLink.setText(actualUrl)
        if (binding.inputGroupName.text.isNullOrBlank()) {
            val suggestedName = subInfo?.name?.takeIf { it.isNotBlank() }
                ?: runCatching { Uri.parse(actualUrl).host }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                ?: getString(R.string.group_type_subscription)
            binding.inputGroupName.setText(suggestedName)
        }
        snackbar(getString(R.string.clipboard_link_detected)).show()
    }

    private fun loadExistingGroup(id: Long) {
        runOnDefaultDispatcher {
            val entity = SagerDatabase.groupDao.getById(id) ?: run {
                onMainDispatcher { finish() }
                return@runOnDefaultDispatcher
            }

            selectedOrder = entity.order
            selectedFrontProxyId = entity.frontProxy
            selectedLandingProxyId = entity.landingProxy

            val orderEntries = resources.getStringArray(R.array.group_orders)

            onMainDispatcher {
                binding.inputGroupName.setText(entity.name ?: "")
                binding.inputGroupOrder.setText(orderEntries.getOrNull(selectedOrder) ?: orderEntries[0], false)
                binding.switchIsSelector.isChecked = entity.isSelector

                val isSub = (entity.type == GroupType.SUBSCRIPTION)
                if (isSub) {
                    binding.groupTypeToggle.check(R.id.btn_type_subscription)
                } else {
                    binding.groupTypeToggle.check(R.id.btn_type_basic)
                }
                updateModeUi(isSub)

                val sub = entity.subscription
                if (sub != null) {
                    binding.inputSubscriptionLink.setText(sub.link ?: "")
                    binding.switchAutoUpdate.isChecked = sub.autoUpdate ?: true
                    binding.inputAutoUpdateDelay.setText((sub.autoUpdateDelay ?: 1440).toString())
                    binding.switchUpdateWhenConnected.isChecked = sub.updateWhenConnectedOnly ?: false
                    binding.inputUserAgent.setText(sub.customUserAgent ?: "")
                    binding.switchDeduplication.isChecked = sub.deduplication ?: false
                    binding.switchForceResolve.isChecked = sub.forceResolve ?: false
                }

                updateProxyButtons()
            }
        }
    }

    private fun save(autoUpdate: Boolean) {
        val isSubscription = binding.btnTypeSubscription.isChecked
        val rawLink = binding.inputSubscriptionLink.text?.toString()?.trim() ?: ""

        if (isSubscription && rawLink.isBlank()) {
            binding.subscriptionLinkLayout.error = getString(R.string.group_subscription_link) + " " + getString(R.string.empty)
            binding.inputSubscriptionLink.requestFocus()
            return
        }
        binding.subscriptionLinkLayout.error = null

        val subInfo = if (isSubscription) parseSubscriptionUrl(rawLink) else null
        val actualUrl = subInfo?.url ?: rawLink

        var name = binding.inputGroupName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) {
            name = subInfo?.name?.takeIf { it.isNotBlank() }
                ?: if (isSubscription) {
                    runCatching { Uri.parse(actualUrl).host }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                        ?: getString(R.string.group_type_subscription)
                } else {
                    getString(R.string.group_type_basic)
                }
        }

        runOnDefaultDispatcher {
            var groupToUpdate: ProxyGroup? = null
            if (editingId == 0L) {
                val group = ProxyGroup(
                    name = name,
                    type = if (isSubscription) GroupType.SUBSCRIPTION else GroupType.BASIC,
                    order = selectedOrder,
                    isSelector = binding.switchIsSelector.isChecked,
                    frontProxy = selectedFrontProxyId,
                    landingProxy = selectedLandingProxyId
                )
                if (isSubscription) {
                    group.subscription = SubscriptionBean().applyDefaultValues().apply {
                        link = actualUrl
                        autoUpdate = binding.switchAutoUpdate.isChecked
                        autoUpdateDelay = binding.inputAutoUpdateDelay.text?.toString()?.toIntOrNull()?.coerceAtLeast(15) ?: 1440
                        updateWhenConnectedOnly = binding.switchUpdateWhenConnected.isChecked
                        customUserAgent = binding.inputUserAgent.text?.toString()?.trim() ?: ""
                        deduplication = binding.switchDeduplication.isChecked
                        forceResolve = binding.switchForceResolve.isChecked
                    }
                }
                val created = GroupManager.createGroup(group)
                groupToUpdate = created
            } else {
                val entity = SagerDatabase.groupDao.getById(editingId) ?: run {
                    onMainDispatcher { finish() }
                    return@runOnDefaultDispatcher
                }
                entity.name = name
                val oldType = entity.type
                entity.type = if (isSubscription) GroupType.SUBSCRIPTION else GroupType.BASIC
                entity.order = selectedOrder
                entity.isSelector = binding.switchIsSelector.isChecked
                entity.frontProxy = selectedFrontProxyId
                entity.landingProxy = selectedLandingProxyId

                if (isSubscription) {
                    val sub = entity.subscription ?: SubscriptionBean().applyDefaultValues()
                    val keepUserInfo = (oldType == GroupType.SUBSCRIPTION && sub.link == actualUrl)
                    if (!keepUserInfo) {
                        sub.subscriptionUserinfo = ""
                    }
                    sub.link = actualUrl
                    sub.autoUpdate = binding.switchAutoUpdate.isChecked
                    sub.autoUpdateDelay = binding.inputAutoUpdateDelay.text?.toString()?.toIntOrNull()?.coerceAtLeast(15) ?: 1440
                    sub.updateWhenConnectedOnly = binding.switchUpdateWhenConnected.isChecked
                    sub.customUserAgent = binding.inputUserAgent.text?.toString()?.trim() ?: ""
                    sub.deduplication = binding.switchDeduplication.isChecked
                    sub.forceResolve = binding.switchForceResolve.isChecked
                    entity.subscription = sub
                }
                GroupManager.updateGroup(entity)
                groupToUpdate = entity
            }

            if (autoUpdate && groupToUpdate?.type == GroupType.SUBSCRIPTION) {
                GroupUpdater.startUpdate(groupToUpdate, true)
            }

            onMainDispatcher {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    if (autoUpdate && groupToUpdate?.type == GroupType.SUBSCRIPTION) R.string.save_and_update else R.string.action_success,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (editingId != 0L) {
            menu.add(0, R.id.action_delete, 0, R.string.delete)
                .setIcon(R.drawable.ic_action_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm)
            .setMessage(R.string.delete_group_prompt)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(editingId)
                    onMainDispatcher { finish() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

}
