package com.kit.video.sample

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.video.sample.ui.screens.EditorScreen
import com.kit.video.sample.ui.screens.MainHomeScreen
import com.kit.video.sample.ui.screens.SinglePlayScreen
import com.kit.video.sample.ui.screens.VideoListScreen

enum class AppScreen {
    Home,
    List,
    Editor,
    SinglePlay
}

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PIP_TOGGLE_PLAY = "com.kit.video.sample.ACTION_PIP_TOGGLE_PLAY"
    }

    private var activeScreen: AppScreen = AppScreen.Home
    private var isInPipModeState by mutableStateOf(false)
    private var isPlayingState by mutableStateOf(true)
    private var isNetworkOnlineState by mutableStateOf(true)

    // PiP 系统的广播接收器：响应桌面小窗上的原生播放/暂停点击
    private val pipBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_PLAY) {
                isPlayingState = !isPlayingState
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updatePipActions(isPlayingState)
                }
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isNetworkOnlineState = true
        }

        override fun onLost(network: Network) {
            isNetworkOnlineState = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 监听网络连通状态
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注册 NetworkCallback 异常", e)
        }

        val filter = IntentFilter(ACTION_PIP_TOGGLE_PLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipBroadcastReceiver, filter)
        }

        setContent {
            SampleTheme {
                MainAppRouter(
                    isInPipMode = isInPipModeState,
                    isPlayingState = isPlayingState,
                    isNetworkOnline = isNetworkOnlineState,
                    onScreenChange = { activeScreen = it },
                    onPlayingStateChanged = { playing ->
                        isPlayingState = playing
                        if (isInPipModeState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            updatePipActions(playing)
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipBroadcastReceiver)
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注销组件异常", e)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 按 Home 键切桌面时自动切系统级画中画 (PiP Mode)
        if (activeScreen == AppScreen.SinglePlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))

                val actionIntent = Intent(ACTION_PIP_TOGGLE_PLAY).setPackage(packageName)
                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val iconRes = if (isPlayingState) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                val title = if (isPlayingState) "Pause" else "Play"
                val action = RemoteAction(
                    Icon.createWithResource(this, iconRes),
                    title,
                    title,
                    pendingIntent
                )
                paramsBuilder.setActions(listOf(action))

                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "触发系统级画中画 PiP 异常", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipActions(isPlaying: Boolean) {
        val actionIntent = Intent(ACTION_PIP_TOGGLE_PLAY).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) "Pause" else "Play"
        val action = RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .build()

        setPictureInPictureParams(params)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
        if (isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipActions(isPlayingState)
        }
    }
}

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFBB86FC),
        secondary = Color(0xFF03DAC6),
        background = Color(0xFF101216),
        surface = Color(0xFF1E222A)
    )
    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}

@Composable
fun MainAppRouter(
    isInPipMode: Boolean,
    isPlayingState: Boolean,
    isNetworkOnline: Boolean,
    onScreenChange: (AppScreen) -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf<AppScreen>(AppScreen.Home) }

    LaunchedEffect(currentScreen) {
        onScreenChange(currentScreen)
    }

    // 处理系统返回按键
    BackHandler(enabled = currentScreen != AppScreen.Home) {
        currentScreen = AppScreen.Home
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(color = Color(0xFF101216)) {
            Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                when (screen) {
                    AppScreen.Home -> MainHomeScreen(
                        onNavigateToList = { currentScreen = AppScreen.List },
                        onNavigateToEditor = { currentScreen = AppScreen.Editor },
                        onNavigateToSinglePlay = { currentScreen = AppScreen.SinglePlay }
                    )

                    AppScreen.List -> VideoListScreen(
                        onBackClick = { currentScreen = AppScreen.Home },
                        onGoSinglePlay = { currentScreen = AppScreen.SinglePlay }
                    )

                    AppScreen.Editor -> EditorScreen(
                        onBackClick = { currentScreen = AppScreen.Home }
                    )

                    AppScreen.SinglePlay -> SinglePlayScreen(
                        onBackClick = { currentScreen = AppScreen.Home },
                        isInPipMode = isInPipMode,
                        externalIsPlaying = isPlayingState,
                        onPlayingStateChanged = onPlayingStateChanged
                    )
                }
            }
        }

        // 全局网络断连高亮横幅提示 (Network Recovery Banner)
        AnimatedVisibility(
            visible = !isNetworkOnline && !isInPipMode,
            enter = expandVertically(),
            exit = shrinkVertically(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFD32F2F))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📡 网络连接已断开 - 网络恢复后将自动为您恢复播放",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}