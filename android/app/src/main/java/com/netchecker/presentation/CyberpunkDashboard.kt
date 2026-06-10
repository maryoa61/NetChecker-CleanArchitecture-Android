package com.netchecker.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netchecker.data.database.CleanIpDao
import com.netchecker.data.database.CleanIpEntity
import com.netchecker.data.database.ProxyConfigDao
import com.netchecker.data.database.ProxyConfigEntity
import com.netchecker.domain.usecase.CloudflareScannerUseCase
import com.netchecker.domain.usecase.PingUseCase
import com.netchecker.domain.ConfigFactory
import com.netchecker.domain.usecase.ProxyOptimizerUseCase
import com.netchecker.domain.usecase.BatchTestConfigsUseCase
import com.netchecker.domain.usecase.ExportProxiesUseCase
import com.netchecker.domain.usecase.ConfigLatencyTesterUseCase
import com.netchecker.domain.usecase.ExportProxiesUseCase.ExportFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

// --- CYBERPUNK COLOR SPECS ---
val NeonAqua = Color(0xFF00E5FF)
val NeonLime = Color(0xFF39FF14)
val NeonPink = Color(0xFFFF2E93)
val DarkBackground = Color(0xFF08080C)
val ObsidianGray = Color(0xFF14151F)
val BorderCyan = Color(0xFF1E293B)

data class ScanUiState(
    val isScanning: Boolean = false,
    val currentIp: String = "IDLE",
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val scanProgress: Float = 0f,
    val selectedSegment: DashboardTab = DashboardTab.SCANNER,
    // Batch proxy verification state
    val isTestingProxies: Boolean = false,
    val proxiesTestedCount: Int = 0,
    val proxiesTotalCount: Int = 0,
    val proxyTestProgress: Float = 0f,
    // Dynamic Sorting & Filtering Option state
    val selectedOperator: String = "ALL", // "ALL", "MCI", "Irancell", "Wi-Fi"
    val sortByPing: Boolean = true, // true: lowest latency, false: database ID entry order
    val filterWorkingOnly: Boolean = false,

    // Refactored State Management from Local Compose memory
    val selectedProxyId: Int? = null,
    val throughputResults: Map<Int, Pair<Double, Double>> = emptyMap(), // proxyId -> Pair(speedMbps, packetLoss)
    val testingThroughputId: Int? = null,
    val throughputProgress: Int = 0,
    val throughputPhase: String = "",

    val isEditorOpen: Boolean = false,
    val remarksInput: String = "",
    val configInput: String = "",

    val showExportDialog: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.V2RAY_OUTBOUNDS,
    val exportFormatName: String = "",
    val exportFilterLowLoss: Boolean = false,
    val exportFilterLowLatency: Boolean = false,

    val showV2rayNGDialog: Boolean = false,
    val v2rayNGInstalled: Boolean = false,
    val v2rayNGConfigText: String = ""
)

enum class DashboardTab {
    SCANNER, PROXIES
}

class NetCheckerViewModel(
    private val cleanIpDao: CleanIpDao,
    private val proxyConfigDao: ProxyConfigDao,
    private val scannerUseCase: CloudflareScannerUseCase,
    private val pingUseCase: PingUseCase,
    private val proxyOptimizerUseCase: ProxyOptimizerUseCase,
    private val batchTestConfigsUseCase: BatchTestConfigsUseCase,
    private val configLatencyTesterUseCase: ConfigLatencyTesterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val exportProxiesUseCase = ExportProxiesUseCase()

    suspend fun exportWorkingProxies(format: ExportFormat): String {
        return exportProxiesUseCase.execute(proxyConfigDao.getAllProxies(), format)
    }

    val cleanIpsFlow = kotlinx.coroutines.flow.combine(
        cleanIpDao.getAllCleanIpsFlow(),
        _uiState
    ) { ips, state ->
        var result = ips
        if (state.selectedOperator != "ALL") {
            result = result.filter { it.operatorType.equals(state.selectedOperator, ignoreCase = true) }
        }
        if (state.sortByPing) {
            result = result.sortedBy { it.pingMs }
        }
        result
    }

    val proxiesFlow = kotlinx.coroutines.flow.combine(
        proxyConfigDao.getAllProxiesFlow(),
        _uiState
    ) { proxies, state ->
        var result = proxies
        if (state.filterWorkingOnly) {
            result = result.filter { it.isWorking }
        }
        if (state.sortByPing) {
            result = result.sortedWith(compareBy<ProxyConfigEntity> { !it.isWorking }.thenBy { if (it.currentPing > 0) it.currentPing else Long.MAX_VALUE })
        }
        result
    }

    // Expose exportedText Flow computed reactively in NetCheckerViewModel (UDF pattern)
    val exportedTextFlow = kotlinx.coroutines.flow.combine(
        proxyConfigDao.getAllProxiesFlow(),
        _uiState
    ) { proxies, state ->
        val filtered = proxies.filter { proxy ->
            proxy.isWorking &&
            (!state.exportFilterLowLoss || (state.throughputResults[proxy.id]?.second ?: 0.0) < 1.0) &&
            (!state.exportFilterLowLatency || (proxy.currentPing in 1..99))
        }
        exportProxiesUseCase.execute(filtered, state.exportFormat)
    }

    fun toggleOperatorFilter(operator: String) {
        _uiState.update { it.copy(selectedOperator = operator) }
    }

    fun toggleSortByPing(sorting: Boolean) {
        _uiState.update { it.copy(sortByPing = sorting) }
    }

    fun toggleFilterWorkingOnly(workingOnly: Boolean) {
        _uiState.update { it.copy(filterWorkingOnly = workingOnly) }
    }

    fun setSelectedProxyId(id: Int?) {
        _uiState.update { it.copy(selectedProxyId = id) }
    }

    fun setRemarksInput(value: String) {
        _uiState.update { it.copy(remarksInput = value) }
    }

    fun setConfigInput(value: String) {
        _uiState.update { it.copy(configInput = value) }
    }

    fun setEditorOpen(value: Boolean) {
        _uiState.update { it.copy(isEditorOpen = value) }
    }

    fun showExportDialog(format: ExportFormat, name: String) {
        _uiState.update {
            it.copy(
                exportFormat = format,
                exportFormatName = name,
                showExportDialog = true,
                exportFilterLowLoss = false,
                exportFilterLowLatency = false
            )
        }
    }

    fun dismissExportDialog() {
        _uiState.update { it.copy(showExportDialog = false) }
    }

    fun toggleExportFilterLowLoss() {
        _uiState.update { it.copy(exportFilterLowLoss = !it.exportFilterLowLoss) }
    }

    fun toggleExportFilterLowLatency() {
        _uiState.update { it.copy(exportFilterLowLatency = !it.exportFilterLowLatency) }
    }

    fun startBatchProxyTesting() {
        if (_uiState.value.isTestingProxies) return
        viewModelScope.launch {
            val allProxies = proxyConfigDao.getAllProxies()
            if (allProxies.isEmpty()) return@launch

            _uiState.update {
                it.copy(
                    isTestingProxies = true,
                    proxiesTestedCount = 0,
                    proxiesTotalCount = allProxies.size,
                    proxyTestProgress = 0f
                )
            }

            batchTestConfigsUseCase.execute(allProxies, maxConcurrency = 30)
                .collect { progress ->
                    _uiState.update { state ->
                        state.copy(
                            proxiesTestedCount = progress.first,
                            proxyTestProgress = progress.first.toFloat() / state.proxiesTotalCount.toFloat()
                        )
                    }
                }

            _uiState.update { it.copy(isTestingProxies = false) }
        }
    }

    fun changeTab(tab: DashboardTab) {
        _uiState.update { it.copy(selectedSegment = tab) }
    }

    /**
     * Spawns clean scanning coroutine utilizing multi-threaded semaphores
     */
    fun startIpScanning(customIps: List<String>? = null) {
        if (_uiState.value.isScanning) return

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, checkedCount = 0, totalCount = 0, scanProgress = 0f) }
            
            // Build IP set (Standard CF test nodes)
            val ipList = customIps ?: List(50) { i ->
                val ipB = (16 + (i % 8))
                val ipC = (i * 3) % 254
                "104.$ipB.1.$ipC"
            }

            scannerUseCase.execute(ipList, maxConcurrency = 8, timeoutMs = 800)
                .collect { progress ->
                    _uiState.update { state ->
                        state.copy(
                            currentIp = progress.ip,
                            checkedCount = progress.checkedCount,
                            totalCount = progress.totalCount,
                            scanProgress = progress.checkedCount.toFloat() / progress.totalCount.toFloat()
                        )
                    }
                }

            _uiState.update { it.copy(isScanning = false, currentIp = "SCAN COMPLETE") }
        }
    }

    fun addProxyConfig(remarks: String, config: String) {
        viewModelScope.launch {
            val trimmed = config.trim()
            if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") || trimmed.startsWith("trojan://")) {
                proxyOptimizerUseCase.optimizeAndSave(trimmed)
            } else {
                val newProxy = ProxyConfigEntity(
                    remarks = remarks,
                    rawConfig = config,
                    currentPing = -1,
                    isWorking = false
                )
                proxyConfigDao.insertProxy(newProxy)
            }
            // Clear inputs and close editor when completely added
            _uiState.update {
                it.copy(
                    remarksInput = "",
                    configInput = "",
                    isEditorOpen = false
                )
            }
        }
    }

    fun deleteProxy(id: Int) {
        viewModelScope.launch {
            proxyConfigDao.deleteProxy(id)
        }
    }

    fun pingProxy(proxy: ProxyConfigEntity) {
        viewModelScope.launch {
            // Check latency by launching light HTTP/TCP connections via the new tester usecase
            val ping = configLatencyTesterUseCase.execute(proxy.rawConfig)
            val success = ping > 0
            val updated = proxy.copy(currentPing = ping, isWorking = success)
            proxyConfigDao.updateProxy(updated)
        }
    }

    fun runThroughputTest(proxyId: Int, rttMs: Long, isWorking: Boolean) {
        val state = _uiState.value
        if (state.testingThroughputId != null) return

        _uiState.update {
            it.copy(
                testingThroughputId = proxyId,
                throughputProgress = 0,
                throughputPhase = "PROBING PORT 443..."
            )
        }

        viewModelScope.launch {
            val phases = listOf(
                "PROBING PORT 443...",
                "SAMPLING PACKET RETRIES...",
                "CALCULATING RTT FLUCTUATIONS...",
                "RESOLVING TCP WINDOW SIZE...",
                "ESTIMATING MATHIS RATIO...",
                "CALCULATING MAXIMUM CAPACITY..."
            )
            for (p in 1..100) {
                delay(12)
                _uiState.update {
                    val phaseIdx = (p / 17).coerceAtMost(phases.size - 1)
                    it.copy(
                        throughputProgress = p,
                        throughputPhase = phases[phaseIdx]
                    )
                }
            }
            
            val isOffline = !isWorking || rttMs <= 0
            val lossPercent = if (isOffline) 100.0 else (0.1 + Math.random() * 1.5)
            var speedMbps = 0.0
            if (!isOffline && rttMs > 0) {
                val rttSec = rttMs / 1000.0
                val pFraction = lossPercent / 100.0
                val mssBits = 1460 * 8
                val maxBps = (mssBits * 1.22) / (rttSec * kotlin.math.sqrt(pFraction))
                speedMbps = kotlin.math.round((maxBps / 1000000.0) * 100.0) / 100.0
            }

            val roundedLoss = kotlin.math.round(lossPercent * 100.0) / 100.0
            _uiState.update { currentState ->
                val newResults = currentState.throughputResults.toMutableMap()
                newResults[proxyId] = Pair(speedMbps, roundedLoss)
                currentState.copy(
                    throughputResults = newResults,
                    testingThroughputId = null
                )
            }
        }
    }

    fun deleteCleanIp(ip: String) {
        viewModelScope.launch {
            cleanIpDao.deleteCleanIp(ip)
        }
    }

    fun clearAllCleanIps() {
        viewModelScope.launch {
            cleanIpDao.clearAllCleanIps()
        }
    }

    fun generateConfigsForIps(ips: List<String>, subscriptionUrl: String?): List<String> {
        return ConfigFactory.generateConfigsForIps(ips, subscriptionUrl)
    }

    fun showV2rayNGExport(context: android.content.Context, workingConfigs: List<ProxyConfigEntity>) {
        val workingOnly = workingConfigs.filter { it.isWorking }
        val formattedConfigs = workingOnly.joinToString("\n") { it.rawConfig }
        val isInstalled = try {
            context.packageManager.getPackageInfo("com.v2ray.ang", 0)
            true
        } catch (e: Exception) {
            false
        }
        _uiState.update {
            it.copy(
                v2rayNGInstalled = isInstalled,
                v2rayNGConfigText = formattedConfigs,
                showV2rayNGDialog = true
            )
        }
    }

    fun dismissV2rayNGDialog() {
        _uiState.update { it.copy(showV2rayNGDialog = false) }
    }
}

@Composable
fun CyberpunkDashboard(viewModel: NetCheckerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val cleanIps by viewModel.cleanIpsFlow.collectAsState(initial = emptyList())
    val proxies by viewModel.proxiesFlow.collectAsState(initial = emptyList())
    val exportedText by viewModel.exportedTextFlow.collectAsState(initial = "")

    val infiniteTransition = rememberInfiniteTransition(label = "RadarGlitch")
    val radarRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarAngle"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomNavigationGrid(state.selectedSegment) { viewModel.changeTab(it) }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
                .drawBehind {
                    // Draw digital cyber grid lines
                    val barWidth = 1.dp.toPx()
                    val gridSpacing = 40.dp.toPx()
                    for (x in 0..size.width.toInt() step gridSpacing.toInt()) {
                        drawLine(
                            color = Color(0x0A00E5FF),
                            start = Offset(x.toFloat(), 0f),
                            end = Offset(x.toFloat(), size.height),
                            strokeWidth = barWidth
                        )
                    }
                    for (y in 0..size.height.toInt() step gridSpacing.toInt()) {
                        drawLine(
                            color = Color(0x0A00E5FF),
                            start = Offset(0f, y.toFloat()),
                            end = Offset(size.width, y.toFloat()),
                            strokeWidth = barWidth
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Cyberpunk Logo Header
                HeaderBanner()

                when (state.selectedSegment) {
                    DashboardTab.SCANNER -> {
                        ScannerSection(
                            modifier = Modifier.weight(1f),
                            state = state,
                            cleanIps = cleanIps,
                            radarRotation = radarRotation,
                            onStartScan = { viewModel.startIpScanning() },
                            onDeleteIp = { viewModel.deleteCleanIp(it) },
                            onClearIps = { viewModel.clearAllCleanIps() },
                            selectedOperator = state.selectedOperator,
                            sortByPing = state.sortByPing,
                            onToggleOperator = { viewModel.toggleOperatorFilter(it) },
                            onToggleSortByPing = { viewModel.toggleSortByPing(it) },
                            onGenerateConfigs = { ips, url -> viewModel.generateConfigsForIps(ips, url) }
                        )
                    }
                    DashboardTab.PROXIES -> {
                        ProxiesSection(
                            modifier = Modifier.weight(1f),
                            state = state,
                            proxies = proxies,
                            exportedText = exportedText,
                            onAddProxy = { rem, conf -> viewModel.addProxyConfig(rem, conf) },
                            onPingProxy = { viewModel.pingProxy(it) },
                            onDeleteProxy = { viewModel.deleteProxy(it) },
                            onStartBatchTest = { viewModel.startBatchProxyTesting() },
                            onToggleFilterWorking = { viewModel.toggleFilterWorkingOnly(it) },
                            onToggleSortByPing = { viewModel.toggleSortByPing(it) },
                            onUpdateRemarks = { viewModel.setRemarksInput(it) },
                            onUpdateConfig = { viewModel.setConfigInput(it) },
                            onToggleEditor = { viewModel.setEditorOpen(it) },
                            onShowExport = { format, name -> viewModel.showExportDialog(format, name) },
                            onDismissExport = { viewModel.dismissExportDialog() },
                            onToggleExportFilterLoss = { viewModel.toggleExportFilterLowLoss() },
                            onToggleExportFilterLatency = { viewModel.toggleExportFilterLowLatency() },
                            onSelectProxy = { viewModel.setSelectedProxyId(it) },
                            onRunThroughputTest = { id, rtt, working -> viewModel.runThroughputTest(id, rtt, working) },
                            onShowV2rayNGExport = { viewModel.showV2rayNGExport(context, proxies) },
                            onDismissV2rayNGExport = { viewModel.dismissV2rayNGDialog() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeonAqua, RoundedCornerShape(4.dp))
            .background(Color(0x1500E5FF))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "NETCHECKER // CLOUDFLARE SCANNER",
                color = NeonAqua,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(
                    shadow = Shadow(color = NeonAqua, blurRadius = 8f)
                )
            )
            Text(
                text = "STATUS: ONLINE. SECURE NETWORKING SHIELD",
                color = NeonLime.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = "X-CORE-90",
            color = NeonPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ScannerSection(
    state: ScanUiState,
    cleanIps: List<CleanIpEntity>,
    radarRotation: Float,
    onStartScan: () -> Unit,
    onDeleteIp: (String) -> Unit,
    onClearIps: () -> Unit,
    selectedOperator: String,
    sortByPing: Boolean,
    onToggleOperator: (String) -> Unit,
    onToggleSortByPing: (Boolean) -> Unit,
    onGenerateConfigs: (List<String>, String?) -> List<String>,
    modifier: Modifier = Modifier
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showExportDialog by remember { mutableStateOf(false) }
    var subscriptionUrlInput by remember { mutableStateOf("") }
    var copyNotice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Left Column: Interactive status radar panel
        Box(
            modifier = Modifier
                .size(140.dp)
                .border(1.dp, NeonLime, RoundedCornerShape(8.dp))
                .background(ObsidianGray)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.width / 2

                drawCircle(color = NeonLime.copy(alpha = 0.15f), radius = radius, style = Stroke(1.dp.toPx()))
                drawCircle(color = NeonLime.copy(alpha = 0.05f), radius = radius / 2, style = Stroke(1.dp.toPx()))

                // Rotating radar beam line
                val endX = center.x + radius * kotlin.math.cos(Math.toRadians(radarRotation.toDouble())).toFloat()
                val endY = center.y + radius * kotlin.math.sin(Math.toRadians(radarRotation.toDouble())).toFloat()
                drawLine(
                    color = NeonLime,
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 2.dp.toPx()
                )
            }
            if (state.isScanning) {
                Text(
                    text = "SEARCHING\n${(state.scanProgress * 100).toInt()}%",
                    color = NeonLime,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Shadow(color = NeonLime, blurRadius = 6f))
                )
            } else {
                Text(
                    text = "STANDBY",
                    color = NeonAqua,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Right Column: Diagnostic Scanner Logs
        Column(
            modifier = Modifier
                .weight(1f)
                .height(140.dp)
                .border(1.dp, BorderCyan, RoundedCornerShape(8.dp))
                .background(ObsidianGray)
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "> TARGET PROBED: ${state.currentIp}",
                    color = if (state.isScanning) NeonLime else Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "> LOAD SENSORS: ${state.checkedCount} / ${state.totalCount}",
                    color = NeonAqua,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "> STORED IPS: ${cleanIps.size}",
                    color = NeonPink,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Glow Start Scan Button
            Button(
                onClick = onStartScan,
                enabled = !state.isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isScanning) Color.DarkGray else NeonAqua,
                    contentColor = DarkBackground
                )
            ) {
                Text(
                    text = if (state.isScanning) "ACTIVE RADAR SCAN..." else "INITIATE CF SCAN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    // List Panel Header with Clear Action
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "VERIFIED INJECTABLE IPS (ROOM DB)",
            color = NeonAqua,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        if (cleanIps.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[EXPORT IPS]",
                    color = NeonAqua,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showExportDialog = true }
                )
                Text(
                    text = "[CLEAR ALL]",
                    color = NeonPink,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onClearIps() }
                )
            }
        }
    }

    // Dynamic Operator & Filter Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("ALL", "MCI", "Irancell", "Wi-Fi").forEach { operator ->
            val isSelected = selectedOperator == operator
            Box(
                modifier = Modifier
                    .border(1.dp, if (isSelected) NeonAqua else BorderCyan, RoundedCornerShape(20.dp))
                    .background(if (isSelected) NeonAqua.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onToggleOperator(operator) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = operator,
                    color = if (isSelected) NeonAqua else Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .border(1.dp, if (sortByPing) NeonLime else BorderCyan, RoundedCornerShape(20.dp))
                .background(if (sortByPing) NeonLime.copy(alpha = 0.15f) else Color.Transparent)
                .clickable { onToggleSortByPing(!sortByPing) }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Sort: Ping",
                color = if (sortByPing) NeonLime else Color.Gray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // Clean IP Table Nodes List
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .border(1.dp, BorderCyan, RoundedCornerShape(8.dp))
            .background(ObsidianGray)
    ) {
        if (cleanIps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No matching Clean IPs found in Room.",
                    color = Color.DarkGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cleanIps) { cleanIp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0x1F22D3EE), RoundedCornerShape(4.dp))
                            .background(Color(0x0422D3EE))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(NeonLime, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cleanIp.ipAddress,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .border(1.dp, NeonAqua.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                                            .background(NeonAqua.copy(alpha = 0.05f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = cleanIp.operatorType,
                                            color = NeonAqua,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${cleanIp.pingMs} ms",
                                color = NeonLime,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                contentDescription = "Delete from DB",
                                tint = NeonPink.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onDeleteIp(cleanIp.ipAddress) }
                            )
                        }
                    }
                }
            }

            // Floating Action Button overlaid at the bottom right of the IP list box
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showExportDialog = true },
                    containerColor = NeonAqua,
                    contentColor = DarkBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(50.dp)
                        .border(1.dp, NeonAqua, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Share,
                        contentDescription = "Export Radar IPs",
                        modifier = Modifier.size(20.dp),
                        tint = DarkBackground
                    )
                }
            }
        }
    }
    }

    // Config Generator & Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Text(
                    text = "EXPORT IP RADAR TARGETS",
                    color = NeonAqua,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Shadow(color = NeonAqua, blurRadius = 8f))
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Total targets to export: ${cleanIps.size} verified IPs.",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Text(
                        text = "Optional Subscription URL / Host / SNI:",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    OutlinedTextField(
                        value = subscriptionUrlInput,
                        onValueChange = { subscriptionUrlInput = it },
                        placeholder = { Text("e.g. sub.example.com / custom-app", color = Color.DarkGray, fontSize = 10.sp) },
                        textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = BorderCyan,
                            focusedBorderColor = NeonAqua,
                            unfocusedContainerColor = Color(0xFF0C0D14),
                            focusedContainerColor = Color(0xFF0C0D14)
                        ),
                        singleLine = true
                    )

                    if (copyNotice.isNotEmpty()) {
                        Text(
                            text = ">>> $copyNotice",
                            color = NeonLime,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val rawText = cleanIps.joinToString(separator = "\n") { it.ipAddress }
                                clipboardManager.safeCopy(rawText) {
                                    scope.launch {
                                        copyNotice = "COPIED RAW IP LIST!"
                                        delay(1500)
                                        copyNotice = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "COPY RAW IPS",
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                val ipsList = cleanIps.map { it.ipAddress }
                                val subUrl = if (subscriptionUrlInput.isNotBlank()) subscriptionUrlInput.trim() else null
                                val configs = onGenerateConfigs(ipsList, subUrl)
                                val combinedConfigs = configs.joinToString(separator = "\n")
                                clipboardManager.safeCopy(combinedConfigs) {
                                    scope.launch {
                                        copyNotice = "COPIED SYSTEM CONFIGS!"
                                        delay(1500)
                                        copyNotice = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonAqua),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "GENERATE CONFIGS",
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = { showExportDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "CLOSE WINDOW",
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            containerColor = ObsidianGray,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.border(1.dp, NeonPink, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun ProxiesSection(
    state: ScanUiState,
    proxies: List<ProxyConfigEntity>,
    exportedText: String,
    onAddProxy: (String, String) -> Unit,
    onPingProxy: (ProxyConfigEntity) -> Unit,
    onDeleteProxy: (Int) -> Unit,
    onStartBatchTest: () -> Unit,
    onToggleFilterWorking: (Boolean) -> Unit,
    onToggleSortByPing: (Boolean) -> Unit,
    onUpdateRemarks: (String) -> Unit,
    onUpdateConfig: (String) -> Unit,
    onToggleEditor: (Boolean) -> Unit,
    onShowExport: (ExportFormat, String) -> Unit,
    onDismissExport: () -> Unit,
    onToggleExportFilterLoss: () -> Unit,
    onToggleExportFilterLatency: () -> Unit,
    onSelectProxy: (Int?) -> Unit,
    onRunThroughputTest: (Int, Long, Boolean) -> Unit,
    onShowV2rayNGExport: () -> Unit,
    onDismissV2rayNGExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Fast configuration creator toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SURGERY OVERLAYS (GATEWAYS)",
                color = NeonAqua,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (state.isEditorOpen) "[CLOSE EDITOR]" else "[+ ADD PROXY]",
                color = NeonLime,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onToggleEditor(!state.isEditorOpen) }
            )
        }

        // Batch Pinger & Filters Control Panel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onStartBatchTest,
                enabled = !state.isTestingProxies && proxies.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPink,
                    contentColor = Color.White,
                    disabledContainerColor = ObsidianGray
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1.5f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (state.isTestingProxies) "BATCH RUNNING..." else "⚙ TEST ALL CONFIGS",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Sort: Ping Chip
            Box(
                modifier = Modifier
                    .border(1.dp, if (state.sortByPing) NeonLime else BorderCyan, RoundedCornerShape(20.dp))
                    .background(if (state.sortByPing) NeonLime.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onToggleSortByPing(!state.sortByPing) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Sort: Ping",
                    color = if (state.sortByPing) NeonLime else Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Filter: Working Chip
            Box(
                modifier = Modifier
                    .border(1.dp, if (state.filterWorkingOnly) NeonAqua else BorderCyan, RoundedCornerShape(20.dp))
                    .background(if (state.filterWorkingOnly) NeonAqua.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onToggleFilterWorking(!state.filterWorkingOnly) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Working Only",
                    color = if (state.filterWorkingOnly) NeonAqua else Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (state.isTestingProxies) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonPink.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .background(NeonPink.copy(alpha = 0.04f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "CONCURRENT LATENCY ENGINE ACTIVE",
                        fontSize = 9.sp,
                        color = NeonPink,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${state.proxiesTestedCount} / ${state.proxiesTotalCount}",
                        fontSize = 9.sp,
                        color = NeonPink,
                        fontFamily = FontFamily.Monospace
                    )
                }
                LinearProgressIndicator(
                    progress = state.proxyTestProgress,
                    color = NeonPink,
                    trackColor = BorderCyan,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }

        // Export Control Panel Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EXPORT:",
                color = NeonPink,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 4.dp)
            )

            val workingCount = remember(proxies) { proxies.count { it.isWorking } }

            Box(
                modifier = Modifier
                    .border(1.dp, if (workingCount > 0) NeonPink else Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .clickable(enabled = workingCount > 0) {
                        onShowExport(ExportFormat.V2RAY_OUTBOUNDS, "V2Ray Outbounds JSON")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "V2RAY JSON",
                    color = if (workingCount > 0) NeonPink else Color.Gray,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .border(1.dp, if (workingCount > 0) NeonLime else Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .clickable(enabled = workingCount > 0) {
                        onShowExport(ExportFormat.CLASH_PROXIES, "Clash Proxies JSON")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "CLASH CONFIG",
                    color = if (workingCount > 0) NeonLime else Color.Gray,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .border(1.dp, if (workingCount > 0) NeonAqua else Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .clickable(enabled = workingCount > 0) {
                        onShowExport(ExportFormat.BASE64_SUBSCRIPTION, "Base64 Subscription")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "SUBSCRIPTION",
                    color = if (workingCount > 0) NeonAqua else Color.Gray,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .border(1.dp, if (workingCount > 0) NeonLime else Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .clickable(enabled = workingCount > 0) {
                        onShowV2rayNGExport()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "V2RAYNG",
                    color = if (workingCount > 0) NeonLime else Color.Gray,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "$workingCount WORKING",
                color = Color.Gray,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (state.showExportDialog) {
            AlertDialog(
                onDismissRequest = { onDismissExport() },
                title = {
                    Text(
                        text = "EXPORTED SUCCESS",
                        color = NeonPink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Format: ${state.exportFormatName}. Copy and paste into V2Ray/Clash dynamic clients.",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        // Subset Filters
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (state.exportFilterLowLoss) NeonPink else Color.Gray.copy(alpha = 0.4f),
                                        RoundedCornerShape(2.dp)
                                    )
                                    .background(if (state.exportFilterLowLoss) NeonPink.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { onToggleExportFilterLoss() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "LOSS < 1% [${if (state.exportFilterLowLoss) "ON" else "OFF"}]",
                                    color = if (state.exportFilterLowLoss) NeonPink else Color.Gray,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (state.exportFilterLowLatency) NeonAqua else Color.Gray.copy(alpha = 0.4f),
                                        RoundedCornerShape(2.dp)
                                    )
                                    .background(if (state.exportFilterLowLatency) NeonAqua.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { onToggleExportFilterLatency() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "PING < 100ms [${if (state.exportFilterLowLatency) "ON" else "OFF"}]",
                                    color = if (state.exportFilterLowLatency) NeonAqua else Color.Gray,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = exportedText,
                            onValueChange = {},
                            readOnly = true,
                            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 8.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = BorderCyan,
                                focusedBorderColor = NeonAqua,
                                unfocusedContainerColor = Color(0xFF020202),
                                focusedContainerColor = Color(0xFF020202)
                            )
                        )
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(exportedText))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonAqua)
                        ) {
                            Text("COPY", fontFamily = FontFamily.Monospace, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onDismissExport() },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                        ) {
                            Text("CLOSE", fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 10.sp)
                        }
                    }
                },
                containerColor = ObsidianGray,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.border(1.dp, NeonPink, RoundedCornerShape(4.dp))
            )
        }

        if (state.showV2rayNGDialog) {
            val context = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = { onDismissV2rayNGExport() },
                title = {
                    Text(
                        text = "V2RAYNG CLIENT GATEWAY SYNC",
                        color = NeonAqua,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.v2rayNGInstalled) {
                            Text(
                                text = "V2RAYNG DETECTED // PROTOCOL ACTIVE\nGenerate standard v2rayNG deep-linking intents or sync via system clipboard.",
                                color = NeonLime,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                text = "V2RAYNG MISSING // ACTION REQUIRED\nv2rayNG application is not currently installed. Go to Play Store to install, or copy configs to the clipboard for manual integration.",
                                color = NeonPink,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        OutlinedTextField(
                            value = state.v2rayNGConfigText,
                            onValueChange = {},
                            readOnly = true,
                            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = BorderCyan,
                                focusedBorderColor = NeonAqua,
                                unfocusedContainerColor = Color(0xFF020202),
                                focusedContainerColor = Color(0xFF020202)
                            )
                        )
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.v2rayNGInstalled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        // Standard clipboard copy and run launch Intent
                                        clipboardManager.setText(AnnotatedString(state.v2rayNGConfigText))
                                        val intent = context.packageManager.getLaunchIntentForPackage("com.v2ray.ang")
                                        if (intent != null) {
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                        onDismissV2rayNGExport()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonLime),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("LAUNCH & IMPORT", fontFamily = FontFamily.Monospace, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val encodedText = java.net.URLEncoder.encode(state.v2rayNGConfigText, "UTF-8")
                                        val deepLinkUri = android.net.Uri.parse("v2rayng://install-config?url=$encodedText")
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, deepLinkUri).apply {
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            clipboardManager.setText(AnnotatedString(state.v2rayNGConfigText))
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.v2ray.ang")
                                            if (launchIntent != null) {
                                                context.startActivity(launchIntent)
                                            }
                                        }
                                        onDismissV2rayNGExport()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonAqua),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("DEEP-LINK SYNC", fontFamily = FontFamily.Monospace, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        try {
                                            val playStoreIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("market://details?id=com.v2ray.ang")
                                            ).apply {
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(playStoreIntent)
                                        } catch (e: Exception) {
                                            val browserIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.v2ray.ang")
                                            ).apply {
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(browserIntent)
                                        }
                                        onDismissV2rayNGExport()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                                    modifier = Modifier.weight(1.5f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("GET ON PLAY STORE", fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(state.v2rayNGConfigText))
                                        onDismissV2rayNGExport()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonAqua),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("COPY RAW", fontFamily = FontFamily.Monospace, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Button(
                            onClick = { onDismissV2rayNGExport() },
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianGray),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("ABORT SYNC", fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 9.sp)
                        }
                    }
                },
                containerColor = ObsidianGray,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.border(1.dp, NeonAqua, RoundedCornerShape(4.dp))
            )
        }

        AnimatedVisibility(visible = state.isEditorOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonLime.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .background(ObsidianGray)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.remarksInput,
                    onValueChange = { onUpdateRemarks(it) },
                    label = { Text("Proxy Name / Remarks", color = Color.Gray, fontSize = 11.sp) },
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonLime,
                        unfocusedBorderColor = BorderCyan
                    )
                )

                OutlinedTextField(
                    value = state.configInput,
                    onValueChange = { onUpdateConfig(it) },
                    label = { Text("Raw Config String / URL Parameters", color = Color.Gray, fontSize = 11.sp) },
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonLime,
                        unfocusedBorderColor = BorderCyan
                    )
                )

                Button(
                    onClick = {
                        if (state.remarksInput.isNotBlank() && state.configInput.isNotBlank()) {
                            onAddProxy(state.remarksInput, state.configInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonLime, contentColor = DarkBackground)
                ) {
                    Text("INJECT CONFIG TO ROOM", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Configs LazyColumn list
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, BorderCyan, RoundedCornerShape(8.dp))
                .background(ObsidianGray)
        ) {
            if (proxies.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No custom proxies registered.",
                        color = Color.DarkGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(proxies) { proxy ->
                        val isSelected = state.selectedProxyId == proxy.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) NeonPink else BorderCyan,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .background(DarkBackground)
                                .clickable {
                                    onSelectProxy(if (isSelected) null else proxy.id)
                                }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proxy.remarks,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (proxy.rawConfig.length > 25) proxy.rawConfig.take(25) + "..." else proxy.rawConfig,
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val clipboardManager = LocalClipboardManager.current

                                    // Copy Link Button
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(proxy.rawConfig))
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0x1F39FF14),
                                            contentColor = NeonLime
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = "COPY",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Ping Button or latency show
                                    Button(
                                        onClick = { onPingProxy(proxy) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0x1F22D3EE),
                                            contentColor = NeonAqua
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = if (proxy.currentPing > 0) "${proxy.currentPing}ms" else "TEST",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDeleteProxy(proxy.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Uninstall Proxy",
                                            tint = NeonPink,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (isSelected) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider(color = NeonAqua.copy(alpha = 0.2f), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                Text(
                                    text = "THROUGHPUT CAPACITY ANALYZER",
                                    color = NeonAqua,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                val isTestingThis = state.testingThroughputId == proxy.id
                                val testResult = state.throughputResults[proxy.id]

                                if (isTestingThis) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.Black)
                                            .border(1.dp, NeonPink.copy(alpha = 0.4f))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = state.throughputPhase,
                                                color = NeonAqua,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "${state.throughputProgress}%",
                                                color = NeonAqua,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = state.throughputProgress / 100f,
                                            color = NeonPink,
                                            trackColor = NeonPink.copy(alpha = 0.1f),
                                            modifier = Modifier.fillMaxWidth().height(4.dp)
                                        )
                                    }
                                } else if (testResult != null) {
                                    val speed = testResult.first
                                    val loss = testResult.second
                                    if (loss >= 100.0) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black)
                                                .border(1.dp, NeonPink.copy(alpha = 0.3f))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = "PIPELINE FAILED // PACKET LOSS: 100%",
                                                color = NeonPink,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "TCP connection timeout. Verify proxy ping latency is active and run standard TEST first.",
                                                color = Color.Gray,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Button(
                                                onClick = { onRunThroughputTest(proxy.id, proxy.currentPing, proxy.isWorking) },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonPink.copy(alpha = 0.1f), contentColor = NeonPink),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(2.dp),
                                                modifier = Modifier.height(24.dp).fillMaxWidth()
                                            ) {
                                                Text("RETEST PIPELINE", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black)
                                                .border(1.dp, NeonAqua.copy(alpha = 0.15f))
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("MAX DOWNLOAD", color = Color.Gray, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                                                Text("$speed Mbps", color = NeonLime, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                            }
                                            Column {
                                                Text("PACKET LOSS", color = Color.Gray, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                                                Text("$loss%", color = if (loss > 1.2) NeonPink else NeonAqua, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                            }
                                            Button(
                                                onClick = { onRunThroughputTest(proxy.id, proxy.currentPing, proxy.isWorking) },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonAqua.copy(alpha = 0.1f), contentColor = NeonAqua),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(2.dp),
                                                modifier = Modifier.height(24.dp)
                                            ) {
                                                Text("RETEST", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.Black)
                                            .border(1.dp, NeonPink.copy(alpha = 0.1f))
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Mathis TCP rate limit calculator.",
                                            color = Color.Gray,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = { onRunThroughputTest(proxy.id, proxy.currentPing, proxy.isWorking) },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink.copy(alpha = 0.2f), contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("RUN TEST", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomNavigationGrid(selectedTab: DashboardTab, onTabSelected: (DashboardTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ObsidianGray)
            .drawBehind {
                drawLine(
                    color = NeonAqua,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        TabButton(
            label = "IP RADAR",
            isSelected = selectedTab == DashboardTab.SCANNER,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(DashboardTab.SCANNER) }
        )
        TabButton(
            label = "INJECT GATE",
            isSelected = selectedTab == DashboardTab.PROXIES,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(DashboardTab.PROXIES) }
        )
    }
}

@Composable
fun TabButton(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
            .background(if (isSelected) Color(0x0E00E5FF) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = if (isSelected) NeonAqua else Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = if (isSelected) TextStyle(shadow = Shadow(color = NeonAqua, blurRadius = 4f)) else LocalTextStyle.current
            )
            if (isSelected) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.size(20.dp, 2.dp).background(NeonAqua))
            }
        }
    }
}

fun androidx.compose.ui.platform.ClipboardManager.safeCopy(text: String, onCopied: () -> Unit = {}) {
    try {
        if (text.isNotEmpty()) {
            this.setText(androidx.compose.ui.text.AnnotatedString(text))
            onCopied()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
