package com.monos.app.ui

import android.graphics.Matrix
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monos.app.di.AppModule
import com.monos.app.network.DownloadStatus
import com.monos.app.ui.components.X11CanvasView
import com.monos.app.ui.components.CustomKeyboardOverlay
import com.monos.app.ui.components.AdbScriptExportDialog
import com.monos.app.virtualization.ContingencyManager
import com.monos.app.ui.theme.*

// Custom Bezier Curve Shape creating a U-shaped cutout in the middle of Bottom Navigation Bar
class UCutoutShape(
    private val cutoutRadius: Float = 110f,
    private val cornerRadius: Float = 36f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        val path = Path().apply {
            moveTo(0f, cornerRadius)
            // Top-left corner
            quadraticBezierTo(0f, 0f, cornerRadius, 0f)
            
            // Draw line to cutout start
            val cutoutStartX = (size.width / 2) - cutoutRadius
            lineTo(cutoutStartX, 0f)
            
            // Bezier U-cutout curve
            val controlX1 = cutoutStartX + (cutoutRadius / 2f)
            val controlY1 = cutoutRadius
            val controlX2 = cutoutStartX + (cutoutRadius * 1.5f)
            val controlY2 = cutoutRadius
            val cutoutEndX = (size.width / 2f) + cutoutRadius
            
            cubicTo(controlX1, controlY1, controlX2, controlY2, cutoutEndX, 0f)
            
            // Draw to top-right corner
            lineTo(size.width - cornerRadius, 0f)
            quadraticBezierTo(size.width, 0f, size.width, cornerRadius)
            
            // Bottom edges
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    downloadStatus: DownloadStatus,
    virtualizationActive: Boolean,
    adbConnected: Boolean,
    phantomProcessDisabled: Boolean,
    displayState: DisplayState,
    displayViewModel: X11DisplayViewModel,
    onStartDownload: (String) -> Unit,
    onPauseDownload: () -> Unit,
    onToggleVirtualization: () -> Unit,
    onConnectAdb: () -> Unit,
    onDisablePhantomKiller: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appModule = remember { AppModule(context) }
    val contingencyManager = appModule.contingencyManager

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAdbExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "MONOS GUEST SYSTEM",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PrimaryNeon
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        containerColor = BackgroundDark,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        // Nav bar height is 80dp. Dashboard does not need keyboard space.
        // CanvasTab manages its own viewport compression internally.
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Content area: leave space for bottom nav bar only
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp)
            ) {
                when (selectedTab) {
                    0 -> DashboardTab(
                        downloadStatus = downloadStatus,
                        virtualizationActive = virtualizationActive,
                        adbConnected = adbConnected,
                        phantomProcessDisabled = phantomProcessDisabled,
                        displayState = displayState,
                        displayViewModel = displayViewModel,
                        contingencyManager = contingencyManager,
                        onStartDownload = onStartDownload,
                        onPauseDownload = onPauseDownload,
                        onToggleVirtualization = onToggleVirtualization,
                        onConnectAdb = onConnectAdb,
                        onDisablePhantomKiller = onDisablePhantomKiller,
                        onShowAdbExport = { showAdbExportDialog = true }
                    )
                    1 -> CanvasTab(
                        virtualizationActive = virtualizationActive,
                        displayState = displayState,
                        displayViewModel = displayViewModel
                    )
                }
            }

            // Premium Custom Navigation Bar with U-cutout and Virtualization Toggle FAB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(96.dp)
            ) {
                // Background shape with U-cutout
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .align(Alignment.BottomCenter)
                        .shadow(12.dp, UCutoutShape())
                        .background(SurfaceDark, UCutoutShape())
                        .border(1.dp, SurfaceLight.copy(alpha = 0.5f), UCutoutShape())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Nav Tab: Dashboard
                        IconButton(
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dashboard,
                                contentDescription = "Dashboard",
                                tint = if (selectedTab == 0) PrimaryNeon else TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(Modifier.width(64.dp)) // Leave space for FAB inside the U-cutout

                        // Right Nav Tab: Virtual Workspace Canvas
                        IconButton(
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Canvas X11",
                                tint = if (selectedTab == 1) SecondaryNeon else TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // Core Virtualization Action FAB resting inside the U-cutout
                FloatingActionButton(
                    onClick = onToggleVirtualization,
                    shape = RoundedCornerShape(28.dp),
                    containerColor = if (virtualizationActive) PrimaryNeon else SurfaceLight,
                    contentColor = BackgroundDark,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(56.dp)
                ) {
                    Icon(
                        imageVector = if (virtualizationActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle VM Container",
                        tint = if (virtualizationActive) BackgroundDark else PrimaryNeon,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Contingency Dialog for Manual ADB Shell Script Exports
            if (showAdbExportDialog) {
                AdbScriptExportDialog(
                    onDismiss = { showAdbExportDialog = false }
                )
            }
        }
    }
}

@Composable
fun DashboardTab(
    downloadStatus: DownloadStatus,
    virtualizationActive: Boolean,
    adbConnected: Boolean,
    phantomProcessDisabled: Boolean,
    displayState: DisplayState,
    displayViewModel: X11DisplayViewModel,
    contingencyManager: ContingencyManager,
    onStartDownload: (String) -> Unit,
    onPauseDownload: () -> Unit,
    onToggleVirtualization: () -> Unit,
    onConnectAdb: () -> Unit,
    onDisablePhantomKiller: () -> Unit,
    onShowAdbExport: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Row (ADB & Phantom Killer Statuses)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Local ADB Connection",
                statusActive = adbConnected,
                activeLabel = "Connected",
                inactiveLabel = "Disconnected",
                icon = Icons.Default.Usb,
                onClick = onConnectAdb,
                modifier = Modifier.weight(1f)
            )

            StatusCard(
                title = "Phantom Process Killer",
                statusActive = phantomProcessDisabled,
                activeLabel = "Disabled",
                inactiveLabel = "Active (Threat)",
                icon = Icons.Default.Security,
                onClick = {
                    val strategy = contingencyManager.getPpkStrategy()
                    if (strategy == ContingencyManager.STRATEGY_PPK_SCRIPT) {
                        onShowAdbExport()
                    } else {
                        onDisablePhantomKiller()
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Contingency Actions Trigger panel
        ContingencyShortcutsCard(
            contingencyManager = contingencyManager,
            onShowAdbExport = onShowAdbExport
        )

        // Dynamic Display Canvas & Pointer Settings Panel
        DisplayCanvasSettingsCard(
            state = displayState,
            viewModel = displayViewModel,
            contingencyManager = contingencyManager
        )

        // Google Drive Asset Download Card
        AssetDownloaderCard(
            status = downloadStatus,
            onStartDownload = onStartDownload,
            onPauseDownload = onPauseDownload
        )
        
        // Log monitor terminal preview
        ConsolePreviewCard()
    }
}

@Composable
fun CanvasTab(
    virtualizationActive: Boolean,
    displayState: DisplayState,
    displayViewModel: X11DisplayViewModel
) {
    if (!virtualizationActive) {
        // Offline placeholder — clean centered message
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Not Running",
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = "Virtual Environment is Offline",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Press ▶ to start the Ubuntu container",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    } else {
        // Active virtualization: Column splits canvas and keyboard area cleanly.
        // Portrait: Canvas fills remaining space, keyboard sits below as fixed-height block.
        // Landscape: Keyboard overlay is transparent/floating — canvas uses full space.
        val isPortrait = displayState.orientation == ScreenOrientation.PORTRAIT
        val keyboardVisible = displayState.keyboardVisible
        // Portrait keyboard height constant (matches CustomKeyboardOverlay portrait block height)
        val keyboardHeightDp = 310.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ─── Canvas Area ─── fills all remaining height above keyboard
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes all space NOT occupied by the keyboard block
            ) {
                val viewWidthPx = constraints.maxWidth.toFloat()
                val viewHeightPx = constraints.maxHeight.toFloat()

                val transformMatrix = displayViewModel.calculateTransformationMatrix(
                    viewWidth = viewWidthPx,
                    viewHeight = viewHeightPx,
                    x11Width = displayState.resolutionWidth.toFloat(),
                    x11Height = displayState.resolutionHeight.toFloat()
                )

                X11CanvasView(
                    transformationMatrix = transformMatrix,
                    useBilinear = displayState.filteringMode == FilteringMode.BILINEAR,
                    inputMode = displayState.inputMode,
                    pointerSpeed = displayState.pointerSpeed,
                    modifier = Modifier.fillMaxSize()
                )

                // Landscape: floating transparent macro overlay sits on top of canvas (Z-axis)
                // It does NOT push canvas down — it is intentionally semi-transparent
                if (!isPortrait) {
                    CustomKeyboardOverlay(
                        displayState = displayState,
                        viewModel = displayViewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ─── Portrait Keyboard Block ─── solid, pushes canvas up via Column weight
            if (isPortrait) {
                AnimatedVisibility(
                    visible = keyboardVisible,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(keyboardHeightDp)
                    ) {
                        CustomKeyboardOverlay(
                            displayState = displayState,
                            viewModel = displayViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Always reserve a minimal keyboard toggle strip at the bottom in portrait
                // even when keyboard is hidden, so user can pull it back up
                if (!keyboardVisible) {
                    KeyboardToggleStrip(
                        onShow = { displayViewModel.updateKeyboardVisibility(true) }
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardToggleStrip(onShow: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, SurfaceDark)
                )
            )
            .clickable { onShow() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Show Keyboard",
                tint = PrimaryNeon,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "KEYBOARD",
                color = PrimaryNeon,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    statusActive: Boolean,
    activeLabel: String,
    inactiveLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val indicatorColor by animateColorAsState(
        targetValue = if (statusActive) TertiaryNeon else ErrorNeon,
        label = "indicatorColor"
    )

    Card(
        modifier = modifier
            .height(110.dp)
            .clickable { onClick() }
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = if (statusActive) listOf(PrimaryNeon, SecondaryNeon) else listOf(SurfaceLight, SurfaceLight)
                ),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(indicatorColor, RoundedCornerShape(4.dp))
                )
            }
            Column {
                Text(
                    text = title,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (statusActive) activeLabel else inactiveLabel,
                    color = if (statusActive) TextPrimary else ErrorNeon,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun VirtualizationControlCard(
    isActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceLight, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Ubuntu Linux Container",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isActive) "PRoot running guest processes" else "PRoot Container is stopped",
                    color = if (isActive) TertiaryNeon else TextSecondary,
                    fontSize = 13.sp
                )
            }
            
            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BackgroundDark,
                    checkedTrackColor = PrimaryNeon,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = SurfaceLight
                )
            )
        }
    }
}

@Composable
fun ContingencyShortcutsCard(
    contingencyManager: ContingencyManager,
    onShowAdbExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceLight, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Fallback & Contingency Overrides",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        contingencyManager.setPpkStrategy(ContingencyManager.STRATEGY_PPK_SCRIPT)
                        onShowAdbExport()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy script", tint = PrimaryNeon, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ADB Script", color = TextPrimary, fontSize = 11.sp)
                }
                
                Button(
                    onClick = {
                        contingencyManager.setPpkStrategy(ContingencyManager.STRATEGY_PPK_WAKELOCK)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Wakelock", tint = PrimaryNeon, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Wakelock VM", color = TextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun DisplayCanvasSettingsCard(
    state: DisplayState,
    viewModel: X11DisplayViewModel,
    contingencyManager: ContingencyManager,
    modifier: Modifier = Modifier
) {
    var displayStrat by remember { mutableStateOf(contingencyManager.getDisplayStrategy()) }
    var keyboardStrat by remember { mutableStateOf(contingencyManager.getKeyboardStrategy()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceLight, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Display & Pointer Settings",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            // Resolution selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Root Geometry", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateResolution(1920, 1080) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.resolutionWidth == 1920) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("1920x1080", color = if (state.resolutionWidth == 1920) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { viewModel.updateResolution(1280, 720) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.resolutionWidth == 1280) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("1280x720", color = if (state.resolutionWidth == 1280) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // Aspect Scaling mode (Fit vs Stretch)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aspect Scale", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateScaleMode(ScaleMode.FIT) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.scaleMode == ScaleMode.FIT) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Fit Screen", color = if (state.scaleMode == ScaleMode.FIT) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { viewModel.updateScaleMode(ScaleMode.STRETCH) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.scaleMode == ScaleMode.STRETCH) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Stretch", color = if (state.scaleMode == ScaleMode.STRETCH) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // GL Texture filtering selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GL Filter", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateFilteringMode(FilteringMode.BILINEAR) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.filteringMode == FilteringMode.BILINEAR) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Bilinear", color = if (state.filteringMode == FilteringMode.BILINEAR) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { viewModel.updateFilteringMode(FilteringMode.NEAREST) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.filteringMode == FilteringMode.NEAREST) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Nearest", color = if (state.filteringMode == FilteringMode.NEAREST) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // Display Rendering Engine (Termux-X11 NDK vs local VNC fallback)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Display Server", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            contingencyManager.setDisplayStrategy(ContingencyManager.STRATEGY_DISPLAY_NDK)
                            displayStrat = ContingencyManager.STRATEGY_DISPLAY_NDK
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (displayStrat == ContingencyManager.STRATEGY_DISPLAY_NDK) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("X11 NDK", color = if (displayStrat == ContingencyManager.STRATEGY_DISPLAY_NDK) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            contingencyManager.setDisplayStrategy(ContingencyManager.STRATEGY_DISPLAY_VNC)
                            displayStrat = ContingencyManager.STRATEGY_DISPLAY_VNC
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (displayStrat == ContingencyManager.STRATEGY_DISPLAY_VNC) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("VNC (Port 5901)", color = if (displayStrat == ContingencyManager.STRATEGY_DISPLAY_VNC) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // IME Suppression Strategy (Main FocusBlock vs Window ALT_FOCUSABLE_IM flag)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("IME Block Strategy", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            contingencyManager.setKeyboardStrategy(ContingencyManager.STRATEGY_KEYBOARD_BLOCK)
                            keyboardStrat = ContingencyManager.STRATEGY_KEYBOARD_BLOCK
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (keyboardStrat == ContingencyManager.STRATEGY_KEYBOARD_BLOCK) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Focus Blocker", color = if (keyboardStrat == ContingencyManager.STRATEGY_KEYBOARD_BLOCK) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            contingencyManager.setKeyboardStrategy(ContingencyManager.STRATEGY_KEYBOARD_IM_FLAG)
                            keyboardStrat = ContingencyManager.STRATEGY_KEYBOARD_IM_FLAG
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (keyboardStrat == ContingencyManager.STRATEGY_KEYBOARD_IM_FLAG) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("ALT IM Flag", color = if (keyboardStrat == ContingencyManager.STRATEGY_KEYBOARD_IM_FLAG) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // Pointer Input Mode Toggle (Absolute Touch vs Trackpad Mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Input Mode", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateInputMode(InputMode.TOUCH) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.inputMode == InputMode.TOUCH) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Touch Mode", color = if (state.inputMode == InputMode.TOUCH) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { viewModel.updateInputMode(InputMode.TRACKPAD) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.inputMode == InputMode.TRACKPAD) PrimaryNeon else SurfaceLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Trackpad Mode", color = if (state.inputMode == InputMode.TRACKPAD) BackgroundDark else TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // Pointer Speed Slider (Trackpad mode only)
            AnimatedVisibility(visible = state.inputMode == InputMode.TRACKPAD) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Pointer Speed", color = TextSecondary, fontSize = 13.sp)
                        Text(
                            text = String.format("%.1fx", state.pointerSpeed),
                            color = PrimaryNeon,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = state.pointerSpeed,
                        onValueChange = { viewModel.updatePointerSpeed(it) },
                        valueRange = 0.5f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryNeon,
                            activeTrackColor = PrimaryNeon,
                            inactiveTrackColor = SurfaceLight
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun AssetDownloaderCard(
    status: DownloadStatus,
    onStartDownload: (String) -> Unit,
    onPauseDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileIdPlaceholder = "1-driveId_ubuntu_rootfs_img"
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceLight, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ubuntu rootfs Asset Downloader",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            when (status) {
                is DownloadStatus.Idle -> {
                    Text(
                        text = "Ready to download Ubuntu rootfs archive (2.1 GB).",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { onStartDownload(fileIdPlaceholder) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                        Spacer(Modifier.width(8.dp))
                        Text("Download from Google Drive", color = BackgroundDark)
                    }
                }
                is DownloadStatus.Downloading -> {
                    val progressFloat by animateFloatAsState(targetValue = status.progress, label = "progress")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Downloading...",
                            color = PrimaryNeon,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            color = PrimaryNeon,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = progressFloat,
                        color = PrimaryNeon,
                        trackColor = SurfaceLight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Text(
                        text = "${status.downloadedBytes / (1024 * 1024)} MB / ${status.totalBytes / (1024 * 1024)} MB",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onPauseDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryNeon),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                            Spacer(Modifier.width(4.dp))
                            Text("Pause", color = TextPrimary)
                        }
                    }
                }
                is DownloadStatus.Paused -> {
                    val progressFloat by animateFloatAsState(targetValue = status.progress, label = "progress")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Download Paused",
                            color = WarningNeon,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            color = WarningNeon,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = progressFloat,
                        color = WarningNeon,
                        trackColor = SurfaceLight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Text(
                        text = "${status.downloadedBytes / (1024 * 1024)} MB / ${status.totalBytes / (1024 * 1024)} MB",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(
                        onClick = { onStartDownload(fileIdPlaceholder) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        Spacer(Modifier.width(4.dp))
                        Text("Resume Download", color = BackgroundDark)
                    }
                }
                is DownloadStatus.Success -> {
                    Text(
                        text = "Ubuntu rootfs extraction ready.",
                        color = TertiaryNeon,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(SurfaceLight, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = TertiaryNeon)
                            Spacer(Modifier.width(8.dp))
                            Text("Archive Download Completed Successfully", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
                is DownloadStatus.Error -> {
                    Text(
                        text = "Download Failed: ${status.message}",
                        color = ErrorNeon,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { onStartDownload(fileIdPlaceholder) },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorNeon),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        Spacer(Modifier.width(4.dp))
                        Text("Retry Download", color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun ConsolePreviewCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .border(1.dp, SurfaceLight, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF07080D)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "SYSTEM LOG MONITOR",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "[SYSTEM] Monos virtualization runtime loaded.",
                    color = TertiaryNeon,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "[PROOT] Initializing syscall tracing structures...",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "[L-ADB] ADB service listening for Wireless Debug port...",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
