package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WatermarkCameraScreen(
    modifier: Modifier = Modifier,
    viewModel: WatermarkCameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Gather Live States from ViewModel
    val config by viewModel.config.collectAsStateWithLifecycle()
    val currentTime by viewModel.currentTime.collectAsStateWithLifecycle()
    val coordinates by viewModel.coordinates.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val lensFacing by viewModel.lensFacing.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()

    // Accompanist Permission State Tracker
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Check Camera specifically (core requirement)
    val cameraGranted = permissionsState.permissions.find { it.permission == Manifest.permission.CAMERA }?.status?.let {
        it is com.google.accompanist.permissions.PermissionStatus.Granted
    } ?: false

    val locationGranted = permissionsState.permissions.filter {
        it.permission == Manifest.permission.ACCESS_FINE_LOCATION || it.permission == Manifest.permission.ACCESS_COARSE_LOCATION
    }.any { it.status is com.google.accompanist.permissions.PermissionStatus.Granted }

    // Auto-request permissions on startup
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted && !permissionsState.shouldShowRationale) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    // Start location updates directly based on state changes
    LaunchedEffect(locationGranted) {
        if (!locationGranted) {
            viewModel.stopLocationUpdates()
        }
    }

    val lifecycleOwnerForObserver = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwnerForObserver, locationGranted) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (locationGranted) {
                    viewModel.startLocationUpdates(context)
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                viewModel.stopLocationUpdates()
            }
        }
        lifecycleOwnerForObserver.lifecycle.addObserver(observer)
        
        if (locationGranted && lifecycleOwnerForObserver.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            viewModel.startLocationUpdates(context)
        }
        
        onDispose {
            lifecycleOwnerForObserver.lifecycle.removeObserver(observer)
            viewModel.stopLocationUpdates()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0B0E14) // Cyber Obsidian Black
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            color = Color(0xFF0B0E14)
        ) {
            if (!cameraGranted) {
                // Beautiful Permission Prompt Layout
                PermissionPromptView(
                    onRequestPermissions = { permissionsState.launchMultiplePermissionRequest() },
                    isPermissionDeniedPermanently = permissionsState.shouldShowRationale
                )
            } else {
                // Main Interactive Shutter Camera View
                CameraViewfinderAndControls(
                    viewModel = viewModel,
                    config = config,
                    currentTime = currentTime,
                    coordinates = coordinates,
                    address = address,
                    lensFacing = lensFacing,
                    flashMode = flashMode,
                    saveStatus = saveStatus,
                    locationGranted = locationGranted,
                    onRequestLocation = { permissionsState.launchMultiplePermissionRequest() }
                )
            }
        }
    }
}

/**
 * Aesthetic camera viewfinder, overlays and setting panels.
 */
@Composable
fun CameraViewfinderAndControls(
    viewModel: WatermarkCameraViewModel,
    config: WatermarkConfig,
    currentTime: String,
    coordinates: String,
    address: String,
    lensFacing: Int,
    flashMode: Int,
    saveStatus: SaveStatus,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Create a mutable state to hold the previewView once it's created by AndroidView
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureTriggered by remember { mutableStateOf(false) }

    // Core Camera UseCase setup binding on lens changes
    DisposableEffect(lensFacing, previewView) {
        val currentPreviewView = previewView 
        
        if (currentPreviewView != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return@DisposableEffect onDispose {}
            }
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also {
                        it.surfaceProvider = currentPreviewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setFlashMode(flashMode)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    
                    imageCapture = capture

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        capture
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
        
        onDispose {
            // Nothing to unbind manually here. ProcessCameraProvider unbinds automatically
            // when lifecycleOwner is destroyed. During camera toggles, the new effect block
            // calls unbindAll() before binding new use cases.
        }
    }

    // Adapt flash modes dynamically on user toggles
    LaunchedEffect(flashMode) {
        imageCapture?.flashMode = flashMode
    }

    // Layout configuration triggers
    var showAddressEditor by remember { mutableStateOf(false) }
    var showCustomTextEditor by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val controlsContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .padding(bottom = 16.dp, top = 8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Horizontal selector of styles
            PresetStyleSelector(
                selectedTheme = config.theme,
                onSelectTheme = { viewModel.updateTheme(it) }
            )

            // Detailed toggle parameters
            ToggleParameterChips(
                config = config,
                onToggleDate = { viewModel.updateShowDate(!config.showDate) },
                onToggleLocation = { viewModel.updateShowLocation(!config.showLocation) },
                onToggleCoords = { viewModel.updateShowCoordinates(!config.showCoordinates) },
                onToggleCustom = { viewModel.updateShowCustomText(!config.showCustomText) },
                onTrigCustomTextEdit = { showCustomTextEditor = true },
                onTrigAddressEdit = { showAddressEditor = true }
            )

            // High-density Camera Mode Selector Bar right above the shutter layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "视频",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "照片",
                        color = Color(0xFFD0E4FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .background(Color(0xFFD0E4FF))
                    )
                }
                Text(
                    text = "全景",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Huge tactical Shutter Row
            ControlShutterRow(
                onShutterClick = {
                    val capturer = imageCapture
                    if (capturer != null && !captureTriggered) {
                        captureTriggered = true
                        capturer.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                    captureTriggered = false
                                    viewModel.processAndSaveCapturedImage(context, image)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    captureTriggered = false
                                    exception.printStackTrace()
                                }
                            }
                        )
                    }
                },
                onToggleLens = { viewModel.toggleLensFacing() }
            )
        }
    }

    val topAppBarContent: @Composable () -> Unit = {
        TopAppBarStrip(
            flashMode = flashMode,
            lensFacing = lensFacing,
            onToggleFlash = { viewModel.cycleFlashMode() },
            onToggleLens = { viewModel.toggleLensFacing() },
            locationGranted = locationGranted,
            onRequestLocation = onRequestLocation
        )
    }

    val viewfinderContent: @Composable (Modifier) -> Unit = { modifier ->
        ViewfinderWithOverlays(
            onPreviewViewCreated = { previewView = it },
            captureTriggered = captureTriggered,
            config = config,
            currentTime = currentTime,
            coordinates = coordinates,
            address = address,
            viewModel = viewModel,
            modifier = modifier
        )
    }

    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                viewfinderContent(Modifier.fillMaxSize())
                topAppBarContent()
            }
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                controlsContent()
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            topAppBarContent()
            viewfinderContent(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                controlsContent()
            }
        }
    }

    // Modal Style Customizing Dialogs
    if (showCustomTextEditor) {
        CustomTextEditDialog(
            initialText = config.customText,
            initialBadgeText = config.badgeText,
            onDismiss = { showCustomTextEditor = false },
            onConfirm = { txt, badge ->
                viewModel.updateCustomText(txt)
                viewModel.updateBadgeText(badge)
                showCustomTextEditor = false
            }
        )
    }

    if (showAddressEditor) {
        AddressEditDialog(
            initialAddress = address,
            onDismiss = { showAddressEditor = false },
            onConfirm = { edited ->
                viewModel.overrideAddress(edited)
                showAddressEditor = false
            }
        )
    }

    // Picture Saving Result Banner / Dialogue Overlay
    SaveResultOverlay(
        status = saveStatus,
        onClose = { viewModel.resetSaveStatus() }
    )
}

@Composable
fun ViewfinderWithOverlays(
    onPreviewViewCreated: (PreviewView) -> Unit,
    captureTriggered: Boolean,
    config: WatermarkConfig,
    currentTime: String,
    coordinates: String,
    address: String,
    viewModel: WatermarkCameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(32.dp))
            .border(2.dp, Color(0xFF202634), RoundedCornerShape(32.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreviewViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 3x3 Grid Overlay Lines
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val gridColor = Color.White.copy(alpha = 0.15f)
                    
                    // Draw vertical guide lines
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth)
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth)
                    
                    // Draw horizontal guide lines
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth)
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth)
                }
        )

        // Flash Screen Trigger Indicator
        AnimatedVisibility(
            visible = captureTriggered,
            enter = fadeIn(animationSpec = tween(50)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
        }

        // Real-time Preview Overlay inside Viewfinder
        WatermarkLiveOverlay(
            config = config,
            dateTime = currentTime,
            coordinates = coordinates,
            address = address
        )

        // Mini Aligning Assist Guides
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            CornerMarkerSquare(
                position = config.position,
                onSelectPosition = { viewModel.updatePosition(it) }
            )
        }
    }
}

/**
 * Beautiful app header showing status and camera selectors.
 */
@Composable
fun TopAppBarStrip(
    flashMode: Int,
    lensFacing: Int,
    onToggleFlash: () -> Unit,
    onToggleLens: () -> Unit,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App title in cyber typography
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF9E00)) // Glowing orange badge
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "KRONOS CAMERA",
                color = Color.White,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // GPS state button
            IconButton(
                onClick = { if (!locationGranted) onRequestLocation() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (locationGranted) Color(0xFF162520) else Color(0xFF2C161D)
                    )
            ) {
                Icon(
                    imageVector = if (locationGranted) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                    contentDescription = "GPS Status",
                    tint = if (locationGranted) Color(0xFF00FF88) else Color(0xFFFF5252),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Flash Mode button
            IconButton(
                onClick = onToggleFlash,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B2230))
            ) {
                val icon = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                    else -> Icons.Default.FlashOff
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Flash Mode",
                    tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFF9E00) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Flip Camera button
            IconButton(
                onClick = onToggleLens,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B2230))
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Overlaid Corner Selector Widget to select TopLeft, BottomRight layout on tap.
 */
@Composable
fun CornerMarkerSquare(
    position: WatermarkPosition,
    onSelectPosition: (WatermarkPosition) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val size = 32.dp
        val activeCol = Color(0xFFFF9E00)
        val inactiveCol = Color(0x33FFFFFF)

        // Top-Left Alignment Dot
        CornerIndicatorDot(
            position = WatermarkPosition.TOP_LEFT,
            isActive = position == WatermarkPosition.TOP_LEFT,
            onSelect = onSelectPosition,
            modifier = Modifier.align(Alignment.TopStart)
        )
        // Top-Right Alignment Dot
        CornerIndicatorDot(
            position = WatermarkPosition.TOP_RIGHT,
            isActive = position == WatermarkPosition.TOP_RIGHT,
            onSelect = onSelectPosition,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        // Bottom-Left Alignment Dot
        CornerIndicatorDot(
            position = WatermarkPosition.BOTTOM_LEFT,
            isActive = position == WatermarkPosition.BOTTOM_LEFT,
            onSelect = onSelectPosition,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        // Bottom-Right Alignment Dot
        CornerIndicatorDot(
            position = WatermarkPosition.BOTTOM_RIGHT,
            isActive = position == WatermarkPosition.BOTTOM_RIGHT,
            onSelect = onSelectPosition,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
fun CornerIndicatorDot(
    position: WatermarkPosition,
    isActive: Boolean,
    onSelect: (WatermarkPosition) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clickable { onSelect(position) }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isActive) 12.dp else 6.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFFFF9E00) else Color(0x77FFFFFF))
                .border(
                    width = if (isActive) 3.dp else 0.dp,
                    color = if (isActive) Color(0x55FF9E00) else Color.Transparent,
                    shape = CircleShape
                )
        )
    }
}

/**
 * Renders the chosen design styles directly inside the Compose camera box.
 */
@Composable
fun BoxScope.WatermarkLiveOverlay(
    config: WatermarkConfig,
    dateTime: String,
    coordinates: String,
    address: String
) {
    val alignment = when (config.position) {
        WatermarkPosition.TOP_LEFT -> Alignment.TopStart
        WatermarkPosition.TOP_RIGHT -> Alignment.TopEnd
        WatermarkPosition.BOTTOM_LEFT -> Alignment.BottomStart
        WatermarkPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    Box(
        modifier = Modifier
            .align(alignment)
            .padding(16.dp)
            .widthIn(max = 280.dp)
    ) {
        when (config.theme) {
            WatermarkTheme.HIGH_DENSITY -> {
                // STYLE 0: High Density "Ice Blue/Slate" translucent layout
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp))
                        .background(Color(0xBA090D14))
                        .border(
                            width = 1.dp,
                            color = Color(0x22FFFFFF),
                            shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
                        )
                ) {
                    // Left vertical accent bar
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                drawRect(Color(0xFFD0E4FF))
                            }
                            .align(Alignment.CenterVertically)
                    )
                    
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (config.showDate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Date",
                                    tint = Color(0xFFD0E4FF),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = dateTime,
                                    color = Color(0xFFFFF1F5),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        
                        if (config.showLocation && address.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = "Location",
                                    tint = Color(0xFFD0E4FF),
                                    modifier = Modifier.size(13.dp).padding(top = 2.dp)
                                )
                                Column {
                                    Text(
                                        text = address,
                                        color = Color(0xFFFFF1F5),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (config.showCoordinates && coordinates.isNotEmpty()) {
                                        Text(
                                            text = coordinates,
                                            color = Color(0xBBFFF1F5),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Light,
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = (-0.2).sp
                                        )
                                    }
                                }
                            }
                        } else if (config.showCoordinates && coordinates.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Explore,
                                    contentDescription = "Coordinates",
                                    tint = Color(0xFFD0E4FF),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = coordinates,
                                    color = Color(0xFFFFF1F5),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (config.showCustomText && config.customText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .height(1.dp)
                                    .background(Color(0x22FFFFFF))
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EditNote,
                                    contentDescription = "Memo",
                                    tint = Color(0xFFD0E4FF),
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = config.customText,
                                    color = Color(0xFFFFF1F5),
                                    fontSize = 11.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            WatermarkTheme.CLASSIC_SLATE -> {
                // STYLE 1: Classic Semi-translucent dark slate plate
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xDD1A1A1A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    if (config.showCustomText && config.customText.isNotEmpty()) {
                        Text(
                            text = config.customText,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    if (config.showDate) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.AccessTime, null, tint = Color(0xFFA0A5B5), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(dateTime, color = Color(0xFFE2E4E9), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (config.showLocation && address.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Place, null, tint = Color(0xFFA0A5B5), modifier = Modifier.size(12.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(address, color = Color(0xFFE2E4E9), fontSize = 11.sp)
                        }
                    }
                    if (config.showCoordinates && coordinates.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Explore, null, tint = Color(0xFFA0A5B5), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(coordinates, color = Color(0xFFA0A5B5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            WatermarkTheme.CONSTRUCTION -> {
                // STYLE 2: Construction High-contrast safety layout
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFB0C0C0C))
                        .padding(end = 12.dp)
                ) {
                    // Thick safety orange sidebar
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                drawRect(Color(0xFFFF9E00))
                            }
                            .align(Alignment.CenterVertically)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        if (config.showCustomText && config.customText.isNotEmpty()) {
                            Text(
                                text = config.customText,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        if (config.showDate) {
                            Text(
                                text = "【时间】 $dateTime",
                                color = Color(0xFFFF9E00),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (config.showLocation && address.isNotEmpty()) {
                            Text(
                                text = "【位置】 $address",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (config.showCoordinates && coordinates.isNotEmpty()) {
                            Text(
                                text = "【物理】 $coordinates",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            WatermarkTheme.CYBER_TECH -> {
                // STYLE 3: Cyberpunk high visibility brackets
                Box(
                    modifier = Modifier
                        .background(Color(0x88000803))
                        .drawBehind {
                            // Draw green technical grid brackets
                            val l = 16.dp.toPx()
                            val stroke = 2.dp.toPx()
                            val c = Color(0xFF00FF88)
                            // Top left
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(l, 0f), strokeWidth = stroke)
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(0f, l), strokeWidth = stroke)
                            // Top right
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width - l, 0f), strokeWidth = stroke)
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width, l), strokeWidth = stroke)
                            // Bottom left
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(l, size.height), strokeWidth = stroke)
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(0f, size.height - l), strokeWidth = stroke)
                            // Bottom right
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width - l, size.height), strokeWidth = stroke)
                            drawLine(color = c, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height - l), strokeWidth = stroke)
                        }
                        .padding(14.dp)
                ) {
                    Column {
                        if (config.showCustomText && config.customText.isNotEmpty()) {
                            Text(
                                text = "[ ${config.customText} ]",
                                color = Color(0xFF00FF88),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (config.showDate) {
                            Text(
                                text = "SYS.TIME: $dateTime",
                                color = Color(0xDD00FF88),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 1.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (config.showLocation && address.isNotEmpty()) {
                            Text(
                                text = "SYS.ADDR: $address",
                                color = Color(0xDD00FF88),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (config.showCoordinates && coordinates.isNotEmpty()) {
                            Text(
                                text = "GPS.COOR: $coordinates",
                                color = Color(0xAA00FF88),
                                fontSize = 9.sp,
                                modifier = Modifier.padding(vertical = 1.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            WatermarkTheme.MINIMAL_BADGE -> {
                // STYLE 4: Stamp official seal look alike
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xD310151E))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (config.showCustomText && config.customText.isNotEmpty()) {
                                Text(
                                    text = config.customText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (config.showDate) {
                                Text(
                                    text = "时间: $dateTime",
                                    color = Color(0xFFE2E4E9),
                                    fontSize = 11.sp
                                )
                            }
                            if (config.showLocation && address.isNotEmpty()) {
                                Text(
                                    text = "地点: $address",
                                    color = Color(0xFFE2E4E9),
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            }
                            if (config.showCoordinates && coordinates.isNotEmpty()) {
                                Text(
                                    text = "物理: $coordinates",
                                    color = Color(0xFFA0A5B5),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Crimson Circular certified stamp
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .graphicsLayer {
                                    rotationZ = -8f
                                }
                                .border(2.dp, Color(0xDDA32323), CircleShape)
                                .padding(4.dp)
                                .border(1.dp, Color(0x88A32323), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = config.badgeText,
                                color = Color(0xDDA32323),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Premium horizontal slider for presets.
 */
@Composable
fun PresetStyleSelector(
    selectedTheme: WatermarkTheme,
    onSelectTheme: (WatermarkTheme) -> Unit
) {
    Column {
        Text(
            text = "水印印章样式",
            color = Color(0xFFA0A5B5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(WatermarkTheme.values()) { theme ->
                val isSelected = theme == selectedTheme
                val (label, icon, color) = when (theme) {
                    WatermarkTheme.HIGH_DENSITY -> Triple("高密蓝", Icons.Outlined.Layers, Color(0xFFD0E4FF))
                    WatermarkTheme.CLASSIC_SLATE -> Triple("经典白", Icons.Outlined.PhotoCamera, Color.White)
                    WatermarkTheme.CONSTRUCTION -> Triple("工程橘", Icons.Outlined.Construction, Color(0xFFFF9E00))
                    WatermarkTheme.CYBER_TECH -> Triple("科技绿", Icons.Outlined.GridOn, Color(0xFF00FF88))
                    WatermarkTheme.MINIMAL_BADGE -> Triple("认证签", Icons.Outlined.OfflinePin, Color(0xFFFF5252))
                }

                Surface(
                    onClick = { onSelectTheme(theme) },
                    modifier = Modifier
                        .height(46.dp)
                        .testTag("style_preset_${theme.name.lowercase()}"),
                    shape = RoundedCornerShape(23.dp),
                    color = if (isSelected) Color(0xFF1F293D) else Color(0xFF131722),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) color else Color(0xFF202634)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) color else Color(0xFF70778B),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else Color(0xFF70778B),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Multi toggle filter chips + dynamic edit dialog shortcuts
 */
@Composable
fun ToggleParameterChips(
    config: WatermarkConfig,
    onToggleDate: () -> Unit,
    onToggleLocation: () -> Unit,
    onToggleCoords: () -> Unit,
    onToggleCustom: () -> Unit,
    onTrigCustomTextEdit: () -> Unit,
    onTrigAddressEdit: () -> Unit
) {
    Column {
        Text(
            text = "水印成分内容",
            color = Color(0xFFA0A5B5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        // Rows of capsule togglers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Edit Custom Text Button
            InputChip(
                selected = config.showCustomText,
                onClick = onToggleCustom,
                label = { Text("自定义: ${config.customText}") },
                leadingIcon = {
                    IconButton(onClick = onTrigCustomTextEdit, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Edit, "Edit", tint = Color(0xFFFF9E00), modifier = Modifier.size(14.dp))
                    }
                },
                modifier = Modifier.testTag("toggle_custom_text")
            )

            // Edit Address Button
            InputChip(
                selected = config.showLocation,
                onClick = onToggleLocation,
                label = { Text("地理位置") },
                leadingIcon = {
                    IconButton(onClick = onTrigAddressEdit, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Edit, "Edit GPS", tint = Color(0xFFFF9E00), modifier = Modifier.size(14.dp))
                    }
                },
                modifier = Modifier.testTag("toggle_address")
            )

            FilterChip(
                selected = config.showDate,
                onClick = onToggleDate,
                label = { Text("日期时间") },
                modifier = Modifier.testTag("toggle_date")
            )

            FilterChip(
                selected = config.showCoordinates,
                onClick = onToggleCoords,
                label = { Text("经纬坐标") },
                modifier = Modifier.testTag("toggle_coords")
            )
        }
    }
}

/**
 * Beautiful camera shutter button layout conforming to the High Density layout specifications.
 */
@Composable
fun ControlShutterRow(
    onShutterClick: () -> Unit,
    onToggleLens: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Gallery Thumbnail / Indicator placeholder matching the design HTML
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E293B))
                .border(2.dp, Color.White.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "Gallery Preview",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        // Middle: Shutter Button (white pro ring + white inner dot)
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .clickable { onShutterClick() }
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        // Right: Flip Camera matching design
        IconButton(
            onClick = onToggleLens,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
        ) {
            Icon(
                imageVector = Icons.Default.FlipCameraAndroid,
                contentDescription = "Switch Camera",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Dialog to change custom texts and certificates.
 */
@Composable
fun CustomTextEditDialog(
    initialText: String,
    initialBadgeText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var badge by remember { mutableStateOf(initialBadgeText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "编辑水印文本",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        containerColor = Color(0xFF181D29),
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("水印描述 (如: 水泥浇筑/施工记录)") },
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = Color(0xFFFF9E00),
                        focusedIndicatorColor = Color(0xFFFF9E00),
                        unfocusedContainerColor = Color(0xFF0F121C),
                        focusedContainerColor = Color(0xFF0F121C)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_custom_input")
                )

                OutlinedTextField(
                    value = badge,
                    onValueChange = { badge = it },
                    label = { Text("印章字符 (仅对 认证签 样式生效)") },
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = Color(0xFFFF9E00),
                        focusedIndicatorColor = Color(0xFFFF9E00),
                        unfocusedContainerColor = Color(0xFF0F121C),
                        focusedContainerColor = Color(0xFF0F121C)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_badge_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text, badge) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9E00)),
                modifier = Modifier.testTag("dialog_custom_confirm")
            ) {
                Text("确定", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFFA0A5B5))
            }
        }
    )
}

/**
 * Dialog to manually customize address values in case of GPS drift or rate limit geocoders.
 */
@Composable
fun AddressEditDialog(
    initialAddress: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialAddress) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "手动校准地理位置",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        containerColor = Color(0xFF181D29),
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "可通过此处手动输入您的作业地点，校正由于遮挡等造成的定位偏移：",
                    color = Color(0xFFA0A5B5),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("现场详细地址（支持手动输入）") },
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = Color(0xFFFF9E00),
                        focusedIndicatorColor = Color(0xFFFF9E00),
                        unfocusedContainerColor = Color(0xFF0F121C),
                        focusedContainerColor = Color(0xFF0F121C)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_address_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9E00)),
                modifier = Modifier.testTag("dialog_address_confirm")
            ) {
                Text("确定", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFFA0A5B5))
            }
        }
    )
}

/**
 * Toast / Drawer popup to notify saving accomplishments without blocking work.
 */
@Composable
fun SaveResultOverlay(
    status: SaveStatus,
    onClose: () -> Unit
) {
    // Triggers when photo rendering completes or fails
    AnimatedVisibility(
        visible = status !is SaveStatus.Idle,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { if (status !is SaveStatus.Saving) onClose() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(Color(0xFF121622))
                    .border(1.dp, Color(0xFF202634), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (status) {
                    SaveStatus.Saving -> {
                        CircularProgressIndicator(
                            color = Color(0xFFFF9E00),
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            "正在合并图层并写入高解像相册...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "请勿关闭相机程序",
                            color = Color(0xFFA0A5B5),
                            fontSize = 11.sp
                        )
                    }

                    is SaveStatus.Success -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF00FF88),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            "合并渲染成功！",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "图片已安全写入系统公共相册中\n存储文件夹: ${status.path}",
                            color = Color(0xFFE2E4E9),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E283A)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("返回拍摄", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    is SaveStatus.Error -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            "合成失败",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            status.message,
                            color = Color(0xFFFF8A8A),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("确定", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * Direct request widget explaining the reasons to grant permissions.
 */
@Composable
fun PermissionPromptView(
    onRequestPermissions: () -> Unit,
    isPermissionDeniedPermanently: Boolean
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // High quality camera lens symbol
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFF131722))
                .border(2.dp, Color(0xFF202634), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color(0xFFFF9E00),
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "需要相机与位置功能",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "水印相机需要：\n1. 相机权限：支持现场拍照取景\n2. 定位权限：读取当前位置的地理名称及高精确度 GPS 经纬度，并渲染到水印中",
            color = Color(0xFFA0A5B5),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

        if (isPermissionDeniedPermanently) {
            // Explains how to recover in Android Settings panel
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9E00)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .testTag("open_settings_button")
            ) {
                Text("前往设置手动授予权限", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9E00)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .testTag("request_permissions_button")
            ) {
                Text("立即授予访问权限", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
