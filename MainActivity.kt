package com.enrico.flacplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Motor de Áudio Hi-Fi (Media3 ExoPlayer) configurado para música pura
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Pausa se tirar o fone Bluetooth
            .setHandleAudioBecomingNoisy(true)
            .build()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlacFolderPlayerScreen(this, player)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}

@Composable
fun FlacFolderPlayerScreen(activity: ComponentActivity, player: ExoPlayer) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(rootDir) }
    var fileList by remember { mutableStateOf(listOf<File>()) }
    var currentTrackName by remember { mutableStateOf("Nenhuma música selecionada") }
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

    // Atualiza lista de pastas e arquivos de áudio (.flac, .mp3, .wav, .ogg, .m4a)
    LaunchedEffect(currentDir, hasPermission) {
        if (hasPermission) {
            val files = currentDir.listFiles()?.filter { file ->
                !file.isHidden && (file.isDirectory || file.extension.lowercase() in listOf("flac", "mp3", "wav", "m4a", "ogg"))
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            fileList = files
        }
    }

    // Monitora o progresso da música em tempo real
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            duration = player.duration.coerceAtLeast(1L)
            currentPosition = player.currentPosition
            progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            delay(500)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.title?.let { currentTrackName = it.toString() }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Cabeçalho estilo Poweramp
        Text("FLAC Folder Player", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
        Text("Pasta: ${currentDir.absolutePath}", fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))

        // Botão para voltar uma pasta acima
        if (currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null) {
            Button(
                onClick = { currentDir.parentFile?.let { currentDir = it } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Voltar para pasta anterior", color = Color.White)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Lista de Pastas e Músicas
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(fileList) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                currentDir = file
                            } else {
                                // Carrega todas as músicas da pasta na fila (Gapless Playback)
                                val audioFiles = fileList.filter { !it.isDirectory }
                                val startIndex = audioFiles.indexOf(file).coerceAtLeast(0)
                                player.clearMediaItems()
                                audioFiles.forEach { audio ->
                                    val item = MediaItem.Builder()
                                        .setUri(Uri.fromFile(audio))
                                        .setMediaMetadata(
                                            androidx.media3.common.MediaMetadata.Builder()
                                                .setTitle(audio.nameWithoutExtension)
                                                .build()
                                        )
                                        .build()
                                    player.addMediaItem(item)
                                }
                                player.seekTo(startIndex, 0L)
                                player.prepare()
                                player.play()
                                currentTrackName = file.nameWithoutExtension
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Default.List else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (file.isDirectory) Color(0xFFFFD54F) else Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(file.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!file.isDirectory) {
                            Text(file.extension.uppercase(), color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
                Divider(color = Color(0xFF2A2A2A))
            }
        }

        // Painel de Controle Inferior (Mini-Poweramp)
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(currentTrackName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        progress = newValue
                        player.seekTo((newValue * duration).toLong())
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { player.seekToPrevious() }) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Anterior", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    FloatingActionButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        containerColor = Color(0xFF00E5FF)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black
                        )
                    }
                    IconButton(onClick = { player.seekToNext() }) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Próxima", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}
