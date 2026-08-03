package com.kit.video.sample.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.kit.video.feed.FeedPlayerManager
import com.kit.video.sample.media.SampleMediaCatalog

@Composable
fun FeedScreen(
    onBackClick: () -> Unit,
    onGoVod: () -> Unit
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

    // 关键防重复与沉降优化 + Preload Queue 后台预加载队列：
    // 1. 只有在手指停止拖拽 (!isScrollInProgress) 且真正翻到了新 Page (settledPage != lastPlayedPage) 时，才发起起播；
    // 2. 当前起播的同时，后台线程自动触发对下个 Page (settledPage + 1) 的前 2MB 数据静默预拉取，切页时 0ms 闪电秒播！
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val settledPage = pagerState.settledPage
            if (settledPage != lastPlayedPage) {
                val coverView = coverViews[settledPage]
                val item = videoList.getOrNull(settledPage)
                if (coverView != null && item != null) {
                    lastPlayedPage = settledPage
                    exoManager.playVideoFromCoverView(coverView, item.primaryUrl)

                    // 触发 Preload Queue：后台静默预拉取下一个视频的数据
                    val nextItem = videoList.getOrNull(settledPage + 1)
                    if (nextItem != null) {
                        exoManager.preloadNextVideo(nextItem.primaryUrl)
                    }
                }
            }
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
                Box(modifier = Modifier.fillMaxSize()) {
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
                            .padding(24.dp)
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

            // 顶部返回与跳转操作栏
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

            Button(
                onClick = onGoVod,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Single Mode", color = Color.White)
            }
        }
    }
}
