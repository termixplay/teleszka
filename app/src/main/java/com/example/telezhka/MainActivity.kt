package com.example.telezhka

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.telezhka.ui.theme.TelezhkaTheme
import java.io.IOException
import java.net.ServerSocket


class MainActivity : ComponentActivity() {
    private lateinit var nsdManager: NsdManager
    private val serviceName = "TelezhkaChat"
    private val serviceType = "_telezhka._tcp."
    private lateinit var serverSocket: ServerSocket
    private var serverPort: Int = 0
    private val clientSockets = mutableListOf<java.net.Socket>() // список клиентских сокетов (устройств, к которым подключаемся)
    val messages = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Инициализация NSD
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        // 🧠 Запускаем сервер в отдельном потоке
        Thread {
            try {
                serverSocket = ServerSocket(0) // 0 — выбрать свободный порт
                serverPort = serverSocket.localPort
                Log.d("Socket", "Сервер запущен на порту $serverPort")

                // Регистрируем сервис в NSD с этим портом
                registerService(serverPort)

                while (true) {
                    val client = serverSocket.accept()
                    Log.d("Socket", "Новое подключение от: ${client.inetAddress.hostAddress}")
                    // Тут можно читать/писать в сокет
                }
            } catch (e: IOException) {
                Log.e("Socket", "Ошибка при запуске сервера", e)
            }
        }.start()

        discoverServices()

        enableEdgeToEdge()
        setContent {
            TelezhkaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(messages = messages, onSendMessage = { msg -> sendMessageToAll(msg) })
                }
            }
        }
    }
    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@MainActivity.serviceName
            serviceType = this@MainActivity.serviceType
            this.port = port
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.d("NSD", "Сервис зарегистрирован: ${NsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
        )
    }

    private fun _addIncomingMessage(message: String) {
        messages.add("Друг: $message")
    }

    private fun listenForMessages(socket: java.net.Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                Log.d("Socket", "Получено сообщение: $line")
                runOnUiThread {
                    // Добавим сообщение в UI
                    _addIncomingMessage(line)
                }
            }
        } catch (e: IOException) {
            Log.e("Socket", "Ошибка чтения сообщения", e)
        } finally {
            synchronized(clientSockets) {
                clientSockets.remove(socket)
            }
            socket.close()
        }
    }

    private fun sendMessageToAll(message: String) {
        // Добавляем своё сообщение в UI
        messages.add("Я: $message")

        val socketsCopy: List<java.net.Socket>
        synchronized(clientSockets) {
            socketsCopy = clientSockets.toList()
        }

        Thread {
            for (socket in socketsCopy) {
                try {
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write(message)
                    writer.newLine()
                    writer.flush()
                } catch (e: IOException) {
                    Log.e("Socket", "Ошибка отправки сообщения", e)
                }
            }
        }.start()
    }

    private fun discoverServices() {
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d("NSD", "Найден сервис: ${serviceInfo.serviceName}")

                    if (serviceInfo.serviceName != serviceName) { // не подключаемся к своему сервису
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                                val host = resolvedServiceInfo.host.hostAddress
                                val port = resolvedServiceInfo.port
                                Log.d("NSD", "Resolved сервис на $host:$port")

                                Thread {
                                    try {
                                        val socket = java.net.Socket(host, port)
                                        synchronized(clientSockets) {
                                            clientSockets.add(socket)
                                        }
                                        listenForMessages(socket)
                                    } catch (e: IOException) {
                                        Log.e("Socket", "Ошибка подключения к серверу", e)
                                    }
                                }.start()
                            }

                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                Log.e("NSD", "Ошибка разрешения сервиса: $errorCode")
                            }
                        })
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
            }
        )
    }


}

@Composable
fun ChatScreen(messages: List<String>, onSendMessage: (String) -> Unit) {
    var message by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        // Фон с масштабированием Crop
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Введите сообщение") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (message.isNotBlank()) {
                            onSendMessage(message)
                            message = ""
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.send_icon),
                        contentDescription = "Отправить сообщение"
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatPreview() {
    TelezhkaTheme {
        ChatScreen(messages = listOf("Привет!", "Как дела?"), onSendMessage = {})
    }
}
