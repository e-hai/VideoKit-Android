@file:OptIn(ExperimentalMaterial3Api::class)

package com.kit.video.sample.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MediaType {
    VIDEO, IMAGE
}

private data class MediaPickerInfo(
    val uri: Uri,
    val cropInfo: CropInfo,
    val mediaType: MediaType
)

private data class CropInfo(
    val cropRect: Rect,
    val mediaSize: IntSize
)

@Composable
fun VideoEditorScreen() {
    var openFullScreen by remember { mutableStateOf(false) }
    var verticalPickerInfo by remember { mutableStateOf<MediaPickerInfo?>(null) }
    var horizontalPickerInfo by remember { mutableStateOf<MediaPickerInfo?>(null) }

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

private fun checkMediaSize(context: Context, uri: Uri): Pair<Boolean, MediaType> {
    val type = context.contentResolver.getType(uri)
    if (type?.startsWith("image/") == true) {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth > 5000 || options.outHeight > 5000) {
                Log.d("VideoEditorScreen", "图片尺寸超过限制(5000*5000)，请重新选择")
                false to MediaType.IMAGE
            } else {
                true to MediaType.IMAGE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false to MediaType.IMAGE
        }
    } else {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width > 1280 || height > 1280) {
                Log.d("VideoEditorScreen", "视频尺寸超过限制(1280*1280)，请重新选择")
                false to MediaType.VIDEO
            } else {
                true to MediaType.VIDEO
            }
        } catch (e: Exception) {
            Log.d("VideoEditorScreen", "读取文件信息失败")
            e.printStackTrace()
            false to MediaType.VIDEO
        } finally {
            retriever.release()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPickerScreen(onClickFullScreen: (verticalPickerInfo: MediaPickerInfo, horizontalPickerInfo: MediaPickerInfo) -> Unit) {
    val context = LocalContext.current
    var verticalUri by remember { mutableStateOf<Uri?>(null) }
    var verticalMediaType by remember { mutableStateOf(MediaType.VIDEO) }
    var horizontalUri by remember { mutableStateOf<Uri?>(null) }
    var horizontalMediaType by remember { mutableStateOf(MediaType.VIDEO) }

    val verticalPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (isValid, type) = checkMediaSize(context, uri)
            if (isValid) {
                verticalUri = uri
                verticalMediaType = type
            }
        }
    }
    val horizontalPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (isValid, type) = checkMediaSize(context, uri)
            if (isValid) {
                horizontalUri = uri
                horizontalMediaType = type
            }
        }
    }

    var verticalPickerInfo by remember { mutableStateOf<MediaPickerInfo?>(null) }
    var horizontalPickerInfo by remember { mutableStateOf<MediaPickerInfo?>(null) }
    var selectVertical by remember { mutableStateOf(true) }
    var openSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (verticalUri == null) {
            Button(
                onClick = { verticalPickerLauncher.launch("*/*") },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("从相册选择视频或图片")
            }
            Text(
                text = "提示：选择媒体后可以调整选框查看局部",
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
                    val uri = verticalUri ?: return@Box
                    VerticalCropMediaPlayer(
                        mediaUri = uri,
                        mediaType = verticalMediaType,
                        onSelectOtherMedia = { verticalPickerLauncher.launch("*/*") },
                        onClickOpenSheet = { openSheet = true },
                        onCropInfoChange = {
                            verticalPickerInfo = MediaPickerInfo(uri, it, verticalMediaType)
                        }
                    )
                } else {
                    val uri = horizontalUri ?: return@Box
                    HorizontalCropMediaPlayer(
                        mediaUri = uri,
                        mediaType = horizontalMediaType,
                        onSelectOtherMedia = { horizontalPickerLauncher.launch("*/*") },
                        onClickOpenSheet = { openSheet = true },
                        onCropInfoChange = {
                            horizontalPickerInfo = MediaPickerInfo(uri, it, horizontalMediaType)
                        }
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = openSheet,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val verticalInfo = verticalPickerInfo ?: return@AnimatedVisibility
        val horizontalInfo = horizontalPickerInfo ?: verticalInfo.copy()

        HorizontalVerticalPreview(
            verticalInfo,
            horizontalInfo,
            onDismissRequest = { openSheet = false },
            onClickLandscapeModeSettings = {
                openSheet = false
                selectVertical = false
                horizontalPickerLauncher.launch("*/*")
            },
            onClickConfirm = {
                onClickFullScreen(verticalInfo, horizontalInfo)
            }
        )
    }
}

@Composable
private fun VerticalCropMediaPlayer(
    mediaUri: Uri,
    mediaType: MediaType,
    onSelectOtherMedia: () -> Unit,
    onClickOpenSheet: () -> Unit,
    onCropInfoChange: (CropInfo) -> Unit = {}
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (mediaType == MediaType.VIDEO) {
                CropVideoPlayer(mediaUri, AspectRatioMode.VERTICAL_111_241, onCropInfoChange)
            } else {
                CropImageView(mediaUri, AspectRatioMode.VERTICAL_111_241, onCropInfoChange)
            }
        }
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Button(
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { onSelectOtherMedia() }) {
                Text("选择其他")
            }
            FloatingActionButton(
                onClick = { onClickOpenSheet() },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Check, contentDescription = "确认")
            }
        }
    }
}

@Composable
private fun HorizontalCropMediaPlayer(
    mediaUri: Uri,
    mediaType: MediaType,
    onSelectOtherMedia: () -> Unit,
    onClickOpenSheet: () -> Unit,
    onCropInfoChange: (CropInfo) -> Unit = {}
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (mediaType == MediaType.VIDEO) {
                CropVideoPlayer(mediaUri, AspectRatioMode.HORIZONTAL_193_89, onCropInfoChange)
            } else {
                CropImageView(mediaUri, AspectRatioMode.HORIZONTAL_193_89, onCropInfoChange)
            }
        }
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Button(
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { onSelectOtherMedia() }) {
                Text("选择其他")
            }
            FloatingActionButton(
                onClick = { onClickOpenSheet() },
                modifier = Modifier.align(Alignment.CenterEnd)
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
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val swappedConstraints = constraints.copy(
            maxWidth = constraints.maxHeight,
            maxHeight = constraints.maxWidth,
            minWidth = if (constraints.hasFixedHeight) constraints.maxHeight else 0,
            minHeight = if (constraints.hasFixedWidth) constraints.maxWidth else 0
        )
        val placeable = measurables.first().measure(swappedConstraints)
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = (placeable.height - placeable.width) / 2,
                y = -(placeable.height - placeable.width) / 2,
                zIndex = 0f
            )
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullScreenPreview(
    verticalPickerInfo: MediaPickerInfo,
    horizontalPickerInfo: MediaPickerInfo
) {
    var isVertical by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        val verticalUri = verticalPickerInfo.uri
        val verticalCropInfo = verticalPickerInfo.cropInfo

        // 竖屏预览
        Box(modifier = Modifier.fillMaxSize()) {
            if (verticalPickerInfo.mediaType == MediaType.VIDEO) {
                val exoPlayer = remember {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(verticalUri))
                        prepare()
                        playWhenReady = true
                        repeatMode = Player.REPEAT_MODE_ONE
                    }
                }
                LaunchedEffect(verticalUri) {
                    exoPlayer.setMediaItem(MediaItem.fromUri(verticalUri))
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
                DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

                AndroidView(
                    factory = { context ->
                        TextureView(context).also { textureView ->
                            exoPlayer.setVideoTextureView(textureView)
                            textureView.addOnLayoutChangeListener(object :
                                View.OnLayoutChangeListener {
                                override fun onLayoutChange(
                                    view: View,
                                    l: Int,
                                    t: Int,
                                    r: Int,
                                    b: Int,
                                    ol: Int,
                                    ot: Int,
                                    or: Int,
                                    ob: Int
                                ) {
                                    if (textureView.width > 0 && textureView.height > 0) {
                                        val matrix = computeTextureMatrix(
                                            textureView.width.toFloat(),
                                            textureView.height.toFloat(),
                                            verticalCropInfo
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
            } else {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.MATRIX
                            setImageURI(verticalUri)
                            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                                override fun onLayoutChange(
                                    view: View,
                                    l: Int,
                                    t: Int,
                                    r: Int,
                                    b: Int,
                                    ol: Int,
                                    ot: Int,
                                    or: Int,
                                    ob: Int
                                ) {
                                    if (width > 0 && height > 0) {
                                        val matrix = computeTextureMatrix(
                                            width.toFloat(),
                                            height.toFloat(),
                                            verticalCropInfo
                                        )
                                        imageMatrix = matrix
                                        removeOnLayoutChangeListener(this)
                                    }
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(20.dp),
                fontSize = 20.sp,
                color = Color.White,
                text = "竖屏预览中"
            )
        }

        // 横屏预览
        if (!isVertical) {
            val horizontalUri = horizontalPickerInfo.uri
            val horizontalCropInfo = horizontalPickerInfo.cropInfo

            RotatedBox(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Yellow)
                    .rotate(90f)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Green)) {
                    if (horizontalPickerInfo.mediaType == MediaType.VIDEO) {
                        val exoPlayer = remember {
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(horizontalUri))
                                prepare()
                                playWhenReady = true
                                repeatMode = Player.REPEAT_MODE_ONE
                            }
                        }
                        LaunchedEffect(horizontalUri) {
                            exoPlayer.setMediaItem(MediaItem.fromUri(horizontalUri))
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                        DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

                        AndroidView(
                            factory = { context ->
                                TextureView(context).also { textureView ->
                                    exoPlayer.setVideoTextureView(textureView)
                                    textureView.addOnLayoutChangeListener(object :
                                        View.OnLayoutChangeListener {
                                        override fun onLayoutChange(
                                            view: View,
                                            l: Int,
                                            t: Int,
                                            r: Int,
                                            b: Int,
                                            ol: Int,
                                            ot: Int,
                                            or: Int,
                                            ob: Int
                                        ) {
                                            if (textureView.width > 0 && textureView.height > 0) {
                                                val matrix = computeTextureMatrix(
                                                    textureView.width.toFloat(),
                                                    textureView.height.toFloat(),
                                                    horizontalCropInfo
                                                )
                                                textureView.setTransform(matrix)
                                                textureView.removeOnLayoutChangeListener(this)
                                            }
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize().background(Color.Red)
                        )
                    } else {
                        AsyncImage(
                            model = horizontalUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
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

        Button(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(60.dp),
            onClick = { isVertical = !isVertical }
        ) {
            Text(text = "切换")
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.HorizontalVerticalPreview(
    verticalInfo: MediaPickerInfo,
    horizontalInfo: MediaPickerInfo,
    onDismissRequest: () -> Unit,
    onClickLandscapeModeSettings: () -> Unit,
    onClickConfirm: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { onDismissRequest() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .animateEnterExit(
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it })
                .height(439.dp)
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures {} },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // 竖屏
                Box(
                    modifier = Modifier
                        .size(111.dp, 241.dp)
                        .border(1.dp, Color.White, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    val uri = verticalInfo.uri
                    val cropInfo = verticalInfo.cropInfo
                    if (verticalInfo.mediaType == MediaType.VIDEO) {
                        val exoPlayer = rememberExoPlayer(context, uri)
                        AndroidView(factory = { context ->
                            TextureView(context).also { textureView ->
                                exoPlayer.setVideoTextureView(textureView)
                                textureView.addOnLayoutChangeListener(object :
                                    View.OnLayoutChangeListener {
                                    override fun onLayoutChange(
                                        v: View,
                                        l: Int,
                                        t: Int,
                                        r: Int,
                                        b: Int,
                                        ol: Int,
                                        ot: Int,
                                        or: Int,
                                        ob: Int
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
                        }, modifier = Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                // 横屏
                Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                    val uri = horizontalInfo.uri
                    val cropInfo = horizontalInfo.cropInfo
                    Box(
                        modifier = Modifier
                            .size(193.dp, 89.dp)
                            .border(1.dp, Color.White, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        if (horizontalInfo.mediaType == MediaType.VIDEO) {
                            val exoPlayer = rememberExoPlayer(context, uri)
                            AndroidView(factory = { context ->
                                TextureView(context).also { textureView ->
                                    exoPlayer.setVideoTextureView(textureView)
                                    textureView.addOnLayoutChangeListener(object :
                                        View.OnLayoutChangeListener {
                                        override fun onLayoutChange(
                                            v: View,
                                            l: Int,
                                            t: Int,
                                            r: Int,
                                            b: Int,
                                            ol: Int,
                                            ot: Int,
                                            or: Int,
                                            ob: Int
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
                            }, modifier = Modifier.fillMaxSize())
                        } else {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onClickLandscapeModeSettings() },
                        modifier = Modifier.size(193.dp, 32.dp),
                        shape = RoundedCornerShape(33.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4F4F5))
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
                onClick = { onClickConfirm() },
                modifier = Modifier
                    .width(343.dp)
                    .height(66.dp),
                shape = RoundedCornerShape(33.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4F4F5).copy(alpha = 0.5f))
            ) {
                Text(text = "Confirm", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(46.dp))
        }
    }
}

@Composable
private fun rememberExoPlayer(context: Context, uri: Uri): ExoPlayer {
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(uri) {
        onDispose { exoPlayer.release() }
    }
    return exoPlayer
}

private fun computeTextureMatrix(
    viewWidth: Float,
    viewHeight: Float,
    cropInfo: CropInfo
): Matrix {
    val cropRatio = cropInfo.cropRect.width / cropInfo.cropRect.height
    val finalCropWidth: Float = viewWidth
    val finalCropHeight: Float = finalCropWidth / cropRatio

    val finalVideoWidth = finalCropWidth / (cropInfo.cropRect.width / cropInfo.mediaSize.width)
    val finalVideoHeight = finalCropHeight / (cropInfo.cropRect.height / cropInfo.mediaSize.height)

    val finalCropLeft = cropInfo.cropRect.left / (cropInfo.cropRect.width / finalCropWidth)
    val finalCropTop = cropInfo.cropRect.top / (cropInfo.cropRect.height / finalCropHeight)

    val finalScaleX = finalVideoWidth / viewWidth
    val finalScaleY = finalVideoHeight / viewHeight

    val finalCropCenterX = finalCropLeft + finalCropWidth / 2f
    val finalCropCenterY = finalCropTop + finalCropHeight / 2f

    val translateX = (finalVideoWidth / 2f - finalCropCenterX)
    val translateY = (finalVideoHeight / 2f - finalCropCenterY)
    
    // 限制平移边界，确保视频内容不会被移出可视区域
    val maxTranslateX = (finalVideoWidth - viewWidth) / 2f
    val maxTranslateY = (finalVideoHeight - viewHeight) / 2f
    val clampedTranslateX = translateX.coerceIn(-maxTranslateX, maxTranslateX)
    val clampedTranslateY = translateY.coerceIn(-maxTranslateY, maxTranslateY)

    val matrix = Matrix()
    val pivotX = viewWidth / 2f
    val pivotY = viewHeight / 2f

    matrix.postScale(finalScaleX, finalScaleY, pivotX, pivotY)
    matrix.postTranslate(clampedTranslateX, clampedTranslateY)
    return matrix
}

private enum class AspectRatioMode(val displayName: String, val ratio: Float?) {
    FREE("自由", null),
    VERTICAL_111_241("111:241", 111f / 241f),
    HORIZONTAL_193_89("193:89", 193f / 89f),
}

@Composable
private fun CropVideoPlayer(
    videoUri: Uri,
    aspectRatioMode: AspectRatioMode,
    onCropInfoChange: (CropInfo) -> Unit = {},
) {
    val context = LocalContext.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var videoSize by remember { mutableStateOf(IntSize.Zero) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    videoSize = IntSize(size.width, size.height)
                }
            })
        }
    }

    LaunchedEffect(videoUri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
        exoPlayer.play()
        videoSize = IntSize.Zero
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> viewSize = coordinates.size }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { playerView -> playerView.useController = false },
            modifier = Modifier.fillMaxSize()
        )
        SelectionOverlay(viewSize, videoSize, aspectRatioMode, onCropInfoChange)
    }
}

@Composable
private fun CropImageView(
    imageUri: Uri,
    aspectRatioMode: AspectRatioMode,
    onCropInfoChange: (CropInfo) -> Unit = {},
) {
    val context = LocalContext.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use {
                    val bitmap = BitmapFactory.decodeStream(it)
                    if (bitmap != null) {
                        imageSize = IntSize(bitmap.width, bitmap.height)
                        imageBitmap = bitmap.asImageBitmap()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> viewSize = coordinates.size }
    ) {
        imageBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        SelectionOverlay(viewSize, imageSize, aspectRatioMode, onCropInfoChange)
    }
}

private fun calculateCropPreviewRect(
    mediaSize: IntSize,
    cropRect: Rect,
    mediaBounds: Rect
): CropInfo {
    val normalizedLeft = (cropRect.left - mediaBounds.left) / mediaBounds.width
    val normalizedTop = (cropRect.top - mediaBounds.top) / mediaBounds.height
    val normalizedWidth = cropRect.width / mediaBounds.width
    val normalizedHeight = cropRect.height / mediaBounds.height
    val previewRect = Rect(
        normalizedLeft * mediaSize.width,
        normalizedTop * mediaSize.height,
        (normalizedLeft + normalizedWidth) * mediaSize.width,
        (normalizedTop + normalizedHeight) * mediaSize.height
    )
    return CropInfo(previewRect, mediaSize)
}

private fun createInitialCropRect(
    viewSize: IntSize,
    mediaSize: IntSize,
    mode: AspectRatioMode
): Rect {
    if (mediaSize.width == 0 || mediaSize.height == 0) {
        val width = viewSize.width * 0.6f
        val height = viewSize.height * 0.6f
        val left = (viewSize.width - width) / 2f
        val top = (viewSize.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    val mediaAspectRatio = mediaSize.width.toFloat() / mediaSize.height.toFloat()
    val viewAspectRatio = viewSize.width.toFloat() / viewSize.height.toFloat()

    val actualMediaWidth: Float
    val actualMediaHeight: Float
    val mediaOffsetX: Float
    val mediaOffsetY: Float

    if (mediaAspectRatio > viewAspectRatio) {
        actualMediaWidth = viewSize.width.toFloat()
        actualMediaHeight = viewSize.width.toFloat() / mediaAspectRatio
        mediaOffsetX = 0f
        mediaOffsetY = (viewSize.height - actualMediaHeight) / 2f
    } else {
        actualMediaHeight = viewSize.height.toFloat()
        actualMediaWidth = viewSize.height.toFloat() * mediaAspectRatio
        mediaOffsetX = (viewSize.width - actualMediaWidth) / 2f
        mediaOffsetY = 0f
    }

    val ratio = mode.ratio
    return if (ratio != null) {
        val maxWidth = actualMediaWidth
        val maxHeight = actualMediaHeight
        val width: Float
        val height: Float

        if (maxWidth / maxHeight < ratio) {
            width = maxWidth
            height = width / ratio
        } else {
            height = maxHeight
            width = height * ratio
        }

        val left = mediaOffsetX + (actualMediaWidth - width) / 2f
        val top = mediaOffsetY + (actualMediaHeight - height) / 2f
        Rect(left, top, left + width, top + height)
    } else {
        val width = actualMediaWidth * 0.8f
        val height = actualMediaHeight * 0.8f
        val left = mediaOffsetX + (actualMediaWidth - width) / 2f
        val top = mediaOffsetY + (actualMediaHeight - height) / 2f
        Rect(left, top, left + width, top + height)
    }
}

@Composable
private fun SelectionOverlay(
    viewSize: IntSize,
    mediaSize: IntSize,
    aspectRatioMode: AspectRatioMode,
    onCropInfoChange: (CropInfo) -> Unit
) {
    var activeHandle by remember { mutableStateOf<ResizeHandle?>(null) }
    var isDraggingRect by remember { mutableStateOf(false) }

    // 裁剪矩形状态 - 用户选择的裁剪区域
    var cropRect by remember { mutableStateOf(Rect.Zero) }

    // 计算视频在视图中的实际显示区域
    val mediaBounds = remember(viewSize, mediaSize) {
        if (mediaSize.width == 0 || mediaSize.height == 0) {
            Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat())
        } else {
            val videoAspectRatio = mediaSize.width.toFloat() / mediaSize.height.toFloat()
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
    LaunchedEffect(cropRect, mediaBounds, mediaSize) {
        if (cropRect != Rect.Zero && mediaSize.width > 0 && mediaSize.height > 0) {
            val previewRect = calculateCropPreviewRect(
                mediaSize,
                cropRect,
                mediaBounds
            )
            onCropInfoChange(previewRect)
        }
    }

    // 初始化选框 - 考虑视频和视图的尺寸
    LaunchedEffect(mediaSize, aspectRatioMode) {
        if (viewSize.width > 0 && viewSize.height > 0 && mediaSize.width > 0 && mediaSize.height > 0) {
            cropRect = createInitialCropRect(viewSize, mediaSize, aspectRatioMode)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mediaSize, aspectRatioMode) {
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
                                        mediaBounds
                                    )
                                    cropRect = newRect
                                }

                                isDraggingRect -> {
                                    // 确保coerceIn的参数顺序正确，避免IllegalArgumentException
                                    val maxX = (mediaBounds.right - cropRect.width).coerceAtLeast(
                                        mediaBounds.left
                                    )
                                    val maxY = (mediaBounds.bottom - cropRect.height).coerceAtLeast(
                                        mediaBounds.top
                                    )

                                    val newX = (cropRect.left + dragAmount.x).coerceIn(
                                        mediaBounds.left,
                                        maxX
                                    )
                                    val newY = (cropRect.top + dragAmount.y).coerceIn(
                                        mediaBounds.top,
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
                // 添加裁剪区域（带圆角）- 使用相反的方向来"挖空"这个区域
                addRoundRect(
                    RoundRect(cropRect, CornerRadius(100f, 100f)),
                    Path.Direction.Clockwise
                )
            }

            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.8f),
                style = Fill,
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
    val minimumSize = 300f // 减小最小尺寸以避免约束问题
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
