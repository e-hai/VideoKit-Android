@file:OptIn(ExperimentalMaterial3Api::class)

package com.kit.video.sample.editor

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private data class VideoPickerInfo(
    val videoUri: Uri,
    val cropInfo: CropInfo
)

// 裁剪信息数据类
private data class CropInfo(
    val cropRect: Rect,        // 用户选择的裁剪区域（相对于视频原始尺寸的像素坐标）
    val videoSize: IntSize     // 视频原始尺寸（像素）
)

@Composable
fun VideoEditorScreen(
) {
    var openFullScreen by remember { mutableStateOf(false) }
    var verticalPickerInfo by remember { mutableStateOf<VideoPickerInfo?>(null) }
    var horizontalPickerInfo by remember { mutableStateOf<VideoPickerInfo?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (openFullScreen) {
            FullScreenPreview(verticalPickerInfo ?: return, horizontalPickerInfo ?: return)
        } else {
            VideoPickerScreen(onClickFullScreen = { vertical, horizontal ->
                verticalPickerInfo = vertical
                horizontalPickerInfo = horizontal
                openFullScreen = true
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPickerScreen(onClickFullScreen: (verticalPickerInfo: VideoPickerInfo, horizontalPickerInfo: VideoPickerInfo) -> Unit) {

    var verticalVideoUri by remember { mutableStateOf<Uri?>(null) }
    var horizontalVideoUri by remember { mutableStateOf<Uri?>(null) }

    val verticalPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        verticalVideoUri = uri
    }
    val horizontalPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        horizontalVideoUri = uri
    }

    var verticalPickerInfo by remember { mutableStateOf<VideoPickerInfo?>(null) }
    var horizontalPickerInfo by remember { mutableStateOf<VideoPickerInfo?>(null) }

    var selectVertical by remember { mutableStateOf(true) }

    var openSheet by remember { mutableStateOf(false) }


    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (verticalVideoUri == null) {
            Button(
                onClick = { verticalPickerLauncher.launch("video/*") },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("从相册选择视频")
            }

            Text(
                text = "提示：选择视频后可以调整选框查看局部",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (selectVertical) {
                    val videoUri = verticalVideoUri ?: return
                    VerticalCropVideoPlayer(
                        videoUri,
                        onSelectOtherVideo = {
                            verticalPickerLauncher.launch("video/*")
                        },
                        onClickOpenSheet = {
                            openSheet = true
                        },
                        onCropInfoChange = {
                            verticalPickerInfo = VideoPickerInfo(videoUri, it)
                        })
                } else {
                    val videoUri = horizontalVideoUri ?: return
                    HorizontalCropVideoPlayer(
                        videoUri,
                        onSelectOtherVideo = {
                            horizontalPickerLauncher.launch("video/*")
                        },
                        onClickOpenSheet = {
                            openSheet = true
                        },
                        onCropInfoChange = {
                            horizontalPickerInfo = VideoPickerInfo(videoUri, it)
                        }
                    )
                }

                if (openSheet) {
                    // 显示半屏弹窗展示裁剪后的视频预览
                    val verticalInfo = verticalPickerInfo ?: return
                    val horizontalInfo = horizontalPickerInfo ?: verticalInfo.copy()

                    HorizontalVerticalPreview(
                        verticalInfo,
                        horizontalInfo,
                        onDismissRequest = { openSheet = false },
                        onClickLandscapeModeSettings = {
                            openSheet = false
                            selectVertical = false
                            horizontalPickerLauncher.launch("video/*")
                        },
                        onClickConfirm = {
                            onClickFullScreen(verticalInfo, horizontalInfo)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalCropVideoPlayer(
    videoUri: Uri,
    onSelectOtherVideo: () -> Unit,
    onClickOpenSheet: () -> Unit,
    onCropInfoChange: (CropInfo) -> Unit = {}
) {

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            CropVideoPlayer(
                videoUri = videoUri,
                aspectRatioMode = AspectRatioMode.VERTICAL_111_241,
                onCropInfoChange = onCropInfoChange
            )
        }

        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Button(
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { onSelectOtherVideo() }
            ) {
                Text("选择其他视频")
            }

            FloatingActionButton(
                onClick = {
                    onClickOpenSheet()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Default.Check, contentDescription = "确认")
            }
        }
    }
}

@Composable
private fun HorizontalCropVideoPlayer(
    videoUri: Uri,
    onSelectOtherVideo: () -> Unit,
    onClickOpenSheet: () -> Unit,
    onCropInfoChange: (CropInfo) -> Unit = {}
) {

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            CropVideoPlayer(
                videoUri = videoUri,
                aspectRatioMode = AspectRatioMode.HORIZONTAL_193_89,
                onCropInfoChange = onCropInfoChange
            )
        }

        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Button(
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { onSelectOtherVideo() }
            ) {
                Text("选择其他视频")
            }

            FloatingActionButton(
                onClick = {
                    onClickOpenSheet()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Default.Check, contentDescription = "确认")
            }
        }
    }
}

@Composable
private fun RotatedBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        // 交换约束的宽高
        val swappedConstraints = constraints.copy(
            maxWidth = constraints.maxHeight,
            maxHeight = constraints.maxWidth,
            minWidth = if (constraints.hasFixedHeight) constraints.maxHeight else 0,
            minHeight = if (constraints.hasFixedWidth) constraints.maxWidth else 0
        )

        val placeable = measurables.first().measure(swappedConstraints)

        // 布局时也交换宽高
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = (placeable.height - placeable.width) / 2,
                y = -(placeable.height - placeable.width) / 2,
                zIndex = 0f
            )
        }
    }
}

/**
 * 全屏裁剪预览
 * **/
@SuppressLint("ConfigurationScreenWidthHeight")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullScreenPreview(verticalPickerInfo: VideoPickerInfo, horizontalPickerInfo: VideoPickerInfo) {
    var isVertical by remember { mutableStateOf(true) }

    val context = LocalContext.current

    //竖屏预览
    Box(modifier = Modifier.fillMaxSize()) {

        val videoUri = verticalPickerInfo.videoUri
        val cropInfo = verticalPickerInfo.cropInfo
        // 创建并记住ExoPlayer实例
        val exoPlayer = remember {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }

        // 当视频URI改变时重新加载视频并重置状态
        LaunchedEffect(videoUri) {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.play()
        }

        // 组件销毁时释放ExoPlayer资源
        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.release()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).also { textureView ->
                        exoPlayer.setVideoTextureView(textureView)
                        textureView.addOnLayoutChangeListener(object :
                            View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                view: View, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                            ) {
                                if (textureView.width > 0 && textureView.height > 0) {
                                    val matrix = computeTextureMatrix(
                                        textureView.width.toFloat(),
                                        textureView.height.toFloat(),
                                        cropInfo
                                    )
                                    textureView.setTransform(matrix)
                                    textureView.removeOnLayoutChangeListener(this)
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Text(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(20.dp),
                fontSize = 20.sp,
                color = Color.White,
                text = "竖屏预览中"
            )
        }

        //横屏预览
        if (!isVertical) {
            val videoUri = horizontalPickerInfo.videoUri
            val cropInfo = horizontalPickerInfo.cropInfo
            // 创建并记住ExoPlayer实例
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(videoUri))
                    prepare()
                    playWhenReady = true
                    repeatMode = Player.REPEAT_MODE_ONE
                }
            }

            // 当视频URI改变时重新加载视频并重置状态
            LaunchedEffect(videoUri) {
                exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                exoPlayer.prepare()
                exoPlayer.play()
            }

            // 组件销毁时释放ExoPlayer资源
            DisposableEffect(Unit) {
                onDispose {
                    exoPlayer.release()
                }
            }


            RotatedBox(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(90f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    AndroidView(
                        factory = { context ->
                            TextureView(context).also { textureView ->
                                exoPlayer.setVideoTextureView(textureView)
                                textureView.addOnLayoutChangeListener(object :
                                    View.OnLayoutChangeListener {
                                    override fun onLayoutChange(
                                        view: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                                    ) {
                                        if (textureView.width > 0 && textureView.height > 0) {
                                            val matrix = computeTextureMatrix(
                                                textureView.width.toFloat(),
                                                textureView.height.toFloat(),
                                                cropInfo
                                            )
                                            textureView.setTransform(matrix)
                                            textureView.removeOnLayoutChangeListener(this)
                                        }
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Text(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(20.dp),
                        fontSize = 20.sp,
                        color = Color.White,
                        text = "横屏预览中"
                    )
                }
            }
        }

        Button(modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(60.dp), onClick = {
            isVertical = !isVertical
        }) {
            Text(text = "切换")
        }
    }
}

/**
 * 横屏/垂屏裁剪预览
 * **/
@Composable
private fun HorizontalVerticalPreview(
    verticalInfo: VideoPickerInfo,
    horizontalInfo: VideoPickerInfo,
    onDismissRequest: () -> Unit,
    onClickLandscapeModeSettings: () -> Unit,
    onClickConfirm: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        containerColor = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(439.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 显示裁剪后的视频预览
            Row(
                modifier = Modifier
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {

                //竖屏
                Box(
                    modifier = Modifier
                        .size(111.dp, 241.dp)
                        .clip(RectangleShape),
                ) {
                    val videoUri = verticalInfo.videoUri
                    val cropInfo = verticalInfo.cropInfo
                    // 创建并记住ExoPlayer实例
                    val exoPlayer = remember {
                        ExoPlayer.Builder(context).build().apply {

                            setMediaItem(MediaItem.fromUri(videoUri))
                            prepare()
                            playWhenReady = true
                            repeatMode = Player.REPEAT_MODE_ONE
                        }
                    }

                    // 当视频URI改变时重新加载视频并重置状态
                    LaunchedEffect(videoUri) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }

                    // 组件销毁时释放ExoPlayer资源
                    DisposableEffect(Unit) {
                        onDispose {
                            exoPlayer.release()
                        }
                    }

                    AndroidView(
                        factory = { context ->
                            TextureView(context).also { textureView ->
                                exoPlayer.setVideoTextureView(textureView)
                                textureView.addOnLayoutChangeListener(object :
                                    View.OnLayoutChangeListener {
                                    override fun onLayoutChange(
                                        view: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                                    ) {
                                        if (textureView.width > 0 && textureView.height > 0) {
                                            val matrix = computeTextureMatrix(
                                                textureView.width.toFloat(),
                                                textureView.height.toFloat(),
                                                cropInfo
                                            )
                                            textureView.setTransform(matrix)
                                            textureView.removeOnLayoutChangeListener(this)
                                        }
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(193f / 89f)
                            .border(2.dp, Color.Green)
                            .align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                //横屏
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                ) {

                    val videoUri = horizontalInfo.videoUri
                    val cropInfo = horizontalInfo.cropInfo

                    // 创建并记住ExoPlayer实例
                    val exoPlayer = remember {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(videoUri))
                            prepare()
                            playWhenReady = true
                            repeatMode = Player.REPEAT_MODE_ONE
                        }
                    }

                    // 当视频URI改变时重新加载视频并重置状态
                    LaunchedEffect(videoUri) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }

                    // 组件销毁时释放ExoPlayer资源
                    DisposableEffect(Unit) {
                        onDispose {
                            exoPlayer.release()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(193.dp, 89.dp)
                            .clip(RectangleShape),
                    ) {
                        AndroidView(
                            factory = { context ->
                                TextureView(context).also { textureView ->
                                    exoPlayer.setVideoTextureView(textureView)
                                    textureView.addOnLayoutChangeListener(object :
                                        View.OnLayoutChangeListener {
                                        override fun onLayoutChange(
                                            view: View,
                                            left: Int,
                                            top: Int,
                                            right: Int,
                                            bottom: Int,
                                            oldLeft: Int,
                                            oldTop: Int,
                                            oldRight: Int,
                                            oldBottom: Int
                                        ) {
                                            if (textureView.width > 0 && textureView.height > 0) {
                                                val matrix = computeTextureMatrix(
                                                    textureView.width.toFloat(),
                                                    textureView.height.toFloat(),
                                                    cropInfo
                                                )
                                                textureView.setTransform(matrix)
                                                textureView.removeOnLayoutChangeListener(this)
                                            }
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            onClickLandscapeModeSettings()
                        },
                        modifier = Modifier.size(193.dp, 32.dp),
                        shape = RoundedCornerShape(33.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF4F4F5)
                        )
                    ) {
                        Text(
                            text = "Landscape Mode Settings",
                            fontSize = 10.sp,
                            color = Color(0xFF2B62FF)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onClickConfirm()
                },
                modifier = Modifier
                    .width(343.dp)
                    .height(66.dp),
                shape = RoundedCornerShape(33.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF4F4F5).copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "Confirm",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(46.dp))
        }
    }
}


/**
 * 计算裁剪参数
 * 将用户选择的裁剪区域转换为视频播放器的缩放和平移参数
 */
private fun computeTextureMatrix(
    viewWidth: Float,
    viewHeight: Float,
    cropInfo: CropInfo
): Matrix {

    val cropRatio = cropInfo.cropRect.width / cropInfo.cropRect.height

    val finalCropWidth: Float = viewWidth
    val finalCropHeight: Float = finalCropWidth / cropRatio

    val finalVideoWidth =
        finalCropWidth / (cropInfo.cropRect.width / cropInfo.videoSize.width)
    val finalVideoHeight =
        finalCropHeight / (cropInfo.cropRect.height / cropInfo.videoSize.height)

    val finalCropLeft =
        cropInfo.cropRect.left / (cropInfo.cropRect.width / finalCropWidth)
    val finalCropTop =
        cropInfo.cropRect.top / (cropInfo.cropRect.height / finalCropHeight)

    val finalScaleX = finalVideoWidth / viewWidth
    val finalScaleY = finalVideoHeight / viewHeight

    // 计算偏移量，使裁剪区域居中显示
    val finalCropCenterX = finalCropLeft + finalCropWidth / 2f
    val finalCropCenterY = finalCropTop + finalCropHeight / 2f

    // 计算视频中心点到裁剪区域中心点的向量，并考虑缩放因素
    val translateX = (finalVideoWidth / 2f - finalCropCenterX)
    val translateY = (finalVideoHeight / 2f - finalCropCenterY)

    val matrix = Matrix()

    // 应用裁剪变换：先缩放，再平移
    val pivotX = viewWidth / 2f
    val pivotY = viewHeight / 2f

    matrix.postScale(
        finalScaleX,
        finalScaleY,
        pivotX,
        pivotY
    )
    matrix.postTranslate(
        translateX,
        translateY
    )

    return matrix
}


// 裁剪比例模式枚举
private enum class AspectRatioMode(val displayName: String, val ratio: Float?) {
    FREE("自由", null),

    //    RATIO_3_4("3:4", 3f / 4f),
//    RATIO_4_3("4:3", 4f / 3f),
//    RATIO_9_16("9:16", 9f / 16f),
//    RATIO_16_9("16:9", 16f / 9f),
    VERTICAL_111_241("111:241", 111f / 241f),
    HORIZONTAL_193_89("193:89", 193f / 89f),
}

/**
 * 可裁剪的视频播放器组件
 *
 * @param videoUri 视频文件的URI
 * @param aspectRatioMode 裁剪比例模式
 * @param onCropInfoChange 回调函数，当裁剪信息发生变化时调用
 */
@Composable
private fun CropVideoPlayer(
    videoUri: Uri,
    aspectRatioMode: AspectRatioMode,
    onCropInfoChange: (CropInfo) -> Unit = {},
) {
    val context = LocalContext.current

    // 视图尺寸状态 - 用于跟踪容器的大小
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 视频尺寸状态 - 从ExoPlayer获取的实际视频分辨率
    var videoSize by remember { mutableStateOf(IntSize.Zero) }

    // 创建并记住ExoPlayer实例
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE

            // 监听视频尺寸变化
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    videoSize = IntSize(size.width, size.height)
                }
            })
        }
    }

    // 当视频URI改变时重新加载视频并重置状态
    LaunchedEffect(videoUri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
        exoPlayer.play()
        videoSize = IntSize.Zero // 重置视频尺寸，等待新视频加载
    }

    // 组件销毁时释放ExoPlayer资源
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                viewSize = coordinates.size
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { playerView ->
                playerView.useController = false
            },
            modifier = Modifier
                .fillMaxSize()
        )

        SelectionOverlay(
            viewSize = viewSize,
            videoSize = videoSize,
            aspectRatioMode = aspectRatioMode,
            onCropInfoChange = onCropInfoChange
        )
    }
}


private fun calculateCropPreviewRect(
    videoSize: IntSize,
    cropRect: Rect,
    videoBounds: Rect
): CropInfo {
    val normalizedLeft = (cropRect.left - videoBounds.left) / videoBounds.width
    val normalizedTop = (cropRect.top - videoBounds.top) / videoBounds.height
    val normalizedWidth = cropRect.width / videoBounds.width
    val normalizedHeight = cropRect.height / videoBounds.height
    val previewRect = Rect(
        normalizedLeft * videoSize.width,
        normalizedTop * videoSize.height,
        (normalizedLeft + normalizedWidth) * videoSize.width,
        (normalizedTop + normalizedHeight) * videoSize.height
    )
    return CropInfo(previewRect, videoSize)
}


// 创建初始裁剪框 - 根据视频实际尺寸和显示区域计算
private fun createInitialCropRect(viewSize: IntSize, videoSize: IntSize, mode: AspectRatioMode): Rect {
    // 如果视频尺寸还未获取，使用视图尺寸
    if (videoSize.width == 0 || videoSize.height == 0) {
        val width = viewSize.width * 0.6f
        val height = viewSize.height * 0.6f
        val left = (viewSize.width - width) / 2f
        val top = (viewSize.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    // 计算视频在视图中的实际显示区域
    val videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
    val viewAspectRatio = viewSize.width.toFloat() / viewSize.height.toFloat()

    val actualVideoWidth: Float
    val actualVideoHeight: Float
    val videoOffsetX: Float
    val videoOffsetY: Float

    if (videoAspectRatio > viewAspectRatio) {
        // 视频更宽，上下留黑边
        actualVideoWidth = viewSize.width.toFloat()
        actualVideoHeight = viewSize.width.toFloat() / videoAspectRatio
        videoOffsetX = 0f
        videoOffsetY = (viewSize.height - actualVideoHeight) / 2f
    } else {
        // 视频更高，左右留黑边
        actualVideoHeight = viewSize.height.toFloat()
        actualVideoWidth = viewSize.height.toFloat() * videoAspectRatio
        videoOffsetX = (viewSize.width - actualVideoWidth) / 2f
        videoOffsetY = 0f
    }

    val ratio = mode.ratio

    return if (ratio != null) {
        // 固定比例模式 - 在视频实际显示区域内创建选框
        val maxWidth = actualVideoWidth
        val maxHeight = actualVideoHeight

        val width: Float
        val height: Float

        if (maxWidth / maxHeight < ratio) {
            // 宽度受限
            width = maxWidth
            height = width / ratio
        } else {
            // 高度受限
            height = maxHeight
            width = height * ratio
        }

        val left = videoOffsetX + (actualVideoWidth - width) / 2f
        val top = videoOffsetY + (actualVideoHeight - height) / 2f

        Rect(left, top, left + width, top + height)
    } else {
        // 自由模式 - 在视频实际显示区域内创建选框
        val width = actualVideoWidth * 0.8f
        val height = actualVideoHeight * 0.8f
        val left = videoOffsetX + (actualVideoWidth - width) / 2f
        val top = videoOffsetY + (actualVideoHeight - height) / 2f

        Rect(left, top, left + width, top + height)
    }
}


@Composable
private fun SelectionOverlay(
    viewSize: IntSize,
    videoSize: IntSize,
    aspectRatioMode: AspectRatioMode,
    onCropInfoChange: (CropInfo) -> Unit
) {
    var activeHandle by remember { mutableStateOf<ResizeHandle?>(null) }
    var isDraggingRect by remember { mutableStateOf(false) }

    // 裁剪矩形状态 - 用户选择的裁剪区域
    var cropRect by remember { mutableStateOf(Rect.Zero) }

    // 计算视频在视图中的实际显示区域
    val videoBounds = remember(viewSize, videoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat())
        } else {
            val videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
            val viewAspectRatio = viewSize.width.toFloat() / viewSize.height.toFloat()

            if (videoAspectRatio > viewAspectRatio) {
                val actualVideoHeight = viewSize.width.toFloat() / videoAspectRatio
                val videoOffsetY = (viewSize.height - actualVideoHeight) / 2f
                Rect(0f, videoOffsetY, viewSize.width.toFloat(), videoOffsetY + actualVideoHeight)
            } else {
                val actualVideoWidth = viewSize.height.toFloat() * videoAspectRatio
                val videoOffsetX = (viewSize.width - actualVideoWidth) / 2f
                Rect(videoOffsetX, 0f, videoOffsetX + actualVideoWidth, viewSize.height.toFloat())
            }
        }
    }

    // 当裁剪信息变化时，通知外部
    LaunchedEffect(cropRect, videoBounds, videoSize) {
        if (cropRect != Rect.Zero && videoSize.width > 0 && videoSize.height > 0) {
            val previewRect = calculateCropPreviewRect(
                videoSize,
                cropRect,
                videoBounds
            )
            onCropInfoChange(previewRect)
        }
    }

    // 初始化选框 - 考虑视频和视图的尺寸
    LaunchedEffect(videoSize, aspectRatioMode) {
        if (viewSize.width > 0 && viewSize.height > 0 && videoSize.width > 0 && videoSize.height > 0) {
            cropRect = createInitialCropRect(viewSize, videoSize, aspectRatioMode)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(videoSize, aspectRatioMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val handle = getResizeHandle(offset, cropRect)
                            if (handle != null) {
                                activeHandle = handle
                                isDraggingRect = false
                            } else if (cropRect.contains(offset)) {
                                isDraggingRect = true
                                activeHandle = null
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()

                            when {
                                activeHandle != null -> {
                                    val newRect = resizeCropRect(
                                        cropRect,
                                        activeHandle!!,
                                        dragAmount,
                                        aspectRatioMode,
                                        videoBounds
                                    )
                                    cropRect = newRect
                                }

                                isDraggingRect -> {
                                    // 确保coerceIn的参数顺序正确，避免IllegalArgumentException
                                    val maxX = (videoBounds.right - cropRect.width).coerceAtLeast(
                                        videoBounds.left
                                    )
                                    val maxY = (videoBounds.bottom - cropRect.height).coerceAtLeast(
                                        videoBounds.top
                                    )

                                    val newX = (cropRect.left + dragAmount.x).coerceIn(
                                        videoBounds.left,
                                        maxX
                                    )
                                    val newY = (cropRect.top + dragAmount.y).coerceIn(
                                        videoBounds.top,
                                        maxY
                                    )
                                    val newRect = Rect(
                                        newX, newY,
                                        newX + cropRect.width,
                                        newY + cropRect.height
                                    )

                                    cropRect = newRect
                                }
                            }
                        },
                        onDragEnd = {
                            isDraggingRect = false
                            activeHandle = null
                        },
                        onDragCancel = {
                            isDraggingRect = false
                            activeHandle = null
                        }
                    )
                }
        ) {
            // 绘制暗色蒙层
            val path = Path().apply {
                // 添加整个画布区域
                addRect(Rect(0f, 0f, size.width, size.height))
                // 添加裁剪区域
                addRect(cropRect, Path.Direction.Clockwise)
            }

            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.8f),
                style = Fill,
            )

            // 绘制选框边框
            drawRect(
                color = Color.White,
                topLeft = cropRect.topLeft,
                size = Size(cropRect.width, cropRect.height),
                style = Stroke(width = 4f)
            )

            // 绘制四个角的控制点 - 改为角形标记
            val cornerLength = 60f
            val cornerWidth = 10f
            // 左上角
            drawLine(
                color = Color.White,
                start = Offset(cropRect.left, cropRect.top + cornerLength),
                end = Offset(cropRect.left, cropRect.top - 5),
                strokeWidth = cornerWidth
            )
            drawLine(
                color = Color.White,
                start = Offset(cropRect.left, cropRect.top),
                end = Offset(cropRect.left + cornerLength, cropRect.top),
                strokeWidth = cornerWidth
            )

            // 右上角
            drawLine(
                color = Color.White,
                start = Offset(cropRect.right - cornerLength, cropRect.top),
                end = Offset(cropRect.right, cropRect.top),
                strokeWidth = cornerWidth
            )
            drawLine(
                color = Color.White,
                start = Offset(cropRect.right, cropRect.top - 5),
                end = Offset(cropRect.right, cropRect.top + cornerLength),
                strokeWidth = cornerWidth
            )

            // 左下角
            drawLine(
                color = Color.White,
                start = Offset(cropRect.left, cropRect.bottom - cornerLength),
                end = Offset(cropRect.left, cropRect.bottom + 5),
                strokeWidth = cornerWidth
            )
            drawLine(
                color = Color.White,
                start = Offset(cropRect.left, cropRect.bottom),
                end = Offset(cropRect.left + cornerLength, cropRect.bottom),
                strokeWidth = cornerWidth
            )

            // 右下角
            drawLine(
                color = Color.White,
                start = Offset(cropRect.right - cornerLength, cropRect.bottom),
                end = Offset(cropRect.right, cropRect.bottom),
                strokeWidth = cornerWidth
            )
            drawLine(
                color = Color.White,
                start = Offset(cropRect.right, cropRect.bottom + 5),
                end = Offset(cropRect.right, cropRect.bottom - cornerLength),
                strokeWidth = cornerWidth
            )

        }
    }
}

private enum class ResizeHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

private fun getResizeHandle(offset: Offset, rect: Rect): ResizeHandle? {
    val touchRadius = 150f

    return when {
        (offset - rect.topLeft).getDistance() < touchRadius -> ResizeHandle.TOP_LEFT
        (offset - Offset(
            rect.right,
            rect.top
        )).getDistance() < touchRadius -> ResizeHandle.TOP_RIGHT

        (offset - Offset(
            rect.left,
            rect.bottom
        )).getDistance() < touchRadius -> ResizeHandle.BOTTOM_LEFT

        (offset - rect.bottomRight).getDistance() < touchRadius -> ResizeHandle.BOTTOM_RIGHT
        else -> null
    }
}

private fun resizeCropRect(
    currentRect: Rect,
    handle: ResizeHandle,
    dragOffset: Offset,
    aspectRatioMode: AspectRatioMode,
    videoBounds: Rect
): Rect {
    val minimumSize = 200f // 减小最小尺寸以避免约束问题
    val aspectRatio = aspectRatioMode.ratio

    // ===== 自由模式 =====
    if (aspectRatio == null) {
        return when (handle) {
            ResizeHandle.TOP_LEFT -> {
                val maxLeft = (currentRect.right - minimumSize).coerceAtLeast(videoBounds.left)
                val maxTop = (currentRect.bottom - minimumSize).coerceAtLeast(videoBounds.top)

                Rect(
                    left = (currentRect.left + dragOffset.x).coerceIn(videoBounds.left, maxLeft),
                    top = (currentRect.top + dragOffset.y).coerceIn(videoBounds.top, maxTop),
                    right = currentRect.right,
                    bottom = currentRect.bottom
                )
            }

            ResizeHandle.TOP_RIGHT -> {
                val maxTop = (currentRect.bottom - minimumSize).coerceAtLeast(videoBounds.top)
                val minRight = (currentRect.left + minimumSize).coerceAtMost(videoBounds.right)

                Rect(
                    left = currentRect.left,
                    top = (currentRect.top + dragOffset.y).coerceIn(videoBounds.top, maxTop),
                    right = (currentRect.right + dragOffset.x).coerceIn(
                        minRight,
                        videoBounds.right
                    ),
                    bottom = currentRect.bottom
                )
            }

            ResizeHandle.BOTTOM_LEFT -> {
                val maxLeft = (currentRect.right - minimumSize).coerceAtLeast(videoBounds.left)
                val minBottom = (currentRect.top + minimumSize).coerceAtMost(videoBounds.bottom)

                Rect(
                    left = (currentRect.left + dragOffset.x).coerceIn(videoBounds.left, maxLeft),
                    top = currentRect.top,
                    right = currentRect.right,
                    bottom = (currentRect.bottom + dragOffset.y).coerceIn(
                        minBottom,
                        videoBounds.bottom
                    )
                )
            }

            ResizeHandle.BOTTOM_RIGHT -> {
                val minRight = (currentRect.left + minimumSize).coerceAtMost(videoBounds.right)
                val minBottom = (currentRect.top + minimumSize).coerceAtMost(videoBounds.bottom)

                Rect(
                    left = currentRect.left,
                    top = currentRect.top,
                    right = (currentRect.right + dragOffset.x).coerceIn(
                        minRight,
                        videoBounds.right
                    ),
                    bottom = (currentRect.bottom + dragOffset.y).coerceIn(
                        minBottom,
                        videoBounds.bottom
                    )
                )
            }
        }
    }

    // ===== 固定比例模式 =====
    return when (handle) {
        ResizeHandle.TOP_LEFT -> {
            val fixedRight = currentRect.right
            val fixedBottom = currentRect.bottom

            // 确保范围正确
            val maxLeft = (fixedRight - minimumSize).coerceAtLeast(videoBounds.left)
            val maxTop = (fixedBottom - minimumSize).coerceAtLeast(videoBounds.top)

            var newLeft = (currentRect.left + dragOffset.x).coerceIn(videoBounds.left, maxLeft)
            var newTop = (currentRect.top + dragOffset.y).coerceIn(videoBounds.top, maxTop)

            val width = fixedRight - newLeft
            val height = width / aspectRatio

            if (fixedBottom - height < videoBounds.top) {
                val availableHeight = fixedBottom - videoBounds.top
                val fixedWidth = availableHeight * aspectRatio
                newLeft = fixedRight - fixedWidth
                newTop = videoBounds.top
            } else {
                newTop = fixedBottom - height
            }

            Rect(newLeft, newTop, fixedRight, fixedBottom)
        }

        ResizeHandle.TOP_RIGHT -> {
            val fixedLeft = currentRect.left
            val fixedBottom = currentRect.bottom

            // 确保范围正确
            val maxTop = (fixedBottom - minimumSize).coerceAtLeast(videoBounds.top)
            val minRight = (fixedLeft + minimumSize).coerceAtMost(videoBounds.right)

            var newRight = (currentRect.right + dragOffset.x).coerceIn(minRight, videoBounds.right)
            var newTop = (currentRect.top + dragOffset.y).coerceIn(videoBounds.top, maxTop)

            val width = newRight - fixedLeft
            val height = width / aspectRatio

            if (fixedBottom - height < videoBounds.top) {
                val availableHeight = fixedBottom - videoBounds.top
                val fixedWidth = availableHeight * aspectRatio
                newRight = fixedLeft + fixedWidth
                newTop = videoBounds.top
            } else {
                newTop = fixedBottom - height
            }

            Rect(fixedLeft, newTop, newRight, fixedBottom)
        }

        ResizeHandle.BOTTOM_LEFT -> {
            val fixedRight = currentRect.right
            val fixedTop = currentRect.top

            // 确保范围正确
            val maxLeft = (fixedRight - minimumSize).coerceAtLeast(videoBounds.left)
            val minBottom = (fixedTop + minimumSize).coerceAtMost(videoBounds.bottom)

            var newLeft = (currentRect.left + dragOffset.x).coerceIn(videoBounds.left, maxLeft)
            var newBottom =
                (currentRect.bottom + dragOffset.y).coerceIn(minBottom, videoBounds.bottom)

            val width = fixedRight - newLeft
            val height = width / aspectRatio

            if (fixedTop + height > videoBounds.bottom) {
                val availableHeight = videoBounds.bottom - fixedTop
                val fixedWidth = availableHeight * aspectRatio
                newLeft = fixedRight - fixedWidth
                newBottom = videoBounds.bottom
            } else {
                newBottom = fixedTop + height
            }

            Rect(newLeft, fixedTop, fixedRight, newBottom)
        }

        ResizeHandle.BOTTOM_RIGHT -> {
            val fixedLeft = currentRect.left
            val fixedTop = currentRect.top

            // 确保范围正确
            val minRight = (fixedLeft + minimumSize).coerceAtMost(videoBounds.right)
            val minBottom = (fixedTop + minimumSize).coerceAtMost(videoBounds.bottom)

            var newRight = (currentRect.right + dragOffset.x).coerceIn(minRight, videoBounds.right)
            var newBottom =
                (currentRect.bottom + dragOffset.y).coerceIn(minBottom, videoBounds.bottom)

            val width = newRight - fixedLeft
            val height = width / aspectRatio

            if (fixedTop + height > videoBounds.bottom) {
                val availableHeight = videoBounds.bottom - fixedTop
                val fixedWidth = availableHeight * aspectRatio
                newRight = fixedLeft + fixedWidth
                newBottom = videoBounds.bottom
            } else {
                newBottom = fixedTop + height
            }

            Rect(fixedLeft, fixedTop, newRight, newBottom)
        }
    }
}