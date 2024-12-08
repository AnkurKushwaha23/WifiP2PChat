package com.ankurkushwaha.p2pchat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ankurkushwaha.p2pchat.databinding.ActivityMainBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    val binding get() = _binding!!

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter
    private lateinit var peers: MutableList<WifiP2pDevice>
    private lateinit var deviceNameArray: Array<String>
    private lateinit var deviceArray: Array<WifiP2pDevice>
    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<Message>()
    var socket: Socket = Socket()
    private lateinit var server: Server
    private lateinit var client: Client
    private var isHost: Boolean = false
    private var isShowPeer: Boolean = true
    private var isReceiverRegistered = false
    private var groupOwnerAddress: InetAddress? = null

    //Permission for Accessing Location
    private val PERMISSION_REQUEST_CODE = 1001
    private fun requestPermissionsForDiscovery() {
        val permissionsToRequest = mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)

            // Add NEARBY_WIFI_DEVICES permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initialWork()
        exqListener()
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messageList)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
    }

    private fun exqListener() {
        //On off Wifi
        val wifiSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Handle the result if needed result.resultCode will tell you the result status You can perform actions based on whether the user changed Wi-Fi settings
        }
        binding.onOffButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            wifiSettingsLauncher.launch(intent)
        }

        //Discover of peers
        binding.discoverButton.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsForDiscovery()
            }
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    showToast(this@MainActivity, "Peer discovery started.")
                    binding.connectionStatus.text = "Peer discovery started."
                }

                override fun onFailure(reason: Int) {
                    showToast(this@MainActivity, "Peer discovery failed: $reason")
                    binding.connectionStatus.text = "Peer discovery failed: $reason"
                }
            })
        }

        //Peer on click listeners
        binding.listView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = deviceArray[position]
            showToast(this, "Connecting to: ${selectedDevice.deviceName}")

            val config = WifiP2pConfig().apply {
                deviceAddress = selectedDevice.deviceAddress
                wps.setup = WpsInfo.PBC // Use Push Button Configuration
            }

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    showToast(
                        this@MainActivity,
                        "Connection initiated with ${selectedDevice.deviceName}"
                    )
                    println("Connection initiated with ${selectedDevice.deviceName}")
                }

                override fun onFailure(reason: Int) {
                    val errorMessage = when (reason) {
                        WifiP2pManager.ERROR -> "Internal error occurred."
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P not supported."
                        WifiP2pManager.BUSY -> "System is busy. Try again later."
                        else -> "Unknown error occurred."
                    }
                    showToast(this@MainActivity, "Failed to connect: $errorMessage")
                    println("Failed to connect: $errorMessage")
                }
            })
        }

        binding.linearLayout.setOnClickListener {
            if (isShowPeer) {
                binding.imgPeers.setImageResource(R.drawable.ic_keyboard_arrow_down) // Use setImageResource for ImageView
                binding.listView.visibility = View.GONE
                isShowPeer = false
            } else {
                binding.imgPeers.setImageResource(R.drawable.ic_keyboard_arrow_up) // Use setImageResource for ImageView
                binding.listView.visibility = View.VISIBLE
                isShowPeer = true
            }
        }

        //on send button click
        binding.sendButton.setOnClickListener {
            val message = binding.inputText.text.toString()
            if (message.isNotBlank()) {
                if (socket.isConnected){
                    val executor = Executors.newSingleThreadExecutor()
                    executor.execute {
                        if (isHost) {
                            server.write(message.toByteArray()) // Convert to byte array
                        } else {
                            client.write(message.toByteArray()) // Convert to byte array
                        }
                    }
                    // Clear the input field
                    binding.inputText.text.clear()
                }else{
                    showToast(this,"Unable to send message")
                }
            }
        }
    }

    private fun initialWork() {
        peers = mutableListOf()
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, Looper.getMainLooper(), null)
        receiver = WifiDirectBroadcastReceiver(manager, channel, this)
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
    }

    //Getting the nearby peers
    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val currentPeers = peerList.deviceList.toList()
        // Log the size of peers list and details
        if (currentPeers.isEmpty()) {
            showToast(this, "No devices found.")
            println("Peer List: No devices found.")
            binding.connectionStatus.text = "No devices found."

        } else {
            println("Peer List: Found ${currentPeers.size} devices.")
            currentPeers.forEach { peer ->
                println("Device: ${peer.deviceName}, Address: ${peer.deviceAddress}")
            }
        }

        // Update peers list only if there's a change
        if (peers != currentPeers) {
            peers.clear()
            peers.addAll(currentPeers)

            deviceNameArray = Array(peers.size) { peers[it].deviceName }
            deviceArray = peers.toTypedArray()

            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                deviceNameArray
            )
            binding.listView.adapter = adapter
        }
    }

    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        groupOwnerAddress = info.groupOwnerAddress

        if (info.groupFormed && info.isGroupOwner) {
            binding.connectionStatus.text = "Host"
            isHost = true
            server = Server()
            server.start()
        } else if (info.groupFormed) {
            binding.connectionStatus.text = "Client"
            isHost = false
            client = Client(groupOwnerAddress!!)
            client.start()
        }
    }


    override fun onResume() {
        super.onResume()
        if (!isReceiverRegistered) {
            registerReceiver(receiver, intentFilter)
            isReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isReceiverRegistered) {
            unregisterReceiver(receiver)
            isReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!socket.isClosed) {
            try {
                socket.close()
                Log.d("Socket", "Socket closed")
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e("SocketClose", "Error closing socket: ${e.message}")
            }
        }
        if (isReceiverRegistered) {
            unregisterReceiver(receiver)
            isReceiverRegistered = false
        }
    }

    inner class Server : Thread() {
        private lateinit var serverSocket: ServerSocket
        private lateinit var inputStream: InputStream
        private lateinit var outputStream: OutputStream

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
                // Update the UI for sent messages
                runOnUiThread {
                    val sentMessage = Message(String(bytes), isSent = true)
                    messageList.add(sentMessage)
                    chatAdapter.notifyItemInserted(messageList.size - 1)
                    binding.recyclerView.scrollToPosition(messageList.size - 1)
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        override fun run() {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket.accept()
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                val buffer = ByteArray(1024)
                var bytes: Int
                while (!socket.isClosed) {
                    try {
                        bytes = inputStream.read(buffer)
                        if (bytes > 0) {
                            val receivedMessage = String(buffer, 0, bytes)
                            runOnUiThread {
                                // Update the UI for received messages
                                messageList.add(Message(receivedMessage, isSent = false))
                                chatAdapter.notifyItemInserted(messageList.size - 1)
                                binding.recyclerView.scrollToPosition(messageList.size - 1)
                            }
                        }
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }
            }
        }
    }

    inner class Client(
        hostAddress: InetAddress
    ) : Thread() {
        private val hostAdd: String = hostAddress.hostAddress
        private lateinit var inputStream: InputStream
        private lateinit var outputStream: OutputStream

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
                runOnUiThread {
                    val sentMessage = Message(String(bytes), isSent = true)
                    messageList.add(sentMessage)
                    chatAdapter.notifyItemInserted(messageList.size - 1)
                    binding.recyclerView.scrollToPosition(messageList.size - 1)
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        override fun run() {
            try {
                socket.connect(InetSocketAddress(hostAdd, 8888), 500)
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                val buffer = ByteArray(1024)
                var bytes: Int
                while (!socket.isClosed) {
                    try {
                        bytes = inputStream.read(buffer)
                        if (bytes > 0) {
                            val receivedMessage = String(buffer, 0, bytes)
                            runOnUiThread {
                                // Update the UI for received messages
                                messageList.add(Message(receivedMessage, isSent = false))
                                chatAdapter.notifyItemInserted(messageList.size - 1)
                                binding.recyclerView.scrollToPosition(messageList.size - 1)
                            }
                        }
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }
            }
        }
    }
}