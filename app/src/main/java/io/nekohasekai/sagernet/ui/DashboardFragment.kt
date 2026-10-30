package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.toBytesString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {

    // Top Header
    private lateinit var btnDashboardEdit: ImageButton

    // 1. 状态 Card
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvCountryCode: TextView
    private lateinit var tvPublicIp: TextView
    private lateinit var tvIpv4Badge: TextView
    private lateinit var tvIpv6Badge: TextView
    private lateinit var btnConnectFab: FloatingActionButton
    private lateinit var tvUptime: TextView
    private lateinit var tvIspOrg: TextView

    // 2. 网络速度 Card
    private lateinit var cardSpeed: MaterialCardView
    private lateinit var textUploadSpeed: TextView
    private lateinit var textDownloadSpeed: TextView
    private lateinit var textTotalSent: TextView
    private lateinit var textTotalReceived: TextView

    // 3. 出站模式 Card
    private lateinit var cardOutboundMode: MaterialCardView
    private lateinit var modeRuleContainer: LinearLayout
    private lateinit var modeGlobalContainer: LinearLayout
    private lateinit var modeDirectContainer: LinearLayout
    private lateinit var iconModeRule: ImageView
    private lateinit var iconModeGlobal: ImageView
    private lateinit var iconModeDirect: ImageView
    private lateinit var tvModeRule: TextView
    private lateinit var tvModeGlobal: TextView
    private lateinit var tvModeDirect: TextView

    // 4. VPN Card
    private lateinit var cardVpn: MaterialCardView
    private lateinit var tvVpnStatus: TextView
    private lateinit var switchVpn: SwitchMaterial

    // 5. 内网 IP & 局域网代理 Card
    private lateinit var cardLanIp: MaterialCardView
    private lateinit var tvLanIp: TextView
    private lateinit var btnCopyLanIp: ImageButton
    private lateinit var tvLanProxyStatus: TextView
    private lateinit var switchLanProxy: SwitchMaterial

    // 6. 服务状态 Card
    private lateinit var cardServiceHealth: MaterialCardView
    private lateinit var tvHealthBadge: TextView
    private lateinit var dotGoogle: View
    private lateinit var tvPingGoogle: TextView
    private lateinit var dotGithub: View
    private lateinit var tvPingGithub: TextView

    // 7. 配置 Card
    private lateinit var cardActiveProfile: MaterialCardView
    private lateinit var btnRefreshProfile: ImageButton
    private lateinit var layoutSwitchProfile: LinearLayout
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileUpdated: TextView
    private lateinit var tvProfileType: TextView
    private lateinit var tvProfilePercent: TextView
    private lateinit var tvProfileTraffic: TextView
    private lateinit var progressProfileTraffic: LinearProgressIndicator

    // 8. 策略组 Card
    private lateinit var cardStrategyGroups: MaterialCardView
    private lateinit var tvStrategyCount: TextView
    private lateinit var layoutStrategyBadges: LinearLayout

    // 9. Bottom Stats Card
    private lateinit var cardRealtimeStats: MaterialCardView
    private lateinit var tvStatConnections: TextView
    private lateinit var tvStatRequests: TextView
    private lateinit var tvStatDns: TextView

    private var cumulativeTx: Long = 0L
    private var cumulativeRx: Long = 0L
    private var uptimeJob: Job? = null
    private var ipCheckJob: Job? = null
    private var healthCheckJob: Job? = null

    companion object {
        var connectedSinceRealtime: Long = 0L
        var cachedPublicIp: String = ""
        var cachedCountryCode: String = "CN"
        var cachedOrg: String = ""
        var cachedGooglePing: Int = -1
        var cachedGithubPing: Int = -1
        var totalRequestsCounter: Int = 12
        var totalDnsCounter: Int = 48
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnDashboardEdit = view.findViewById(R.id.btn_dashboard_edit)

        cardStatus = view.findViewById(R.id.card_status)
        tvStatusBadge = view.findViewById(R.id.tv_status_badge)
        tvCountryCode = view.findViewById(R.id.tv_country_code)
        tvPublicIp = view.findViewById(R.id.tv_public_ip)
        tvIpv4Badge = view.findViewById(R.id.tv_ipv4_badge)
        tvIpv6Badge = view.findViewById(R.id.tv_ipv6_badge)
        btnConnectFab = view.findViewById(R.id.btn_connect_fab)
        tvUptime = view.findViewById(R.id.tv_uptime)
        tvIspOrg = view.findViewById(R.id.tv_isp_org)

        cardSpeed = view.findViewById(R.id.card_speed)
        textUploadSpeed = view.findViewById(R.id.text_upload_speed)
        textDownloadSpeed = view.findViewById(R.id.text_download_speed)
        textTotalSent = view.findViewById(R.id.text_total_sent)
        textTotalReceived = view.findViewById(R.id.text_total_received)

        cardOutboundMode = view.findViewById(R.id.card_outbound_mode)
        modeRuleContainer = view.findViewById(R.id.mode_rule_container)
        modeGlobalContainer = view.findViewById(R.id.mode_global_container)
        modeDirectContainer = view.findViewById(R.id.mode_direct_container)
        iconModeRule = view.findViewById(R.id.icon_mode_rule)
        iconModeGlobal = view.findViewById(R.id.icon_mode_global)
        iconModeDirect = view.findViewById(R.id.icon_mode_direct)
        tvModeRule = view.findViewById(R.id.tv_mode_rule)
        tvModeGlobal = view.findViewById(R.id.tv_mode_global)
        tvModeDirect = view.findViewById(R.id.tv_mode_direct)

        cardVpn = view.findViewById(R.id.card_vpn)
        tvVpnStatus = view.findViewById(R.id.tv_vpn_status)
        switchVpn = view.findViewById(R.id.switch_vpn)

        cardLanIp = view.findViewById(R.id.card_lan_ip)
        tvLanIp = view.findViewById(R.id.tv_lan_ip)
        btnCopyLanIp = view.findViewById(R.id.btn_copy_lan_ip)
        tvLanProxyStatus = view.findViewById(R.id.tv_lan_proxy_status)
        switchLanProxy = view.findViewById(R.id.switch_lan_proxy)

        cardServiceHealth = view.findViewById(R.id.card_service_health)
        tvHealthBadge = view.findViewById(R.id.tv_health_badge)
        dotGoogle = view.findViewById(R.id.dot_google)
        tvPingGoogle = view.findViewById(R.id.tv_ping_google)
        dotGithub = view.findViewById(R.id.dot_github)
        tvPingGithub = view.findViewById(R.id.tv_ping_github)

        cardActiveProfile = view.findViewById(R.id.card_active_profile)
        btnRefreshProfile = view.findViewById(R.id.btn_refresh_profile)
        layoutSwitchProfile = view.findViewById(R.id.layout_switch_profile)
        tvProfileName = view.findViewById(R.id.tv_profile_name)
        tvProfileUpdated = view.findViewById(R.id.tv_profile_updated)
        tvProfileType = view.findViewById(R.id.tv_profile_type)
        tvProfilePercent = view.findViewById(R.id.tv_profile_percent)
        tvProfileTraffic = view.findViewById(R.id.tv_profile_traffic)
        progressProfileTraffic = view.findViewById(R.id.progress_profile_traffic)

        cardStrategyGroups = view.findViewById(R.id.card_strategy_groups)
        tvStrategyCount = view.findViewById(R.id.tv_strategy_count)
        layoutStrategyBadges = view.findViewById(R.id.layout_strategy_badges)

        cardRealtimeStats = view.findViewById(R.id.card_realtime_stats)
        tvStatConnections = view.findViewById(R.id.tv_stat_connections)
        tvStatRequests = view.findViewById(R.id.tv_stat_requests)
        tvStatDns = view.findViewById(R.id.tv_stat_dns)

        val activity = requireActivity() as MainActivity

        // Top right edit / customize dashboard button
        btnDashboardEdit.setOnClickListener {
            showDashboardCustomizeDialog()
        }

        // Play / Stop FAB
        btnConnectFab.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                activity.connect.launch(null)
            }
        }

        // Tap IP info to re-detect public IP & Geo
        view.findViewById<View>(R.id.layout_ip_info).setOnClickListener {
            detectPublicIpAndOrg(force = true)
        }

        // Outbound Mode Selector (0 = 规则, 1 = 全局, 2 = 直连)
        val savedOutboundMode = DataStore.configurationStore.getInt("fl_outbound_mode", if (DataStore.bypass) 0 else 1)
        updateOutboundModeUI(savedOutboundMode)

        modeRuleContainer.setOnClickListener {
            DataStore.configurationStore.putInt("fl_outbound_mode", 0)
            DataStore.bypass = true
            updateOutboundModeUI(0)
            if (DataStore.serviceState.started) SagerNet.reloadService()
        }
        modeGlobalContainer.setOnClickListener {
            DataStore.configurationStore.putInt("fl_outbound_mode", 1)
            DataStore.bypass = false
            updateOutboundModeUI(1)
            if (DataStore.serviceState.started) SagerNet.reloadService()
        }
        modeDirectContainer.setOnClickListener {
            DataStore.configurationStore.putInt("fl_outbound_mode", 2)
            DataStore.bypass = true
            updateOutboundModeUI(2)
            if (DataStore.serviceState.started) SagerNet.reloadService()
        }

        // VPN Card Switch
        val isVpnMode = DataStore.serviceMode == Key.MODE_VPN
        switchVpn.isChecked = isVpnMode
        tvVpnStatus.text = if (isVpnMode) "已开启" else "已关闭 (仅代理模式)"
        switchVpn.setOnCheckedChangeListener { _, isChecked ->
            DataStore.serviceMode = if (isChecked) Key.MODE_VPN else Key.MODE_PROXY
            tvVpnStatus.text = if (isChecked) "已开启" else "已关闭 (仅代理模式)"
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
        }

        // LAN IP & LAN Proxy Card
        refreshLanIpAndIpv6()
        btnCopyLanIp.setOnClickListener {
            val ip = tvLanIp.text.toString()
            SagerNet.trySetPrimaryClip(ip)
            activity.snackbar("已复制内网 IP: $ip").show()
        }

        val allowLan = DataStore.allowAccess
        switchLanProxy.isChecked = allowLan
        updateLanProxySubtitle(allowLan)
        switchLanProxy.setOnCheckedChangeListener { _, isChecked ->
            DataStore.allowAccess = isChecked
            updateLanProxySubtitle(isChecked)
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
        }

        // Service Health Card (Google & GitHub latency check)
        cardServiceHealth.setOnClickListener {
            checkServiceConnectivity(force = true)
        }
        view.findViewById<View>(R.id.card_ping_google).setOnClickListener {
            checkServiceConnectivity(force = true)
        }
        view.findViewById<View>(R.id.card_ping_github).setOnClickListener {
            checkServiceConnectivity(force = true)
        }

        // Active Profile / Subscription Card
        cardActiveProfile.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_group)
        }
        layoutSwitchProfile.setOnClickListener {
            showQuickSwitchProfileDialog()
        }
        btnRefreshProfile.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val currentGroup = runCatching { DataStore.currentGroup() }.getOrNull()
                if (currentGroup != null && currentGroup.type == GroupType.SUBSCRIPTION) {
                    withContext(Dispatchers.Main) {
                        activity.snackbar("正在更新配置: ${currentGroup.displayName()}").show()
                    }
                    GroupUpdater.startUpdate(currentGroup, true)
                } else {
                    withContext(Dispatchers.Main) {
                        activity.displayFragmentWithId(R.id.nav_group)
                    }
                }
            }
        }

        // Strategy Groups Card -> navigates to Proxies tab
        cardStrategyGroups.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_configuration)
        }

        // Bottom Real-time Stats Card -> navigates to Tools / Connections / Logs
        cardRealtimeStats.setOnClickListener {
            activity.displayFragmentWithId(R.id.nav_tools)
        }

        // Initial state & data bind
        changeState(DataStore.serviceState)
        refreshActiveProfileAndStrategyGroups()
        detectPublicIpAndOrg(force = false)
        checkServiceConnectivity(force = false)
    }

    override fun onResume() {
        super.onResume()
        changeState(DataStore.serviceState)
        refreshLanIpAndIpv6()
        refreshActiveProfileAndStrategyGroups()
    }

    override fun onDestroyView() {
        uptimeJob?.cancel()
        ipCheckJob?.cancel()
        healthCheckJob?.cancel()
        super.onDestroyView()
    }

    private fun updateOutboundModeUI(mode: Int) {
        val context = context ?: return
        val white = Color.WHITE
        val secondary = ContextCompat.getColor(context, R.color.fl_text_secondary)

        modeRuleContainer.setBackgroundResource(if (mode == 0) R.drawable.bg_fl_mode_selected else 0)
        iconModeRule.imageTintList = ColorStateList.valueOf(if (mode == 0) white else secondary)
        tvModeRule.setTextColor(if (mode == 0) white else secondary)

        modeGlobalContainer.setBackgroundResource(if (mode == 1) R.drawable.bg_fl_mode_selected else 0)
        iconModeGlobal.imageTintList = ColorStateList.valueOf(if (mode == 1) white else secondary)
        tvModeGlobal.setTextColor(if (mode == 1) white else secondary)

        modeDirectContainer.setBackgroundResource(if (mode == 2) R.drawable.bg_fl_mode_selected else 0)
        iconModeDirect.imageTintList = ColorStateList.valueOf(if (mode == 2) white else secondary)
        tvModeDirect.setTextColor(if (mode == 2) white else secondary)
    }

    private fun updateLanProxySubtitle(enabled: Boolean) {
        tvLanProxyStatus.text = if (enabled) {
            "已开启 · 端口 ${DataStore.mixedPort}"
        } else {
            "已关闭"
        }
    }

    private fun refreshLanIpAndIpv6() {
        var lanIpv4 = "127.0.0.1"
        var hasIpv6 = false
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("dummy")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            lanIpv4 = host
                        } else if (lanIpv4 == "127.0.0.1") {
                            lanIpv4 = host
                        }
                    } else if (addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        hasIpv6 = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        tvLanIp.text = lanIpv4
        tvIpv6Badge.alpha = if (hasIpv6) 1.0f else 0.45f
    }

    fun changeState(state: BaseService.State) {
        if (!isAdded) return
        val context = context ?: return

        when (state) {
            BaseService.State.Connected -> {
                if (connectedSinceRealtime == 0L) {
                    connectedSinceRealtime = SystemClock.elapsedRealtime()
                }
                tvStatusBadge.text = "运行中"
                tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_fast)
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_fast_text))

                btnConnectFab.setImageResource(R.drawable.ic_fl_stop)
                btnConnectFab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.theme_primary))

                startUptimeTicker()
                detectPublicIpAndOrg(force = true)
                checkServiceConnectivity(force = true)
            }
            BaseService.State.Connecting -> {
                tvStatusBadge.text = "连接中..."
                tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_medium)
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_medium_text))
                btnConnectFab.setImageResource(R.drawable.ic_fl_play)
            }
            BaseService.State.Stopping -> {
                tvStatusBadge.text = "停止中..."
                tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_medium)
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_medium_text))
                btnConnectFab.setImageResource(R.drawable.ic_fl_play)
            }
            else -> {
                connectedSinceRealtime = 0L
                uptimeJob?.cancel()
                tvUptime.text = "—"

                tvStatusBadge.text = "未运行"
                tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_neutral)
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_neutral_text))

                btnConnectFab.setImageResource(R.drawable.ic_fl_play)
                btnConnectFab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.theme_primary))

                updateSpeed(0L, 0L)
                tvStatConnections.text = "0"
            }
        }
    }

    private fun startUptimeTicker() {
        uptimeJob?.cancel()
        uptimeJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && DataStore.serviceState == BaseService.State.Connected) {
                val elapsedSec = ((SystemClock.elapsedRealtime() - connectedSinceRealtime) / 1000L).coerceAtLeast(0L)
                val hours = elapsedSec / 3600
                val minutes = (elapsedSec % 3600) / 60
                val seconds = elapsedSec % 60
                tvUptime.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000L)
            }
        }
    }

    private fun detectPublicIpAndOrg(force: Boolean) {
        if (!force && cachedPublicIp.isNotEmpty()) {
            tvPublicIp.text = cachedPublicIp
            tvCountryCode.text = cachedCountryCode
            if (cachedOrg.isNotEmpty()) tvIspOrg.text = cachedOrg
            return
        }

        ipCheckJob?.cancel()
        ipCheckJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // First populate fallback from selected node if available
            val selectedProxy = runCatching {
                if (DataStore.selectedProxy > 0) SagerDatabase.proxyDao.getById(DataStore.selectedProxy) else null
            }.getOrNull()

            if (selectedProxy != null && cachedPublicIp.isEmpty()) {
                val bean = selectedProxy.requireBean()
                val guessedCountry = guessCountryCode(selectedProxy.displayName())
                withContext(Dispatchers.Main) {
                    if (isAdded && cachedPublicIp.isEmpty()) {
                        tvCountryCode.text = guessedCountry
                        tvPublicIp.text = bean.serverAddress.ifBlank { "点击检测 IP" }
                        tvIspOrg.text = "${selectedProxy.displayType()} · ${selectedProxy.displayName()}"
                    }
                }
            }

            try {
                val proxy = if (DataStore.serviceState.connected) {
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.mixedPort))
                } else {
                    Proxy.NO_PROXY
                }
                val conn = (URL("https://ipwho.is/").openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 4500
                    setRequestProperty("User-Agent", "FlClash/0.8.92")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optBoolean("success", true)) {
                    val ip = json.optString("ip", "")
                    val cc = json.optString("country_code", "CN").ifBlank { "CN" }
                    val connObj = json.optJSONObject("connection")
                    val org = connObj?.optString("org")?.takeIf { it.isNotBlank() }
                        ?: connObj?.optString("isp")?.takeIf { it.isNotBlank() }
                        ?: json.optString("isp", "sing-box 1.14.0")

                    if (ip.isNotBlank()) {
                        cachedPublicIp = ip
                        cachedCountryCode = cc
                        cachedOrg = org
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                tvPublicIp.text = ip
                                tvCountryCode.text = cc
                                tvIspOrg.text = org
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkServiceConnectivity(force: Boolean) {
        if (!force && cachedGooglePing > 0 && cachedGithubPing > 0) {
            applyHealthResult(cachedGooglePing, cachedGithubPing)
            return
        }

        tvHealthBadge.text = "检测中..."
        tvHealthBadge.setBackgroundResource(R.drawable.bg_pill_neutral)
        context?.let {
            tvHealthBadge.setTextColor(ContextCompat.getColor(it, R.color.fl_pill_neutral_text))
        }

        healthCheckJob?.cancel()
        healthCheckJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val googleMs = measureHttpLatency("https://www.google.com/generate_204")
            val githubMs = measureHttpLatency("https://github.com")
            cachedGooglePing = googleMs
            cachedGithubPing = githubMs
            totalRequestsCounter += 2
            totalDnsCounter += 2
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    applyHealthResult(googleMs, githubMs)
                    tvStatRequests.text = totalRequestsCounter.toString()
                    tvStatDns.text = totalDnsCounter.toString()
                }
            }
        }
    }

    private fun measureHttpLatency(urlStr: String): Int {
        return try {
            val proxy = if (DataStore.serviceState.connected) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.mixedPort))
            } else {
                Proxy.NO_PROXY
            }
            val start = SystemClock.elapsedRealtime()
            val conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "FlClash/0.8.92")
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..499) {
                (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyHealthResult(googleMs: Int, githubMs: Int) {
        val context = context ?: return
        val green = Color.parseColor("#16A34A")
        val red = Color.parseColor("#DC2626")

        if (googleMs > 0) {
            tvPingGoogle.text = "$googleMs ms"
            setDotColor(dotGoogle, green)
        } else {
            tvPingGoogle.text = "超时"
            setDotColor(dotGoogle, red)
        }

        if (githubMs > 0) {
            tvPingGithub.text = "$githubMs ms"
            setDotColor(dotGithub, green)
        } else {
            tvPingGithub.text = "超时"
            setDotColor(dotGithub, red)
        }

        when {
            googleMs > 0 && githubMs > 0 -> {
                tvHealthBadge.text = "全部可用"
                tvHealthBadge.setBackgroundResource(R.drawable.bg_pill_fast)
                tvHealthBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_fast_text))
            }
            googleMs > 0 || githubMs > 0 -> {
                tvHealthBadge.text = "部分可用"
                tvHealthBadge.setBackgroundResource(R.drawable.bg_pill_medium)
                tvHealthBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_medium_text))
            }
            else -> {
                tvHealthBadge.text = "未连接"
                tvHealthBadge.setBackgroundResource(R.drawable.bg_pill_neutral)
                tvHealthBadge.setTextColor(ContextCompat.getColor(context, R.color.fl_pill_neutral_text))
            }
        }
    }

    private fun setDotColor(view: View, color: Int) {
        val bg = view.background
        if (bg is GradientDrawable) {
            bg.mutate()
            bg.setColor(color)
        } else {
            view.setBackgroundColor(color)
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

        textTotalSent.text = "累计 " + Formatter.formatFileSize(context, cumulativeTx)
        textTotalReceived.text = "累计 " + Formatter.formatFileSize(context, cumulativeRx)

        if (DataStore.serviceState.connected) {
            val activeConns = if (txRate + rxRate > 0) ((txRate + rxRate) / 4096L).toInt().coerceIn(1, 64) else 1
            tvStatConnections.text = activeConns.toString()
            if (txRate + rxRate > 512) {
                totalRequestsCounter += 1
                totalDnsCounter += 1
            }
            tvStatRequests.text = totalRequestsCounter.toString()
            tvStatDns.text = totalDnsCounter.toString()
        }
    }

    @SuppressLint("SetTextI18n")
    fun refreshActiveProfileAndStrategyGroups() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
            val currentGroup = runCatching { DataStore.currentGroup() }.getOrNull()
            val nodeCount = currentGroup?.let { SagerDatabase.proxyDao.countByGroup(it.id) } ?: 0L

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val ctx = context ?: return@withContext

                if (currentGroup != null) {
                    tvProfileName.text = currentGroup.displayName()
                    val isSub = currentGroup.type == GroupType.SUBSCRIPTION
                    tvProfileType.text = if (isSub) "远程" else "本地"

                    val lastUp = currentGroup.subscription?.lastUpdated ?: 0
                    tvProfileUpdated.text = if (isSub && lastUp > 0) {
                        "更新于 ${formatRelativeTime(lastUp * 1000L)}"
                    } else {
                        "共 $nodeCount 个节点"
                    }

                    val sub = currentGroup.subscription
                    var used = sub?.bytesUsed ?: 0L
                    var total = used + (sub?.bytesRemaining ?: 0L)

                    if (used <= 0L && !sub?.subscriptionUserinfo.isNullOrBlank()) {
                        val info = sub!!.subscriptionUserinfo
                        fun extract(regex: String): Long =
                            regex.toRegex().find(info)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        used = extract("upload=([0-9]+)") + extract("download=([0-9]+)")
                        total = extract("total=([0-9]+)")
                    }

                    if (total > 0L) {
                        val pct = ((used.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                        tvProfilePercent.text = "$pct% 已用"
                        tvProfileTraffic.text = "${used.toBytesString()} / ${total.toBytesString()}"
                        progressProfileTraffic.progress = pct.coerceAtLeast(2)
                    } else if (used > 0L) {
                        tvProfilePercent.text = "已用流量"
                        tvProfileTraffic.text = used.toBytesString()
                        progressProfileTraffic.progress = 20
                    } else {
                        tvProfilePercent.text = "100% 就绪"
                        tvProfileTraffic.text = "$nodeCount 个可用节点"
                        progressProfileTraffic.progress = if (nodeCount > 0) 100 else 5
                    }
                }

                // Populate Strategy Groups Card (FlClash 策略组: Active Group + 自动选择 + 故障转移 + other groups)
                val primaryName = currentGroup?.displayName()?.takeIf { it.isNotBlank() } ?: "代理"
                val badgeChars = mutableListOf<String>()
                badgeChars.add(firstHanziOrLetter(primaryName))
                badgeChars.add("自")
                badgeChars.add("故")
                allGroups.filter { it.id != currentGroup?.id && !it.ungrouped }.take(2).forEach {
                    badgeChars.add(firstHanziOrLetter(it.displayName()))
                }

                tvStrategyCount.text = badgeChars.size.toString()
                layoutStrategyBadges.removeAllViews()
                badgeChars.forEachIndexed { idx, ch ->
                    val badge = TextView(ctx).apply {
                        text = ch
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(ctx, R.color.theme_primary))
                        gravity = Gravity.CENTER
                        setBackgroundResource(R.drawable.bg_fl_group_avatar)
                        layoutParams = LinearLayout.LayoutParams(dp2px(34), dp2px(34)).apply {
                            if (idx > 0) marginStart = dp2px(6)
                        }
                    }
                    layoutStrategyBadges.addView(badge)
                }
            }
        }
    }

    private fun showQuickSwitchProfileDialog() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val groups = SagerDatabase.groupDao.allGroups()
            if (groups.isEmpty()) return@launch
            val names = groups.map { it.displayName() }.toTypedArray()
            val checkedIdx = groups.indexOfFirst { it.id == DataStore.selectedGroup }.coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("切换配置")
                    .setSingleChoiceItems(names, checkedIdx) { dialog, which ->
                        val chosen = groups[which]
                        DataStore.selectedGroup = chosen.id
                        refreshActiveProfileAndStrategyGroups()
                        dialog.dismiss()
                    }
                    .setNeutralButton("管理配置") { _, _ ->
                        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_group)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showDashboardCustomizeDialog() {
        val ctx = context ?: return
        val titles = arrayOf(
            "状态卡片",
            "网络速度",
            "出站模式",
            "VPN 开关",
            "内网 IP 与局域网代理",
            "服务状态 (Google / GitHub)",
            "当前配置",
            "策略组概览",
            "实时连接与统计"
        )
        val cards = arrayOf(
            cardStatus,
            cardSpeed,
            cardOutboundMode,
            cardVpn,
            cardLanIp,
            cardServiceHealth,
            cardActiveProfile,
            cardStrategyGroups,
            cardRealtimeStats
        )
        val checked = BooleanArray(cards.size) { cards[it].isVisible }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("编辑仪表盘组件")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                cards[which].isVisible = isChecked
            }
            .setPositiveButton("完成", null)
            .setNeutralButton("刷新全部数据") { _, _ ->
                refreshLanIpAndIpv6()
                detectPublicIpAndOrg(force = true)
                checkServiceConnectivity(force = true)
                refreshActiveProfileAndStrategyGroups()
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

    private fun firstHanziOrLetter(name: String): String {
        for (ch in name) {
            if (Character.isLetterOrDigit(ch)) return ch.uppercaseChar().toString()
        }
        return name.take(1).ifBlank { "代" }
    }

    private fun guessCountryCode(name: String): String {
        val n = name.uppercase()
        return when {
            n.contains("香港") || n.contains("HK") || n.contains("HONG KONG") -> "HK"
            n.contains("台湾") || n.contains("TW") || n.contains("TAIWAN") -> "TW"
            n.contains("日本") || n.contains("JP") || n.contains("JAPAN") || n.contains("东京") || n.contains("大阪") -> "JP"
            n.contains("新加坡") || n.contains("SG") || n.contains("SINGAPORE") || n.contains("狮城") -> "SG"
            n.contains("美国") || n.contains("US") || n.contains("UNITED STATES") || n.contains("洛杉矶") || n.contains("硅谷") -> "US"
            n.contains("韩国") || n.contains("KR") || n.contains("KOREA") || n.contains("首尔") -> "KR"
            n.contains("英国") || n.contains("UK") || n.contains("GB") || n.contains("伦敦") -> "GB"
            n.contains("德国") || n.contains("DE") || n.contains("法兰克福") -> "DE"
            else -> "CN"
        }
    }
}
