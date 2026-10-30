package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard), Toolbar.OnMenuItemClickListener {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var btnConnect: MaterialButton

    private lateinit var textUploadSpeed: TextView
    private lateinit var textDownloadSpeed: TextView
    private lateinit var textTotalSent: TextView
    private lateinit var textTotalReceived: TextView

    private lateinit var toggleRoutingMode: MaterialButtonToggleGroup
    private lateinit var btnModeRule: MaterialButton
    private lateinit var btnModeGlobal: MaterialButton
    private lateinit var btnModeDirect: MaterialButton

    private lateinit var activeNodeName: TextView
    private lateinit var activeNodeProtocol: TextView
    private lateinit var activeNodePing: TextView
    private lateinit var activeProfileName: TextView
    private lateinit var btnSwitchNode: MaterialButton
    private lateinit var btnPingTest: MaterialButton

    private lateinit var btnQuickRestart: MaterialButton
    private lateinit var btnQuickPingAll: MaterialButton
    private lateinit var btnQuickLogs: MaterialButton
    private lateinit var btnQuickRules: MaterialButton

    private var cumulativeTx: Long = 0L
    private var cumulativeRx: Long = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.app_name)
        toolbar.inflateMenu(R.menu.dashboard_menu)
        toolbar.setOnMenuItemClickListener(this)

        statusDot = view.findViewById(R.id.status_dot)
        statusText = view.findViewById(R.id.status_text)
        statusSubtext = view.findViewById(R.id.status_subtext)
        btnConnect = view.findViewById(R.id.btn_connect)

        textUploadSpeed = view.findViewById(R.id.text_upload_speed)
        textDownloadSpeed = view.findViewById(R.id.text_download_speed)
        textTotalSent = view.findViewById(R.id.text_total_sent)
        textTotalReceived = view.findViewById(R.id.text_total_received)

        toggleRoutingMode = view.findViewById(R.id.toggle_routing_mode)
        btnModeRule = view.findViewById(R.id.btn_mode_rule)
        btnModeGlobal = view.findViewById(R.id.btn_mode_global)
        btnModeDirect = view.findViewById(R.id.btn_mode_direct)

        activeNodeName = view.findViewById(R.id.active_node_name)
        activeNodeProtocol = view.findViewById(R.id.active_node_protocol)
        activeNodePing = view.findViewById(R.id.active_node_ping)
        activeProfileName = view.findViewById(R.id.active_profile_name)
        btnSwitchNode = view.findViewById(R.id.btn_switch_node)
        btnPingTest = view.findViewById(R.id.btn_ping_test)

        btnQuickRestart = view.findViewById(R.id.btn_quick_restart)
        btnQuickPingAll = view.findViewById(R.id.btn_quick_ping_all)
        btnQuickLogs = view.findViewById(R.id.btn_quick_logs)
        btnQuickRules = view.findViewById(R.id.btn_quick_rules)

        val activity = requireActivity() as MainActivity

        // Connect button
        btnConnect.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                activity.connect.launch(null)
            }
        }

        // Mode Selector
        if (DataStore.bypass) {
            toggleRoutingMode.check(R.id.btn_mode_rule)
        } else {
            toggleRoutingMode.check(R.id.btn_mode_global)
        }

        toggleRoutingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_mode_rule -> {
                    DataStore.bypass = true
                    if (DataStore.serviceState.started) SagerNet.reloadService()
                }
                R.id.btn_mode_global -> {
                    DataStore.bypass = false
                    if (DataStore.serviceState.started) SagerNet.reloadService()
                }
                R.id.btn_mode_direct -> {
                    // Direct mode
                    DataStore.bypass = true
                    if (DataStore.serviceState.started) SagerNet.reloadService()
                }
            }
        }

        // Switch node button
        btnSwitchNode.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_configuration)
        }

        view.findViewById<View>(R.id.card_active_proxy).setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_configuration)
        }

        // Ping test button
        btnPingTest.setOnClickListener {
            testCurrentNodeLatency()
        }

        // Quick restart core
        btnQuickRestart.setOnClickListener {
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
                activity.snackbar("Sing-box core restarted").show()
            } else {
                activity.snackbar("Service is not running").show()
            }
        }

        // Quick ping all
        btnQuickPingAll.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_configuration)
        }

        // Quick logs
        btnQuickLogs.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_logcat)
        }

        // Quick rules
        btnQuickRules.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_route)
        }

        // Initial state update
        changeState(DataStore.serviceState)
        refreshActiveProfileAndProxy()
    }

    override fun onResume() {
        super.onResume()
        changeState(DataStore.serviceState)
        refreshActiveProfileAndProxy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val activity = requireActivity() as MainActivity
        return when (item.itemId) {
            R.id.action_logcat -> activity.displayFragmentWithId(R.id.nav_logcat)
            R.id.action_about -> activity.displayFragmentWithId(R.id.nav_about)
            else -> false
        }
    }

    fun changeState(state: BaseService.State) {
        if (!isAdded) return
        val context = context ?: return

        when (state) {
            BaseService.State.Connected -> {
                setDotColor(Color.parseColor("#10B981")) // Green
                statusText.text = getString(R.string.dashboard_status_connected)
                statusSubtext.text = getString(R.string.dashboard_protected_hint)
                btnConnect.text = getString(R.string.dashboard_action_disconnect)
                btnConnect.setIconResource(R.drawable.ic_service_active)
                btnConnect.setBackgroundColor(Color.parseColor("#EF4444")) // Red to disconnect
            }
            BaseService.State.Connecting -> {
                setDotColor(Color.parseColor("#F59E0B")) // Amber
                statusText.text = getString(R.string.dashboard_status_connecting)
                statusSubtext.text = getString(R.string.dashboard_status_connecting)
                btnConnect.text = getString(R.string.connecting)
                btnConnect.setIconResource(R.drawable.ic_service_idle)
            }
            BaseService.State.Stopping -> {
                setDotColor(Color.parseColor("#F59E0B")) // Amber
                statusText.text = getString(R.string.dashboard_status_stopping)
                statusSubtext.text = getString(R.string.dashboard_status_stopping)
                btnConnect.text = getString(R.string.stopping)
                btnConnect.setIconResource(R.drawable.ic_service_idle)
            }
            else -> {
                setDotColor(Color.parseColor("#94A3B8")) // Slate Grey
                statusText.text = getString(R.string.dashboard_status_disconnected)
                statusSubtext.text = getString(R.string.dashboard_unprotected_hint)
                btnConnect.text = getString(R.string.dashboard_action_connect)
                btnConnect.setIconResource(R.drawable.ic_service_idle)
                btnConnect.setBackgroundColor(Color.parseColor("#00A389")) // Fresh Mint Theme
                updateSpeed(0L, 0L)
            }
        }
    }

    private fun setDotColor(color: Int) {
        val bg = statusDot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            statusDot.setBackgroundColor(color)
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        if (!isAdded) return
        val context = context ?: return

        cumulativeTx += txRate
        cumulativeRx += rxRate

        textUploadSpeed.text = Formatter.formatFileSize(context, txRate) + "/s"
        textDownloadSpeed.text = Formatter.formatFileSize(context, rxRate) + "/s"

        textTotalSent.text = getString(R.string.dashboard_total_sent, Formatter.formatFileSize(context, cumulativeTx))
        textTotalReceived.text = getString(R.string.dashboard_total_received, Formatter.formatFileSize(context, cumulativeRx))
    }

    fun refreshActiveProfileAndProxy() {
        if (!isAdded) return
        lifecycleScope.launch(Dispatchers.IO) {
            val selectedProxyId = DataStore.selectedProxy
            val proxyEntity = if (selectedProxyId > 0) {
                try {
                    SagerDatabase.proxyDao.getById(selectedProxyId)
                } catch (e: Exception) {
                    null
                }
            } else null

            val currentGroup = try {
                DataStore.currentGroup()
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (proxyEntity != null) {
                    activeNodeName.text = proxyEntity.displayName()
                    activeNodeProtocol.text = proxyEntity.displayType()
                    if (proxyEntity.ping > 0) {
                        activeNodePing.text = "${proxyEntity.ping} ms"
                        if (proxyEntity.ping <= 400) {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_fast)
                            activeNodePing.setTextColor(Color.parseColor("#16A34A"))
                        } else if (proxyEntity.ping <= 999) {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_medium)
                            activeNodePing.setTextColor(Color.parseColor("#D97706"))
                        } else {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_slow)
                            activeNodePing.setTextColor(Color.parseColor("#DC2626"))
                        }
                    } else {
                        activeNodePing.text = "-- ms"
                        activeNodePing.setBackgroundResource(R.drawable.bg_pill_neutral)
                        activeNodePing.setTextColor(Color.parseColor("#64748B"))
                    }
                } else {
                    activeNodeName.text = getString(R.string.dashboard_no_node)
                    activeNodeProtocol.text = "PROXY"
                    activeNodePing.text = "-- ms"
                    activeNodePing.setBackgroundResource(R.drawable.bg_pill_neutral)
                    activeNodePing.setTextColor(Color.parseColor("#64748B"))
                }

                activeProfileName.text = currentGroup?.displayName() ?: getString(R.string.dashboard_no_profile)
            }
        }
    }

    private fun testCurrentNodeLatency() {
        val activity = activity as? MainActivity ?: return
        if (!DataStore.serviceState.connected) {
            activity.snackbar("Please connect proxy first").show()
            return
        }

        activeNodePing.text = "..."
        activeNodePing.setBackgroundResource(R.drawable.bg_pill_neutral)
        activeNodePing.setTextColor(Color.parseColor("#64748B"))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val latency = activity.urlTest()
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        activeNodePing.text = "$latency ms"
                        if (latency in 1..400) {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_fast)
                            activeNodePing.setTextColor(Color.parseColor("#16A34A"))
                        } else if (latency in 401..999) {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_medium)
                            activeNodePing.setTextColor(Color.parseColor("#D97706"))
                        } else {
                            activeNodePing.setBackgroundResource(R.drawable.bg_pill_slow)
                            activeNodePing.setTextColor(Color.parseColor("#DC2626"))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        activeNodePing.text = "Timeout"
                        activeNodePing.setBackgroundResource(R.drawable.bg_pill_slow)
                        activeNodePing.setTextColor(Color.parseColor("#DC2626"))
                    }
                }
            }
        }
    }
}
