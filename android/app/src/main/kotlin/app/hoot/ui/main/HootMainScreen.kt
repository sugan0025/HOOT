package app.hoot.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import app.hoot.update.HootUpdater
import app.hoot.update.HootUpdateSettingsDialog
import app.hoot.update.UpdateInstallActivity
import app.hoot.R
import app.hoot.audio.HootSoundManager
import app.hoot.model.BlobTone
import app.hoot.model.ConversionUiState
import app.hoot.ui.blob.BlobSwallowTimeline
import app.hoot.ui.blob.BlobatarView
import app.hoot.ui.dialog.RenameDialog
import app.hoot.ui.theme.FileBadgeIcon
import app.hoot.ui.wheel.DonutWheel
import app.hoot.ui.wheel.hitTestSlice
import app.hoot.ui.wheel.hitTestSubSlice
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HootMainScreen(
    viewModel: HootViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentFile by viewModel.currentFile.collectAsState()
    val currentFileCount by viewModel.currentFileCount.collectAsState()
    val currentSlices by viewModel.currentSlices.collectAsState()
    val hoveredIndex by viewModel.hoveredSliceIndex.collectAsState()
    val expandedGroupIndex by viewModel.expandedGroupIndex.collectAsState()
    val hoveredSubIndex by viewModel.hoveredSubIndex.collectAsState()
    val flipTrigger by viewModel.flipTrigger.collectAsState()
    val faceIndex by viewModel.faceIndex.collectAsState()
    val blobTone by viewModel.blobTone.collectAsState()
    val pendingSlice by viewModel.pendingRenameSlice.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var showUpdateSettings by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf(HootUpdater.availableRelease(context)) }
    var pendingApproval by remember { mutableStateOf(HootUpdater.pendingApproval(context)) }
    var hidePendingPrompt by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    LaunchedEffect(Unit) {
        HootUpdater.initialize(context)
        while (true) {
            availableUpdate = HootUpdater.availableRelease(context)
            pendingApproval = HootUpdater.pendingApproval(context)
            delay(1500)
        }
    }

    BackHandler(enabled = expandedGroupIndex >= 0) {
        viewModel.collapseGroup()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.loadFromUri(context, uri)
        }
    }

    var touchPosition by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val ptr = event.changes.firstOrNull { it.pressed } ?: event.changes.firstOrNull()
                        if (ptr != null && ptr.pressed) {
                            touchPosition = ptr.position
                        } else if (event.changes.all { !it.pressed }) {
                            touchPosition = null
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. Android Hoot wallpaper supplied for the mobile experience.
        Image(
            painter = painterResource(id = R.drawable.app_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 2. A light navy veil keeps the artwork visible while preserving text contrast.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x20091420),
                            Color(0x3007111D),
                            Color(0x46050C17)
                        )
                    )
                )
        )

        val wheelCenter = Offset(
            constraints.maxWidth / 2f,
            constraints.maxHeight / 2f
        )
        val outerRadius = minOf(constraints.maxWidth, constraints.maxHeight) * 0.355f

        var dragOffset by remember(currentFile) { mutableStateOf(Offset.Zero) }
        var isDragging by remember(currentFile) { mutableStateOf(false) }
        val currentQuip = remember { HootQuips.getRandomQuip() }

        // Windows Hoot smooth cancel swallow animation
        var isSwallowing by remember(currentFile) { mutableStateOf(false) }
        val swallowAnim = remember(currentFile) { Animatable(0f) }
        var swallowStartOffset by remember(currentFile) { mutableStateOf(Offset.Zero) }
        var hasGulped by remember(currentFile) { mutableStateOf(false) }

        LaunchedEffect(isSwallowing) {
            if (isSwallowing) {
                swallowAnim.snapTo(0f)
                hasGulped = false
                swallowAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500, easing = LinearEasing)
                )
                viewModel.cancelConversion()
                isSwallowing = false
                dragOffset = Offset.Zero
            }
        }

        // ==========================================
        // EMPTY STATE: Full Animated Blob + Snowflakes + Quip
        // ==========================================
        if (currentFile == null) {
            // Genuine 6-arm dendrite crystal snowfall
            SnowfallEffect(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "HOOT",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.5.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Windows Hoot ThoughtCloud Card with exact gradient, border, typography & drop shadow
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(18.dp),
                            ambientColor = Color(0x55000000),
                            spotColor = Color(0x66000000)
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xE6202535),
                                    Color(0xD9131622)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                1.5.dp,
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xAA8E85F3),
                                        Color(0x60FFFFFF),
                                        Color(0x256E58A8)
                                    )
                                )
                            ),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "“$currentQuip”",
                        color = Color(0xFFF2EFFF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Windows Hoot thought bubble tail dot connecting directly to the blob
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0xCC8E85F3))
                        .border(BorderStroke(1.dp, Color(0x88FFFFFF)), RoundedCornerShape(99.dp))
                )

                // Living Blobatar mascot (190.dp) - snugged right under the thought dot, eliminating the extra gap
                BlobatarView(
                    modifier = Modifier.offset(y = (-30).dp),
                    size = 190.dp,
                    tone = BlobTone.Idle,
                    targetOffset = touchPosition,
                    onPress = {
                        HootSoundManager.playBlobPress()
                    },
                    onClick = {
                        HootSoundManager.playSquish()
                        filePickerLauncher.launch("*/*")
                    }
                )
            }
        }

        // ==========================================
        // ACTIVE STATE: File Loaded & Blob Wheel
        // ==========================================
        if (currentFile != null && currentSlices.isNotEmpty()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val apertureRadius = outerRadius * 0.44f
            val pillBaseY = with(density) { -outerRadius - 36.dp.toPx() }
            val currentBadgeX = dragOffset.x
            val currentBadgeY = pillBaseY + dragOffset.y
            val distToBlobCenter = kotlin.math.hypot(currentBadgeX.toDouble(), currentBadgeY.toDouble()).toFloat()

            // Blob body radius inside aperture (~110.dp mascot)
            val blobBodyRadius = apertureRadius * 0.65f

            // Dynamic mouth opening: mouth opens ONLY when file enters its body with NO odd mouth line
            val mouthOpenAmount: Float = if (isSwallowing) {
                val frame = BlobSwallowTimeline.at(swallowAnim.value)
                if (frame.gulp && !hasGulped) {
                    hasGulped = true
                    HootSoundManager.playSwallow()
                }
                frame.mouthOpen
            } else if (isDragging && distToBlobCenter <= blobBodyRadius) {
                val entryProgress = (blobBodyRadius - distToBlobCenter) / blobBodyRadius
                entryProgress.coerceIn(0f, 1f)
            } else {
                0f
            }

            // Swallow flight keyframes for file token
            val swallowFrame = if (isSwallowing) BlobSwallowTimeline.at(swallowAnim.value) else null
            val animFlight = swallowFrame?.tokenProgress ?: 0f
            val animArc = if (isSwallowing) kotlin.math.sin(animFlight * Math.PI).toFloat() * with(density) { 14.dp.toPx() } else 0f
            val badgeRenderX = if (isSwallowing) swallowStartOffset.x * (1f - animFlight) else dragOffset.x
            val badgeRenderY = if (isSwallowing) swallowStartOffset.y * (1f - animFlight) - animArc else (pillBaseY + dragOffset.y)
            val badgeScale = swallowFrame?.tokenScale ?: 1f
            val badgeAlpha = swallowFrame?.tokenOpacity ?: 1f
            val badgeVisible = swallowFrame?.tokenVisible ?: true

            // Top info bar: Premium Frosted Glass Pill matching Windows Hoot
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x40000000), spotColor = Color(0x60000000))
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0x32FFFFFF),
                                    Color(0x18FFFFFF),
                                    Color(0x0CFFFFFF)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                1.2.dp,
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x75FFFFFF),
                                        Color(0x28A499F7),
                                        Color(0x10FFFFFF)
                                    )
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = currentFile!!.displayName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${currentFile!!.extension.uppercase()} · ${formatFileSize(currentFile!!.size)}",
                            color = Color(0xFFC7BFFF),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Radial Wheel with Blobatar in aperture
            DonutWheel(
                modifier = Modifier.fillMaxSize(),
                slices = currentSlices,
                hoveredIndex = hoveredIndex,
                expandedGroupIndex = expandedGroupIndex,
                hoveredSubIndex = hoveredSubIndex,
                tone = blobTone,
                targetOffset = if (isDragging || isSwallowing) {
                    Offset(wheelCenter.x + badgeRenderX, wheelCenter.y + badgeRenderY)
                } else touchPosition,
                mouthOpen = mouthOpenAmount,
                flipTrigger = flipTrigger,
                progress = when (val state = uiState) {
                    is ConversionUiState.Converting -> state.progress
                    else -> 0f
                },
                isConverting = uiState is ConversionUiState.Converting,
                isCelebrating = uiState is ConversionUiState.Success,
                centerTitle = "$currentFileCount × ${currentFile!!.extension.uppercase()}",
                centerSubtitle = if (expandedGroupIndex >= 0) "Move outward to choose" else "Esc to close",
                centerFlipHint = if (faceIndex == 0) "↺ Tab front" else "↺ Tab back",
                onFlip = {
                    viewModel.flipWheel()
                },
                onSliceClick = { hit ->
                    viewModel.onDropOverSlice(hit)
                },
                onSubSliceClick = { groupIdx, subIdx ->
                    viewModel.onDropOverSubSlice(groupIdx, subIdx)
                }
            )

            // Draggable File Extension Badge Pill - docked cleanly above the wheel
            if (uiState is ConversionUiState.Ready) {
                val extText = currentFile!!.extension.uppercase().ifEmpty { "FILE" }
                val currentSlicesState by androidx.compose.runtime.rememberUpdatedState(currentSlices)
                val currentExpandedGroupIndex by androidx.compose.runtime.rememberUpdatedState(expandedGroupIndex)
                val isSwallowingState by androidx.compose.runtime.rememberUpdatedState(isSwallowing)

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                badgeRenderX.roundToInt(),
                                badgeRenderY.roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                            alpha = if (badgeVisible) badgeAlpha else 0f
                        }
                        .pointerInput(currentFile) {
                            detectDragGestures(
                                onDragStart = {
                                    if (isSwallowingState) return@detectDragGestures
                                    isDragging = true
                                    HootSoundManager.playBlobPress()
                                },
                                onDragEnd = {
                                    isDragging = false
                                    val finalDist = kotlin.math.hypot(dragOffset.x.toDouble(), (pillBaseY + dragOffset.y).toDouble()).toFloat()
                                    if (finalDist <= blobBodyRadius * 1.15f) {
                                        // Dropped into center blob mouth: smooth Windows Hoot swallow & cancel!
                                        isSwallowing = true
                                        swallowStartOffset = Offset(dragOffset.x, pillBaseY + dragOffset.y)
                                    } else {
                                        val currentTouch = Offset(
                                            wheelCenter.x + dragOffset.x,
                                            wheelCenter.y + pillBaseY + dragOffset.y
                                        )
                                        val groupIdx = currentExpandedGroupIndex
                                        val slicesNow = currentSlicesState
                                        var hitSub = -1
                                        if (groupIdx in slicesNow.indices) {
                                            hitSub = hitTestSubSlice(
                                                touchPos = currentTouch,
                                                center = wheelCenter,
                                                outerRadius = outerRadius,
                                                expandedGroupIndex = groupIdx,
                                                sliceCount = slicesNow.size,
                                                subCount = slicesNow[groupIdx].subSlices.size
                                            )
                                        }

                                        dragOffset = Offset.Zero
                                        if (hitSub >= 0) {
                                            HootSoundManager.playDrop()
                                            viewModel.onDropOverSubSlice(groupIdx, hitSub)
                                        } else {
                                            val hit = hitTestSlice(
                                                touchPos = currentTouch,
                                                center = wheelCenter,
                                                outerRadius = outerRadius,
                                                sliceCount = slicesNow.size
                                            )
                                            if (hit >= 0) {
                                                HootSoundManager.playDrop()
                                                viewModel.onDropOverSlice(hit)
                                            } else {
                                                viewModel.setHoveredSlice(-1)
                                                viewModel.setHoveredSub(-1)
                                                viewModel.collapseGroup()
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragOffset = Offset.Zero
                                    viewModel.setHoveredSlice(-1)
                                    viewModel.setHoveredSub(-1)
                                },
                                onDrag = { change, dragAmount ->
                                    if (isSwallowingState) return@detectDragGestures
                                    change.consume()
                                    dragOffset += dragAmount
                                    val curX = dragOffset.x
                                    val curY = pillBaseY + dragOffset.y
                                    val curDist = kotlin.math.hypot(curX.toDouble(), curY.toDouble()).toFloat()
                                    if (curDist <= blobBodyRadius) {
                                        // Inside center blob: hover effect on slice is cleared
                                        viewModel.setHoveredSlice(-1)
                                        viewModel.setHoveredSub(-1)
                                    } else {
                                        val currentTouch = Offset(
                                            wheelCenter.x + curX,
                                            wheelCenter.y + curY
                                        )
                                        val groupIdx = currentExpandedGroupIndex
                                        val slicesNow = currentSlicesState
                                        var hitSub = -1
                                        if (groupIdx in slicesNow.indices) {
                                            hitSub = hitTestSubSlice(
                                                touchPos = currentTouch,
                                                center = wheelCenter,
                                                outerRadius = outerRadius,
                                                expandedGroupIndex = groupIdx,
                                                sliceCount = slicesNow.size,
                                                subCount = slicesNow[groupIdx].subSlices.size
                                            )
                                        }

                                        if (hitSub >= 0) {
                                            viewModel.setHoveredSub(hitSub)
                                            viewModel.setHoveredSlice(-1)
                                        } else {
                                            viewModel.setHoveredSub(-1)
                                            val hit = hitTestSlice(
                                                touchPos = currentTouch,
                                                center = wheelCenter,
                                                outerRadius = outerRadius,
                                                sliceCount = slicesNow.size
                                            )
                                            viewModel.setHoveredSlice(hit)
                                            if (hit >= 0 && slicesNow[hit].isGroup) {
                                                viewModel.expandGroup(hit)
                                            } else if (hit >= 0 && hit != groupIdx) {
                                                viewModel.collapseGroup()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .shadow(
                            elevation = if (isDragging) 24.dp else 12.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = if (isDragging) Color(0x778471E2) else Color(0x35000000),
                            spotColor = if (isDragging) Color(0xAA8471E2) else Color(0x50000000)
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            brush = if (isDragging) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF9F8FFF),
                                        Color(0xEE8471E2),
                                        Color(0xDD6C57D6)
                                    )
                                )
                            } else {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0x45FFFFFF),
                                        Color(0x25FFFFFF),
                                        Color(0x14FFFFFF)
                                    )
                                )
                            }
                        )
                        .border(
                            BorderStroke(
                                1.4.dp,
                                Brush.verticalGradient(
                                    colors = if (isDragging) {
                                        listOf(
                                            Color(0xFFFFFFFF),
                                            Color(0xBBA499F7),
                                            Color(0x77FFFFFF)
                                        )
                                    } else {
                                        listOf(
                                            Color(0x95FFFFFF),
                                            Color(0x40A499F7),
                                            Color(0x18FFFFFF)
                                        )
                                    }
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FileBadgeIcon(
                            size = 17.dp,
                            tint = if (isDragging) Color.White else Color(0xFFD4CCFF),
                            extension = currentFile!!.extension
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = extText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    }
                }
            }
        }

        // ==========================================
        // Rename Dialog
        // ==========================================
        if (pendingSlice != null && currentFile != null) {
            RenameDialog(
                originalName = currentFile!!.displayName,
                targetFormat = pendingSlice!!.targetFormat,
                slice = pendingSlice,
                onDismiss = { viewModel.dismissRenameDialog() },
                onConfirm = { finalName ->
                    viewModel.startConversion(
                        context = context,
                        finalFileName = finalName,
                        targetFormat = pendingSlice!!.targetFormat,
                        resizePercent = pendingSlice!!.resizePercent,
                        targetWidth = pendingSlice!!.targetWidth,
                        targetMaxMb = pendingSlice!!.targetMaxMb,
                        rotationDegrees = pendingSlice!!.rotationDegrees,
                        flipHorizontal = pendingSlice!!.flipHorizontal,
                        flipVertical = pendingSlice!!.flipVertical,
                        cropAspectRatio = pendingSlice!!.cropAspectRatio,
                        removeBackground = pendingSlice!!.id == "bg-remove"
                    )
                }
            )
        }

        // ==========================================
        // Error Message Overlay
        // ==========================================
        if (uiState is ConversionUiState.Error) {
            val error = uiState as ConversionUiState.Error
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xEE3D1520),
                                Color(0xDD280E16)
                            )
                        )
                    )
                    .border(
                        BorderStroke(1.2.dp, Color(0x66FF6B6B)),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "Conversion Failed",
                        color = Color(0xFFFF8585),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = error.message, color = Color.White, fontSize = 13.5.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.cancelConversion() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8471E2)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Dismiss", color = Color.White)
                    }
                }
            }
        }

        IconButton(
            onClick = { showUpdateSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
        ) {
            Text("⚙", color = Color.White, fontSize = 23.sp, modifier = Modifier.semantics {
                contentDescription = if (availableUpdate != null) "Settings, update available" else "Settings"
            })
        }

        if (pendingApproval != null && !hidePendingPrompt && !showUpdateSettings) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp)
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF21A2433))
                    .border(BorderStroke(1.dp, Color(0x995E4BBA)), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text("Hoot update ready", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Android needs your approval to finish installing version ${pendingApproval!!.versionName}.", color = Color(0xFFE1DDFB), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button(
                            onClick = {
                                val tag = pendingApproval!!.tag
                                val confirmation = HootUpdater.pendingConfirmationIntent(context)
                                if (confirmation != null) context.startActivity(confirmation)
                                else context.startActivity(Intent(context, UpdateInstallActivity::class.java).putExtra(UpdateInstallActivity.EXTRA_TAG, tag))
                                hidePendingPrompt = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7665D8)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Review", color = Color.White) }
                        TextButton(onClick = { hidePendingPrompt = true }) { Text("Later", color = Color(0xFFD3CCFF)) }
                    }
                }
            }
        }

        if (showUpdateSettings) {
            HootUpdateSettingsDialog(
                onDismiss = { showUpdateSettings = false },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
