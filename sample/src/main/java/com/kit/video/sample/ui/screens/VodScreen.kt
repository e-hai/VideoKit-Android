package com.kit.video.sample.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import com.kit.video.ffmpeg.FfmpegRenderersFactory
import com.kit.video.sample.media.SampleMediaCatalog
import com.kit.video.sample.media.SampleVideo
import com.kit.video.vod.VodPlayer
import com.kit.video.vod.VodPlayerView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("DefaultLocale")
@Composable
fun VodScreen(
    onBackClick: () -> Unit,
    isInPipMode: Boolean = false,
    externalIsPlaying: Boolean = true,
    onPlayingStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val testVideoList = remember { SampleMediaCatalog.vodSources }

    var currentSourceIndex by remember { mutableIntStateOf(0) }
    var currentUrlIndex by remember { mutableIntStateOf(0) }
    val currentVideoSource = testVideoList[currentSourceIndex]
    val currentPlayUrl = currentVideoSource.urls[
        currentUrlIndex.coerceIn(0, currentVideoSource.urls.lastIndex)
    ]

    var lastErrorText by remember { mutableStateOf<String?>(null) }
    var fallbackNotice by remember { mutableStateOf<String?>(null) }

    var playerViewInstance by remember { mutableStateOf<VodPlayerView?>(null) }
    var selectedSpeed by remember { mutableFloatStateOf(1.0f) }
    var isLandscape by remember { mutableStateOf(false) }

    var activeDecoderName by remember { mutableStateOf("Initializing...") }
    var isHardwareDecoder by remember { mutableStateOf(true) }

    var showLabSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    val labSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val speedOptions = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    val renderersFactory = remember {
        FfmpegRenderersFactory(context)
    }

    LaunchedEffect(currentSourceIndex) {
        currentUrlIndex = 0
        lastErrorText = null
        fallbackNotice = null
    }

    LaunchedEffect(isLandscape) {
        if (!isInPipMode) {
            activity?.let { act ->
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isLandscape) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    controller.hide(
                        WindowInsetsCompat.Type.statusBars() or
                            WindowInsetsCompat.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    controller.show(
                        WindowInsetsCompat.Type.statusBars() or
                            WindowInsetsCompat.Type.navigationBars()
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars()
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        VodPlayer(
            url = currentPlayUrl,
            title = currentVideoSource.name,
            renderersFactory = renderersFactory,
            speed = selectedSpeed,
            isInPipMode = isInPipMode,
            externalIsPlaying = externalIsPlaying,
            onBackClick = onBackClick,
            onPlayingStateChanged = onPlayingStateChanged,
            onDecoderChanged = { name, isHw ->
                activeDecoderName = name
                isHardwareDecoder = isHw
            },
            onPlayerError = { error ->
                lastErrorText = "${error.errorCodeName}: ${error.message}"
                val canSwitchMirror = isSourceAvailabilityError(error) &&
                    currentUrlIndex < currentVideoSource.urls.lastIndex
                if (canSwitchMirror) {
                    val next = currentUrlIndex + 1
                    currentUrlIndex = next
                    fallbackNotice =
                        "主源不可用，已切换备用地址 (${next + 1}/${currentVideoSource.urls.size})"
                    lastErrorText = null
                }
            },
            onPlayerViewReady = { view ->
                playerViewInstance = view
            },
            onFullscreenChanged = { landscape ->
                isLandscape = landscape
            },
            onSpeedClick = { showSpeedSheet = true },
            topTrailingContent = {
                IconButton(
                    onClick = { showLabSheet = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2196F3).copy(alpha = 0.85f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Science,
                        contentDescription = "调试面板",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            bannerContent = if (fallbackNotice != null) {
                {
                    Text(
                        text = fallbackNotice.orEmpty(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFFFF9800).copy(alpha = 0.92f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                null
            }
        )
    }

    if (showLabSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLabSheet = false },
            sheetState = labSheetState,
            containerColor = Color(0xFF1A1C20)
        ) {
            LabBottomSheetContent(
                testVideoList = testVideoList,
                currentSourceIndex = currentSourceIndex,
                currentUrlIndex = currentUrlIndex,
                activeDecoderName = activeDecoderName,
                isHardwareDecoder = isHardwareDecoder,
                lastErrorText = lastErrorText,
                fallbackNotice = fallbackNotice,
                onSelectSource = { index ->
                    currentSourceIndex = index
                }
            )
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            sheetState = speedSheetState,
            containerColor = Color(0xFF1A1C20)
        ) {
            SpeedBottomSheetContent(
                speedOptions = speedOptions,
                selectedSpeed = selectedSpeed,
                onSelectSpeed = { speed ->
                    selectedSpeed = speed
                    playerViewInstance?.setSpeed(speed)
                    showSpeedSheet = false
                }
            )
        }
    }
}

@Composable
private fun LabBottomSheetContent(
    testVideoList: List<SampleVideo>,
    currentSourceIndex: Int,
    currentUrlIndex: Int,
    activeDecoderName: String,
    isHardwareDecoder: Boolean,
    lastErrorText: String?,
    fallbackNotice: String?,
    onSelectSource: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "调试面板",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            text = "硬解优先，FFmpeg Renderer 兜底；片源不可用时自动切换同编码备用 URL。",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Text(
            text = "当前解码器",
            color = Color.LightGray,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badgeBg = if (isHardwareDecoder) Color(0xFF4CAF50) else Color(0xFFFF9800)
            Text(
                text = if (isHardwareDecoder) "MediaCodec 硬解" else "FFmpeg 软解",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = activeDecoderName,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        val statusText = fallbackNotice ?: lastErrorText
        if (statusText != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = statusText,
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFF9800).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "测试视频源",
            color = Color.LightGray,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        testVideoList.forEachIndexed { index, source ->
            val selected = index == currentSourceIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectSource(index) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.name,
                            color = Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = source.codec,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(
                                    if (source.likelyNeedsSoftFallback) Color(0xFFFF9800)
                                    else Color(0xFF4CAF50),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = source.description +
                            if (selected) " · mirror ${currentUrlIndex + 1}/${source.urls.size}"
                            else " · ${source.urls.size} mirrors",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (selected) {
                    Text(
                        text = "播放中",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedBottomSheetContent(
    speedOptions: List<Float>,
    selectedSpeed: Float,
    onSelectSpeed: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, contentDescription = null, tint = Color.White)
            Text(
                text = "播放倍速",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            speedOptions.forEach { speed ->
                val selected = speed == selectedSpeed
                Text(
                    text = "${speed}x",
                    color = if (selected) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 48.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectSpeed(speed) }
                        .padding(vertical = 14.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 片源/网络类错误才切镜像；解码器能力问题不切（备用 URL 同编码）。 */
private fun isSourceAvailabilityError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
        else -> {
            val name = error.errorCodeName.orEmpty()
            name.contains("IO_", ignoreCase = true) ||
                name.contains("NETWORK", ignoreCase = true) ||
                name.contains("HTTP", ignoreCase = true)
        }
    }
}
