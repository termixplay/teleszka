package com.example.telezhka


import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.telezhka.ui.theme.TelezhkaTheme
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class MainActivity : ComponentActivity() {
    private lateinit var nsdManager: NsdManager
    private val serviceName = "TelezhkaChat"
    private val serviceType = "_telezhka._tcp."
    private lateinit var serverSocket: ServerSocket
    private var serverPort: Int = 0
    private val clientSockets = mutableListOf<Socket>()
    val messages = mutableStateListOf<ChatMessage>()

    private var nickname by mutableStateOf("")
    private var isNicknameSet by mutableStateOf(false)

    sealed class ChatMessage {
        data class Text(val text: String) : ChatMessage()
        data class Image(val bytes: ByteArray) : ChatMessage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        enableEdgeToEdge()
        setContent {
            TelezhkaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!isNicknameSet) {
                        NicknameScreen(onNicknameSet = { nick ->
                            nickname = nick
                            isNicknameSet = true
                            startNetworking()
                        })
                    } else {
                        ChatScreen(
                            messages = messages,
                            onSendMessage = { msg -> sendMessageToAll(msg) },
                            onSendImage = { bytes -> sendImageToAll(bytes) }
                        )
                    }
                }
            }
        }
    }

    private fun startNetworking() {
        Thread {
            try {
                val host = "10.0.2.2"  // или IP нужного сервера
                val port = 12345       // нужный порт
                val socket = java.net.Socket(host, port)
                synchronized(clientSockets) {
                    clientSockets.add(socket)
                }
                listenForMessages(socket)
            } catch (e: IOException) {
                Log.e("Socket", "Ошибка подключения к хост-серверу", e)
            }
        }.start()
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@MainActivity.serviceName
            serviceType = this@MainActivity.serviceType
            this.port = port
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d("NSD", "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        })
    }

    private fun listenForMessages(socket: Socket) {
        Thread {
            try {
                val input = socket.getInputStream()

                while (!socket.isClosed) {
                    val header = readHeader(input) ?: break

                    val type = header["TYPE"] ?: "TEXT"
                    val length = header["LENGTH"]?.toIntOrNull() ?: 0
                    if (length <= 0) continue

                    val data = ByteArray(length)
                    var bytesRead = 0
                    while (bytesRead < length) {
                        val read = input.read(data, bytesRead, length - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }
                    if (bytesRead != length) break

                    if (type == "TEXT") {
                        val msg = String(data, Charsets.UTF_8)
                        runOnUiThread { messages.add(ChatMessage.Text(msg)) }
                    } else if (type == "IMAGE") {
                        runOnUiThread { messages.add(ChatMessage.Image(data)) }
                    }
                }
            } catch (e: IOException) {
                Log.e("Socket", "Read message error", e)
            } finally {
                synchronized(clientSockets) { clientSockets.remove(socket) }
                try { socket.close() } catch (ignored: IOException) {}
            }
        }.start()
    }

    private fun readHeader(input: java.io.InputStream): Map<String, String>? {
        val header = mutableMapOf<String, String>()
        val buffer = StringBuilder()

        var lastTwoChars = ""
        while (true) {
            val ch = input.read()
            if (ch == -1) return null
            val c = ch.toChar()
            buffer.append(c)

            lastTwoChars += c
            if (lastTwoChars.length > 2) {
                lastTwoChars = lastTwoChars.takeLast(2)
            }
            if (lastTwoChars == "\n\n") break
        }

        val headerText = buffer.toString().trim()
        val lines = headerText.split("\n")
        for (line in lines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                header[parts[0].trim()] = parts[1].trim()
            }
        }

        return header
    }

    private fun sendMessageToAll(message: String) {
        val socketsCopy = synchronized(clientSockets) { clientSockets.toList() }

        Thread {
            for (socket in socketsCopy) {
                try {
                    val output = socket.getOutputStream()
                    val writer = output.bufferedWriter()
                    val bytes = message.toByteArray(Charsets.UTF_8)
                    writer.write("TYPE:TEXT\nLENGTH:${bytes.size}\n\n")
                    writer.flush()
                    output.write(bytes)
                    output.flush()
                } catch (e: IOException) {
                    Log.e("Socket", "Send text error", e)
                }
            }
        }.start()

        messages.add(ChatMessage.Text(message))
    }

    private fun sendImageToAll(imageBytes: ByteArray) {
        val socketsCopy = synchronized(clientSockets) { clientSockets.toList() }

        Thread {
            for (socket in socketsCopy) {
                try {
                    val output = socket.getOutputStream()
                    val writer = output.bufferedWriter()
                    writer.write("TYPE:IMAGE\nLENGTH:${imageBytes.size}\n\n")
                    writer.flush()
                    output.write(imageBytes)
                    output.flush()
                } catch (e: IOException) {
                    Log.e("Socket", "Send image error", e)
                }
            }
        }.start()

        messages.add(ChatMessage.Image(imageBytes))
    }

    private fun discoverServices() {
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName != serviceName) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val host = resolvedServiceInfo.host.hostAddress
                            val port = resolvedServiceInfo.port
                            Thread {
                                try {
                                    val socket = Socket(host, port)
                                    synchronized(clientSockets) { clientSockets.add(socket) }
                                    listenForMessages(socket)
                                } catch (e: IOException) {
                                    Log.e("Socket", "Connect error", e)
                                }
                            }.start()
                        }
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    @Composable
    fun NicknameScreen(onNicknameSet: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Введите никнейм", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Никнейм") },
                singleLine = true
            )
            Spacer(Modifier.height(16.dp))
            Button(enabled = text.isNotBlank(), onClick = { onNicknameSet(text.trim()) }) {
                Text("Подтвердить")
            }
        }
    }

    @Composable
    fun ChatScreen(
        messages: List<ChatMessage>,
        onSendMessage: (String) -> Unit,
        onSendImage: (ByteArray) -> Unit
    ) {
        var inputText by remember { mutableStateOf("") }
        val context = LocalContext.current

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
            onResult = { uri: Uri? ->
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bytes = inputStream.readBytes()
                        onSendImage(bytes)
                    }
                }
            }
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(id = R.drawable.background),  // background.jpg должен быть в res/drawable под именем background.jpg
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    items(messages.reversed()) { msg ->
                        when (msg) {
                            is ChatMessage.Text -> {
                                val isOutgoing = msg.text.startsWith("$nickname: ")
                                val displayText = if (isOutgoing) msg.text.removePrefix("$nickname: ").trimStart() else msg.text

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (isOutgoing)
                                                    Color(0x8032CD32)  // Полупрозрачный зелёный
                                                else
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isOutgoing)
                                                    Color(0x8032CD32)
                                                else
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = displayText,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                            }
                            is ChatMessage.Image -> {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(msg.bytes, 0, msg.bytes.size)
                                if (bmp != null) {
                                    val isOutgoing = false // Можно реализовать аналогично, если нужно
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp),
                                        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(12.dp)
                                        ) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.widthIn(max = 200.dp), // Ограничим ширину, чтобы не занимало весь экран
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Введите сообщение") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage("$nickname: $inputText")
                                inputText = ""
                            }
                        }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.send_icon),
                            contentDescription = "Send"
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { launcher.launch("image/*") }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.image_icon),
                            contentDescription = "Select Image"
                        )
                    }
                }

            }
        }
    }
}