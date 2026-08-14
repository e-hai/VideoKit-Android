package com.kit.video.sample.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kit.video.feed.FeedCoverPlayerView
import com.kit.video.feed.FeedMicroProgressBar
import com.kit.video.feed.FeedPlayerManager
import com.kit.video.sample.media.SampleMediaCatalog
import kotlinx.coroutines.delay

@Composable
fun FeedScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val videoList = remember { SampleMediaCatalog.feedSources }

    val exoManager = remember(lifecycleOwner, context) {
        FeedPlayerManager(lifecycleOwner, context)
    }

    val pagerState = rememberPagerState(pageCount = { videoList.size })
    // 保存每个 Page 的 FeedCoverPlayerView 实例
    val coverViews = remember { mutableStateMapOf<Int, FeedCoverPlayerView>() }
    // 记录上一次真正发起的播放页面 Index，避免弹回原视频时重复加载
    var lastPlayedPage by remember { mutableIntStateOf(-1) }

    // 关键防重复与沉降优化 + 🚀 工业级双向预加载队列 (Bidirectional Preload Queue)：
    // 1. 只有在手指停止拖拽 (!isScrollInProgress) 且真正翻到了新 Page (settledPage != lastPlayedPage) 时，才发起起播；
    // 2. 当前起播的同时，后台线程自动并发/排队预热下一个视频 (page + 1) 与上一个视频 (page - 1) 的前 2MB 数据，双向滑动皆为 0ms 秒播！
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val settledPage = pagerState.settledPage
            if (settledPage != lastPlayedPage) {
                val coverView = coverViews[settledPage]
                val item = videoList.getOrNull(settledPage)
                if (coverView != null && item != null) {
                    lastPlayedPage = settledPage
                    exoManager.playVideoFromCoverView(coverView, item.primaryUrl)

                    // 🚀 触发双向预加载队列：上下双向智能预拉取
                    val prevItem = videoList.getOrNull(settledPage - 1)
                    val nextItem = videoList.getOrNull(settledPage + 1)
                    exoManager.preloadBidirectional(
                        prevVideoUrl = prevItem?.primaryUrl,
                        nextVideoUrl = nextItem?.primaryUrl
                    )
                }
            }
        }
    }

    // 实时播放进度轮询 (支持底栏微型进度条)
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }

    LaunchedEffect(exoManager) {
        while (true) {
            currentPositionMs = exoManager.getCurrentPosition()
            bufferedPositionMs = exoManager.getBufferedPosition()
            durationMs = exoManager.getDuration()
            isPlaying = exoManager.isPlaying()
            delay(100)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = videoList[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (exoManager.isPlaying()) {
                                exoManager.pause()
                            } else {
                                exoManager.play()
                            }
                            isPlaying = exoManager.isPlaying()
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FeedCoverPlayerView(ctx).also { view ->
                                coverViews[page] = view
                            }
                        },
                        update = { view ->
                            coverViews[page] = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 抖音风格暂停状态居中半透明大播放图标
                    if (!isPlaying && page == pagerState.settledPage) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(72.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    // 抖音风格视频底部信息界面
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                )
                            )
                            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)
                    ) {
                        Text(
                            text = item.author,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "${item.codec} · Swipe up · ${page + 1}/${videoList.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // 🚀 抖音级底部极细微拖拽进度条 (Feed Micro Scrubbing Bar)
            FeedMicroProgressBar(
                currentPositionMs = currentPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                onSeekTo = { targetMs ->
                    exoManager.seekTo(targetMs)
                    currentPositionMs = targetMs
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )

            // 顶部返回操作栏
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 20.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}
