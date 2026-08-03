package com.kit.video.sample.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.kit.video.clip.ClipEngine
import com.kit.video.clip.ClipParams
import com.kit.video.feed.FeedPlayerManager
import com.kit.video.sample.media.SampleMediaCatalog

enum class ClipTab {
    TRIM, FILTER, ADJUST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val exoManager = remember(lifecycleOwner, context) {
        FeedPlayerManager(lifecycleOwner, context)
    }
    val editorEngine = remember(context) {
        ClipEngine(context)
    }

    val previewSource = remember { SampleMediaCatalog.clipPreview }
    val onlineVideoUrl = previewSource.primaryUrl

    var selectedTab by remember { mutableStateOf(ClipTab.TRIM) }
    var trimRange by remember { mutableStateOf(10f..80f) }
    var selectedFilter by remember { mutableStateOf("Original") }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var contrast by remember { mutableFloatStateOf(0.5f) }
    var saturation by remember { mutableFloatStateOf(0.5f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("剪辑 Clip (Timeline Track)", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editorEngine.updateParams(
                            ClipParams(
                                trimStartMs = (trimRange.start * 1000).toLong(),
                                trimEndMs = (trimRange.endInclusive * 1000).toLong(),
                                filterName = selectedFilter,
                                brightness = brightness,
                                contrast = contrast,
                                saturation = saturation
                            )
                        )
                        onBackClick()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Export",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101216))
            )
        },
        containerColor = Color(0xFF101216)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 上半部分：视频实时预览 PlayerView 与参数悬浮面板
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            exoManager.playVideoFromUrl(this, onlineVideoUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 左上角：滤镜与裁剪状态面板
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            text = "Filter: $selectedFilter",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Trim: ${trimRange.start.toInt()}s - ${trimRange.endInclusive.toInt()}s",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // 右上角：实时调参仪表板 (ADJUST Live Preview)
                if (selectedTab == ClipTab.ADJUST) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("☀️ Brightness: ${(brightness * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                            Text("🌗 Contrast: ${(contrast * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                            Text("🎨 Saturation: ${(saturation * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }

            // 下半部分：Compose 编辑控制面板
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1E222A),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 功能 Tab 选择
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        TabButton("裁剪", Icons.Default.ContentCut, selectedTab == ClipTab.TRIM) {
                            selectedTab = ClipTab.TRIM
                        }
                        TabButton("滤镜", Icons.Default.Filter, selectedTab == ClipTab.FILTER) {
                            selectedTab = ClipTab.FILTER
                        }
                        TabButton("调节", Icons.Default.Tune, selectedTab == ClipTab.ADJUST) {
                            selectedTab = ClipTab.ADJUST
                        }
                    }

                    // Tab 内容区
                    when (selectedTab) {
                        ClipTab.TRIM -> {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("视频剪裁波形轨", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "时长: ${((trimRange.endInclusive - trimRange.start)).toInt()}s",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                // 可视化时间轴胶卷轨 Canvas
                                TimelineTrackVisualizer(
                                    trimRange = trimRange,
                                    primaryColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                )

                                RangeSlider(
                                    value = trimRange,
                                    onValueChange = { trimRange = it },
                                    valueRange = 0f..100f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        ClipTab.FILTER -> {
                            val filters = listOf("Original", "Vivid", "Warm", "Mono", "Cyberpunk", "Vintage")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(filters) { filter ->
                                    val isSelected = selectedFilter == filter
                                    Box(
                                        modifier = Modifier
                                            .size(75.dp, 52.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C323F))
                                            .clickable { selectedFilter = filter },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(filter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        ClipTab.ADJUST -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AdjustSlider("亮度 (Brightness)", brightness) { brightness = it }
                                AdjustSlider("对比度 (Contrast)", contrast) { contrast = it }
                                AdjustSlider("饱和度 (Saturation)", saturation) { saturation = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 精致可视化视频剪辑时间轴波形/胶卷轨 Canvas 控件
 */
@Composable
fun TimelineTrackVisualizer(
    trimRange: ClosedFloatingPointRange<Float>,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barCount = 40
        val barGap = width / barCount
        val handleWidthPx = 4.dp.toPx()

        // 1. 背景暗色波形轨/胶卷块
        for (i in 0 until barCount) {
            val barHeight = (height * (0.3f + (i % 5) * 0.14f)).coerceAtMost(height)
            val x = i * barGap
            val y = (height - barHeight) / 2
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.35f),
                topLeft = Offset(x, y),
                size = Size(barGap * 0.65f, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }

        // 2. 剪裁高亮选区 Window (亮品牌色高亮 Track)
        val startX = (trimRange.start / 100f) * width
        val endX = (trimRange.endInclusive / 100f) * width
        val highlightWidth = (endX - startX).coerceAtLeast(0f)

        drawRoundRect(
            color = primaryColor.copy(alpha = 0.25f),
            topLeft = Offset(startX, 0f),
            size = Size(highlightWidth, height),
            cornerRadius = CornerRadius(4f, 4f)
        )

        // 3. 左右边界 Handle 线
        drawRect(
            color = primaryColor,
            topLeft = Offset(startX, 0f),
            size = Size(handleWidthPx, height)
        )
        drawRect(
            color = primaryColor,
            topLeft = Offset((endX - handleWidthPx).coerceAtLeast(0f), 0f),
            size = Size(handleWidthPx, height)
        )
    }
}

@Composable
private fun TabButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2C323F)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(label, color = if (selected) Color.White else Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
        )
    }
}
