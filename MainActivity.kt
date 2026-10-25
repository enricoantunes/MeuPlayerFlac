package com.enrico.flacplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        setContent {
            MaterialTheme {
                IPodTouch1GApp(this, player)
            }
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

@Composable
fun IPodTouch1GApp(activity: ComponentActivity, player: ExoPlayer) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(rootDir) }
    var fileList by remember { mutableStateOf(listOf<File>()) }

    // Alterna entre a Lista de Pastas (false) e a tela "Now Playing" do iPod (true)
    var showNowPlaying by remember { mutableStateOf(false) }

    var currentTrackName by remember { mutableStateOf("Sem Reprodução") }
    var currentArtistAlbum by remember { mutableStateOf("Enrico iPod") }
    var currentFilePath by remember { mutableStateOf<String?>(null) }
    var albumArt by remember { mutableStateOf<Bitmap?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(1L) }
    var currentPosition by remember { mutableStateOf(0L) }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(permission)
    }

    // Botão Voltar do celular: se estiver no Now Playing volta pra lista; se estiver numa subpasta, sobe uma pasta
    BackHandler(enabled = showNowPlaying || (currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null)) {
        if (showNowPlaying) {
            showNowPlaying = false
        } else {
            currentDir.parentFile?.let { currentDir = it }
        }
    }

    LaunchedEffect(currentDir, hasPermission) {
        if (hasPermission) {
            val files = currentDir.listFiles()?.filter { file ->
                !file.isHidden && (file.isDirectory || file.extension.lowercase() in listOf("flac", "mp3", "wav", "m4a", "ogg"))
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            fileList = files
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            duration = player.duration.coerceAtLeast(1L)
            currentPosition = player.currentPosition
            progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            delay(500)
        }
    }

    // Extrai capa embutida no FLAC ou procura cover.jpg / folder.jpg na pasta
    LaunchedEffect(currentFilePath) {
        val path = currentFilePath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            var bmp: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val artBytes = retriever.embeddedPicture
                retriever.release()
                if (artBytes != null) {
                    bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                }
            } catch (_: Exception) {}

            if (bmp == null) {
                try {
                    val parent = File(path).parentFile
                    val coverFile = parent?.listFiles()?.firstOrNull {
                        it.extension.lowercase() in listOf("jpg", "jpeg", "png")
                    }
                    if (coverFile != null) {
                        bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                    }
                } catch (_: Exception) {}
            }
            albumArt = bmp
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.title?.let { currentTrackName = it.toString() }
                mediaItem?.mediaMetadata?.artist?.let { currentArtistAlbum = it.toString() }
                mediaItem?.localConfiguration?.uri?.path?.let { currentFilePath = it }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!showNowPlaying) {
            // ==========================================
            // TELA 1: NAVEGADOR DE PASTAS ESTILO iPOD 1G
            // ==========================================
            IPodSilverTopBar(
                title = if (currentDir.absolutePath == rootDir.absolutePath) "Pastas" else currentDir.name,
                showBack = currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null,
                onBackClick = { currentDir.parentFile?.let { currentDir = it } },
                showNowPlayingButton = currentFilePath != null,
                onNowPlayingClick = { showNowPlaying = true }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                items(fileList) { file ->
                    val isThisTrackPlaying = currentFilePath == file.absolutePath
                    val rowBg = if (isThisTrackPlaying) {
                        Brush.verticalGradient(listOf(Color(0xFF058CF5), Color(0xFF015DE6)))
                    } else {
                        Brush.verticalGradient(listOf(Color.White, Color.White))
                    }

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(rowBg)
                                .clickable {
                                    if (file.isDirectory) {
                                        currentDir = file
                                    } else {
                                        val audioFiles = fileList.filter { !it.isDirectory }
                                        val startIndex = audioFiles.indexOf(file).coerceAtLeast(0)
                                        val mediaItems = audioFiles.map { audio ->
                                            MediaItem.Builder()
                                                .setUri(Uri.fromFile(audio))
                                                .setMediaMetadata(
                                                    MediaMetadata.Builder()
                                                        .setTitle(audio.nameWithoutExtension)
                                                        .setArtist(currentDir.name)
                                                        .build()
                                                )
                                                .build()
                                        }
                                        player.setMediaItems(mediaItems, startIndex, 0L)
                                        player.prepare()
                                        player.play()
                                        currentTrackName = file.nameWithoutExtension
                                        currentArtistAlbum = currentDir.name
                                        currentFilePath = file.absolutePath
                                        showNowPlaying = true
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isDirectory) Icons.Default.List else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isThisTrackPlaying) Color.White else if (file.isDirectory) Color(0xFF6D84A2) else Color(0xFF015DE6),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (file.isDirectory) file.name else file.nameWithoutExtension,
                                color = if (isThisTrackPlaying) Color.White else Color.Black,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (file.isDirectory) {
                                Text(
                                    text = "›",
                                    color = if (isThisTrackPlaying) Color.White else Color(0xFF999999),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = file.extension.uppercase(),
                                    color = if (isThisTrackPlaying) Color.White.copy(alpha = 0.85f) else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                    }
                }
            }
        } else {
            // ==========================================
            // TELA 2: "TOCANDO AGORA" (iPOD TOUCH 2007)
            // ==========================================
            IPodDarkTopBar(
                artist = currentArtistAlbum,
                title = currentTrackName,
                onBackClick = { showNowPlaying = false }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Barra de Tempo com acabamento de vidro (Scrubber Bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF2C2C2C), Color(0xFF141414), Color(0xFF050505))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = progress,
                        onValueChange = { newValue ->
                            progress = newValue
                            player.seekTo((newValue * duration).toLong())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF3A89FF),
                            inactiveTrackColor = Color(0xFF555555)
                        )
                    )
                    Text(
                        text = "-${formatTime((duration - currentPosition).coerceAtLeast(0L))}",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Capa do Álbum + Efeito Clássico de Reflexo no Chão do iPod touch
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .border(1.dp, Color(0xFF333333))
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (albumArt != null) {
                            Image(
                                bitmap = albumArt!!.asImageBitmap(),
                                contentDescription = "Capa do Álbum",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            DefaultIPodArtwork()
                        }
                    }

                    // Reflexo Espelhado Invertido (Skeuomorphism 2007)
                    Box(
                        modifier = Modifier
                            .size(width = 230.dp, height = 55.dp)
                            .graphicsLayer {
                                rotationX = 180f
                                alpha = 0.28f
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.Black, Color.Transparent)
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    ) {
                        if (albumArt != null) {
                            Image(
                                bitmap = albumArt!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            DefaultIPodArtwork()
                        }
                    }
                }

                // Painel de Controles de Vidro/Metal Escovado
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xCC2A2A2A),
                                    Color(0xEE141414),
                                    Color(0xFF050505)
                                )
                            )
                        )
                        .border(width = 1.dp, color = Color(0xFF3A3A3A))
                        .padding(vertical = 18.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Faixa Anterior ( Dupla Seta |<< )
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(3.dp).height(20.dp).background(Color.White))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowLeft,
                                    contentDescription = "Anterior",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }

                        // Botão Play / Pause Brilhante Estilo iPod
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF555555), Color(0xFF222222), Color(0xFF0A0A0A))
                                    )
                                )
                                .border(1.dp, Color(0xFF888888), CircleShape)
                                .clickable {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPlaying) {
                                // Ícone de Pausa ( || ) desenhado perfeitamente
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(modifier = Modifier.width(6.dp).height(22.dp).background(Color.White))
                                    Box(modifier = Modifier.width(6.dp).height(22.dp).background(Color.White))
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Próxima Faixa ( >>| )
                        IconButton(onClick = { player.seekToNextMediaItem() }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Próxima",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp)
                                )
                                Box(modifier = Modifier.width(3.dp).height(20.dp).background(Color.White))
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // BARRA DE ABAS INFERIOR (DOCK DO iPOD TOUCH)
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF424242), Color(0xFF212121), Color(0xFF080808))
                    )
                )
                .border(1.dp, Color(0xFF555555)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { showNowPlaying = false }
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "Pastas",
                    tint = if (!showNowPlaying) Color(0xFF4CA6FF) else Color(0xFF888888),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Pastas",
                    color = if (!showNowPlaying) Color.White else Color(0xFF888888),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { showNowPlaying = true }
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Tocando Agora",
                    tint = if (showNowPlaying) Color(0xFF4CA6FF) else Color(0xFF888888),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Tocando Agora",
                    color = if (showNowPlaying) Color.White else Color(0xFF888888),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun IPodSilverTopBar(
    title: String,
    showBack: Boolean,
    onBackClick: () -> Unit,
    showNowPlayingButton: Boolean,
    onNowPlayingClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF2F5F8), Color(0xFFBCC6D0), Color(0xFF8E9BA8), Color(0xFF6D7B8A))
                )
            )
            .border(1.dp, Color(0xFF4D5863))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (showBack) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF8A9AA9), Color(0xFF4B5C6B))
                        )
                    )
                    .border(1.dp, Color(0xFF33404D), RoundedCornerShape(6.dp))
                    .clickable { onBackClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("‹ Voltar", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            text = title,
            color = Color(0xFF222A30),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 90.dp)
        )

        if (showNowPlayingButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF5A99E6), Color(0xFF1E5799))
                        )
                    )
                    .border(1.dp, Color(0xFF113561), RoundedCornerShape(6.dp))
                    .clickable { onNowPlayingClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("Tocando ›", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun IPodDarkTopBar(
    artist: String,
    title: String,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF4A4A4A), Color(0xFF242424), Color(0xFF0A0A0A))
                )
            )
            .border(1.dp, Color(0xFF333333))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF555555), Color(0xFF222222))
                    )
                )
                .border(1.dp, Color(0xFF666666), RoundedCornerShape(6.dp))
                .clickable { onBackClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = "Voltar para lista",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 60.dp)
        ) {
            Text(
                text = artist,
                color = Color(0xFFBBBBBB),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DefaultIPodArtwork() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A3A4A), Color(0xFF0F171E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(110.dp)) {
            drawCircle(
                color = Color(0xFF4CA6FF).copy(alpha = 0.25f),
                style = Stroke(width = 6f)
            )
        }
        Text(
            text = "♫",
            color = Color(0xFF68B5FF),
            fontSize = 72.sp,
            textAlign = TextAlign.Center
        )
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
