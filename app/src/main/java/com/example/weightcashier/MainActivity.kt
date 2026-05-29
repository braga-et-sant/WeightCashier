package com.example.weightcashier

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import com.example.weightcashier.ui.theme.WeightCashierTheme
import android.content.Context

const val SERVER_PORT = 5000
const val DEFAULT_SERVER_IP = "192.168.1.249"

private const val PREFS_NAME = "weightcashier_prefs"
private const val PREF_SERVER_IP = "server_ip"
private const val PREF_SERVER_PORT = "server_port"
private const val PREF_LAST_NIF = "last_nif"
data class ProductItem(
    val name: String,
    val weightGrams: Int,
    val priceCents: Int,
    val scale: String
) {
    val weightKg: Double
        get() = weightGrams / 1000.0
}

data class ArchivedTransaction(
    val timestamp: String,
    val items: List<ProductItem>,
    val total: Double
)

data class ScaleAssignResult(
    val status: String,
    val scaleId: String?,
    val message: String?
)

data class ApiAuthResult(
    val status: String,
    val clientName: String?,
    val token: String?,
    val message: String?
)

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun getSavedServerIp(context: Context): String {
    return prefs(context).getString(PREF_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
}

private fun getSavedServerPort(context: Context): String {
    return prefs(context).getString(PREF_SERVER_PORT, SERVER_PORT.toString()) ?: SERVER_PORT.toString()
}

private fun getSavedLastNif(context: Context): String {
    return prefs(context).getString(PREF_LAST_NIF, "") ?: ""
}

private fun saveConnectionPrefs(context: Context, serverIp: String, serverPortText: String, nif: String) {
    prefs(context).edit()
        .putString(PREF_SERVER_IP, serverIp)
        .putString(PREF_SERVER_PORT, serverPortText)
        .putString(PREF_LAST_NIF, nif)
        .apply()
}


class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var onTagRead: ((String) -> Unit)? = null
    private var onNfcStatusChange: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            WeightCashierTheme {
                WeightingCashierApp(
                    hasNfc = nfcAdapter != null,
                    isNfcEnabled = nfcAdapter?.isEnabled == true,
                    onRegisterNfcCallbacks = { tagCallback, statusCallback ->
                        onTagRead = tagCallback
                        onNfcStatusChange = statusCallback
                    },
                    onOpenNfcSettings = {
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableForegroundNfc()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val tagText = readNfcText(intent)

        if (tagText != null) {
            onTagRead?.invoke(tagText.trim().uppercase())
        } else {
            onNfcStatusChange?.invoke("Could not read text from NFC tag.")
        }
    }

    private fun enableForegroundNfc() {
        val adapter = nfcAdapter ?: return

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE
        )

        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun readNfcText(intent: Intent): String? {
        val messages: List<NdefMessage>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.mapNotNull { it as? NdefMessage }
        }

        if (!messages.isNullOrEmpty()) {

            for (message in messages) {
                for (record in message.records) {
                    val payload = record.payload ?: continue

                    if (payload.isEmpty()) continue

                    val languageCodeLength = payload[0].toInt() and 0x3F
                    val textStart = 1 + languageCodeLength

                    if (payload.size > textStart) {
                        return String(
                            payload,
                            textStart,
                            payload.size - textStart,
                            Charsets.UTF_8
                        )
                    }
                }
            }
        }

        val tag: Tag = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return null
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return null
        }
        val ndef = Ndef.get(tag) ?: return null

        return try {
            ndef.connect()

            val message = ndef.ndefMessage ?: return null

            for (record in message.records) {
                val payload = record.payload ?: continue

                if (payload.isEmpty()) continue

                val languageCodeLength = payload[0].toInt() and 0x3F
                val textStart = 1 + languageCodeLength

                if (payload.size > textStart) {
                    return String(
                        payload,
                        textStart,
                        payload.size - textStart,
                        Charsets.UTF_8
                    )
                }
            }

            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (_: Exception) {
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightingCashierApp(
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    onRegisterNfcCallbacks: (((String) -> Unit, (String) -> Unit) -> Unit),
    onOpenNfcSettings: () -> Unit
) {

    val context = LocalContext.current

    var isLoggedIn by remember { mutableStateOf(false) }
    var customerNif by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf<String?>(null) }

    var serverIp by remember { mutableStateOf(getSavedServerIp(context)) }
    var serverPortText by remember { mutableStateOf(getSavedServerPort(context)) }

    val scope = rememberCoroutineScope()

    if (!isLoggedIn) {
        LoginScreen(
            serverIp = serverIp,
            serverPortText = serverPortText,
            onServerIpChange = { serverIp = it },
            onServerPortChange = { serverPortText = it },
            initialNif = getSavedLastNif(context),
            onLoginSuccess = { nif, name, token ->
                customerNif = nif
                customerName = name
                authToken = token
                saveConnectionPrefs(context, serverIp, serverPortText, nif)
                isLoggedIn = true
            }
        )
        return
    }

    var selectedScale by remember { mutableStateOf("No scale assigned") }
    var assignedScaleId by remember { mutableStateOf<String?>(null) }
    var readScaleMode by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf(listOf<ProductItem>()) }
    var archive by remember { mutableStateOf(listOf<ArchivedTransaction>()) }
    var selectedArchive by remember { mutableStateOf<ArchivedTransaction?>(null) }

    var warningText by remember { mutableStateOf<String?>(null) }
    var socketConnected by remember { mutableStateOf(false) }
    var socketStatus by remember { mutableStateOf("Disconnected") }
    var socketRef by remember { mutableStateOf<Socket?>(null) }

    var rfidStatus by remember {
        mutableStateOf("Read a scale tag to select a station.")
    }

    val totalCents = items.sumOf { it.priceCents }

    val screenScrollState = rememberScrollState()
    val shoppingListState = rememberLazyListState()
    val archiveListState = rememberLazyListState()

    LaunchedEffect(selectedScale, readScaleMode) {
        onRegisterNfcCallbacks(
            { tagText ->

                when {

                    readScaleMode -> {

                        scope.launch {
                            val result = assignScaleToCustomer(
                                serverIp,
                                serverPortText,
                                authToken,
                                customerNif,
                                tagText
                            )

                            if (result == null) {
                                rfidStatus = "Unable to reach server."
                                return@launch
                            }

                            if (result.status == "pending") {
                                selectedScale = "Pending tare"
                                rfidStatus = "Waiting for scale to tare..."
                                readScaleMode = false
                            } else if (result.status == "busy") {
                                rfidStatus = "Scale is busy. Try another."
                            } else if (result.status == "success" && result.scaleId != null) {
                                assignedScaleId = result.scaleId
                                selectedScale = result.scaleId
                                rfidStatus = "Selected ${result.scaleId}."
                                readScaleMode = false
                            } else {
                                rfidStatus = result.message ?: "Unable to assign scale."
                            }
                        }
                    }

                    else -> {
                        rfidStatus =
                            "Tag read: $tagText. Press Read Scale first."
                    }
                }
            },
            { status ->
                rfidStatus = status
            }
        )
    }

    DisposableEffect(isLoggedIn, serverIp, serverPortText, customerNif, authToken) {
        if (!isLoggedIn) {
            return@DisposableEffect onDispose { }
        }

        val baseUrl = buildBaseUrl(serverIp, serverPortText)
        if (baseUrl == null) {
            warningText = "Invalid server address."
            return@DisposableEffect onDispose { }
        }

        val options = IO.Options()
        options.reconnection = true
        options.reconnectionAttempts = Int.MAX_VALUE
        options.reconnectionDelay = 500
        options.reconnectionDelayMax = 5000

        val socket = IO.socket(baseUrl, options)
        socketRef = socket

        val onConnect = Emitter.Listener {
            socketConnected = true
            socketStatus = "Live"
            warningText = null
            socket.emit("register_android", JSONObject(mapOf("token" to (authToken ?: ""))))
        }

        val onDisconnect = Emitter.Listener {
            socketConnected = false
            socketStatus = "Disconnected"
        }

        val onCartUpdate = Emitter.Listener { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@Listener
            val updatedItems = parseCartItems(payload)
            if (updatedItems != null) {
                items = updatedItems
            }
        }

        val onScaleReady = Emitter.Listener { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@Listener
            val scaleId = payload.optString("scale_id")
            if (scaleId.isNotBlank()) {
                assignedScaleId = scaleId
                selectedScale = scaleId
                rfidStatus = "Scale ${scaleId} ready."
            }
        }

        val onScaleError = Emitter.Listener { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@Listener
            val reason = payload.optString("reason")
            rfidStatus = "Scale error: ${reason.ifBlank { "unknown" }}"
            selectedScale = "No scale assigned"
            assignedScaleId = null
        }

        val onCartError = Emitter.Listener { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@Listener
            val reason = payload.optString("reason")
            warningText = "Cart error: ${reason.ifBlank { "unknown" }}"
        }

        socket.on(Socket.EVENT_CONNECT, onConnect)
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect)
        socket.on("cart_update", onCartUpdate)
        socket.on("scale_ready", onScaleReady)
        socket.on("scale_error", onScaleError)
        socket.on("cart_error", onCartError)
        socket.connect()

        onDispose {
            socket.off(Socket.EVENT_CONNECT, onConnect)
            socket.off(Socket.EVENT_DISCONNECT, onDisconnect)
            socket.off("cart_update", onCartUpdate)
            socket.off("scale_ready", onScaleReady)
            socket.off("scale_error", onScaleError)
            socket.off("cart_error", onCartError)
            socket.disconnect()
            socketRef = null
            socketConnected = false
        }
    }

    LaunchedEffect(socketConnected, assignedScaleId) {
        while (socketConnected) {
            val scaleId = assignedScaleId
            if (!scaleId.isNullOrBlank()) {
                socketRef?.emit("android_heartbeat", JSONObject(mapOf("scale_id" to scaleId)))
            }
            delay(15000)
        }
    }

    LaunchedEffect(isLoggedIn, socketConnected, serverIp, serverPortText, customerNif, authToken) {
        var backoffMs = 1000L
        while (isLoggedIn && !socketConnected) {
            val updatedItems = fetchCartItems(serverIp, serverPortText, authToken, customerNif)
            if (updatedItems != null) {
                items = updatedItems
                warningText = "Realtime disconnected. Using fallback sync."
                backoffMs = 1000L
            } else {
                warningText = "Unable to reach server. Retrying..."
                backoffMs = (backoffMs * 2).coerceAtMost(8000L)
            }
            delay(backoffMs)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Weighting Cashier")
                },
                actions = {
                    Text(
                        text = "Hi, $customerName",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            Column(
                modifier = Modifier
                    .verticalScroll(screenScrollState)
                    .padding(16.dp)
                    .padding(end = 8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "€${totalCents.moneyFromCents()}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Scale: $selectedScale",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Realtime: $socketStatus",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                warningText?.let { warning ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = warning,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {

                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        Text(
                            "Read Tag / Producte",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(rfidStatus)

                        if (!hasNfc) {

                            Text(
                                "This device does not support NFC.",
                                color = MaterialTheme.colorScheme.error
                            )

                        } else if (!isNfcEnabled) {

                            Text(
                                "NFC is disabled.",
                                color = MaterialTheme.colorScheme.error
                            )

                            Button(onClick = onOpenNfcSettings) {
                                Text("Open NFC Settings")
                            }

                        } else {

                            Button(
                                onClick = {
                                    readScaleMode = true
                                    rfidStatus = "Waiting for scale tag..."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Scan Scale Tag")
                            }
                        }
                    }
                }

                Text(
                    "My Cart",
                    style = MaterialTheme.typography.titleLarge
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {

                        Box(modifier = Modifier.fillMaxSize()) {

                            LazyColumn(
                                state = shoppingListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .padding(end = 10.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {

                                if (items.isEmpty()) {
                                    item {
                                        Text("Your cart is empty.")
                                    }
                                }

                                items(items) { item ->

                                    ProductRow(
                                        item = item,
                                        onRemove = {
                                            items = items - item
                                        }
                                    )
                                }
                            }

                            LazyScrollbar(
                                state = shoppingListState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(
                                        vertical = 8.dp,
                                        horizontal = 4.dp
                                    )
                            )
                        }
                    }

                Button(
                    onClick = {
                        if (items.isNotEmpty()) {
                            archive = archive + ArchivedTransaction(
                                timestamp = currentTimestamp(),
                                items = items,
                                total = totalCents / 100.0
                            )
                        }

                        scope.launch {
                            val ok = resetCart(serverIp, serverPortText, authToken, customerNif)
                            if (!ok) {
                                warningText = "Failed to reset cart on server."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Finish & Start New")
                }

                Text(
                    "Receipts",
                    style = MaterialTheme.typography.titleMedium
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = archiveListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (archive.isEmpty()) {
                                    item {
                                        Text("No receipts yet.")
                                    }
                                }

                                items(archive.reversed()) { transaction ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { selectedArchive = transaction }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                transaction.timestamp,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                "${transaction.items.size} items — €${transaction.total.money()}"
                                            )
                                        }
                                    }
                                }
                            }

                            LazyScrollbar(
                                state = archiveListState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                    }

                Spacer(modifier = Modifier.height(20.dp))
            }

            AppScrollbar(
                currentValue = screenScrollState.value,
                maxValue = screenScrollState.maxValue,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        selectedArchive?.let { transaction ->
            AlertDialog(
                onDismissRequest = { selectedArchive = null },
                confirmButton = {
                    TextButton(onClick = { selectedArchive = null }) {
                        Text("Close")
                    }
                },
                title = {
                    Text("Receipt")
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text("Date: ${transaction.timestamp}")
                        }

                        items(transaction.items) { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text("Source: ${item.scale}")
                                    Text("Weight: ${item.weightKg.money()} kg")
                                    Text("Price: €${item.priceCents.moneyFromCents()}")
                                }
                            }
                        }

                        item {
                            Text(
                                "Final Total: €${transaction.total.money()}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            )
        }

    }
}

@Composable
fun LoginScreen(
    serverIp: String,
    serverPortText: String,
    onServerIpChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    initialNif: String,
    onLoginSuccess: (String, String, String) -> Unit
) {

    var isSignUp by remember { mutableStateOf(false) }
    var nif by remember { mutableStateOf(initialNif) }
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {

        Card(modifier = Modifier.fillMaxWidth()) {

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    "Welcome",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    if (isSignUp) "Create an account to start shopping." else "Sign in to view your cart and receipts.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = nif,
                    onValueChange = {
                        nif = it
                        error = null
                    },
                    label = {
                        Text("Customer ID (NIF)")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isSignUp) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            error = null
                        },
                        label = {
                            Text("Name")
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            error = null
                        },
                        label = {
                            Text("Email")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    label = {
                        Text("PIN")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                var showAdvanced by remember { mutableStateOf(false) }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide connection settings" else "Connection settings")
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = onServerIpChange,
                        label = {
                            Text("Server IP")
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = serverPortText,
                        onValueChange = {
                            onServerPortChange(
                                it.filter { character ->
                                    character.isDigit()
                                }.take(5)
                            )
                        },
                        label = {
                            Text("Server Port")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = {
                        if (isLoading) return@Button

                        scope.launch {
                            isLoading = true
                            val result = if (isSignUp) {
                                apiRegister(serverIp, serverPortText, nif, pin, name, email)
                            } else {
                                apiLogin(serverIp, serverPortText, nif, pin)
                            }
                            isLoading = false

                            if (result == null) {
                                error = "Server unreachable. Check Wi‑Fi and address."
                            } else if (result.status == "success" && !result.clientName.isNullOrBlank() && !result.token.isNullOrBlank()) {
                                onLoginSuccess(nif.trim(), result.clientName, result.token)
                            } else {
                                error = result.message ?: "Request failed."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (isSignUp) "Create Account" else "Sign In"
                    Text(if (isLoading) "Please wait..." else label)
                }

                TextButton(
                    onClick = {
                        isSignUp = !isSignUp
                        error = null
                    }
                ) {
                    Text(if (isSignUp) "Already have an account? Sign in" else "New here? Create an account")
                }
            }
        }
    }
}

@Composable
fun ProductRow(
    item: ProductItem,
    onRemove: () -> Unit
) {

    Card(modifier = Modifier.fillMaxWidth()) {

        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Column(modifier = Modifier.weight(1f)) {

                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Source: ${item.scale}")
                Text("Weight: ${item.weightKg.money()} kg")
                Text("Price: €${item.priceCents.moneyFromCents()}")
            }

            OutlinedButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

private fun buildBaseUrl(serverIp: String, serverPortText: String): String? {
    val port = serverPortText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return "http://$serverIp:$port"
}

suspend fun apiLogin(
    serverIp: String,
    serverPortText: String,
    nif: String,
    pin: String
): ApiAuthResult? {
    val base = buildBaseUrl(serverIp, serverPortText) ?: return null
    val payload = JSONObject(
        mapOf(
            "nif" to nif.trim(),
            "pin" to pin.trim()
        )
    )

    val response = postJson("$base/api/auth/login", payload) ?: return null
    val status = response.optString("status")
    return ApiAuthResult(
        status = status.ifBlank { "error" },
        clientName = response.optString("client_name").ifBlank { null },
        token = response.optString("token").ifBlank { null },
        message = response.optString("message").ifBlank {
            if (status == "unauthorized") "Invalid credentials." else "Sign-in failed."
        }
    )
}

suspend fun assignScaleToCustomer(
    serverIp: String,
    serverPortText: String,
    token: String?,
    nif: String,
    scaleTag: String
): ScaleAssignResult? {
    val base = buildBaseUrl(serverIp, serverPortText) ?: return null
    val payload = JSONObject(
        mapOf(
            "nif" to nif.trim(),
            "scale_rfid" to scaleTag.trim().uppercase()
        )
    )

    val response = postJson("$base/api/scale/assign", payload, token) ?: return null
    return ScaleAssignResult(
        status = response.optString("status"),
        scaleId = response.optString("scale_id"),
        message = response.optString("message")
    )
}

suspend fun fetchCartItems(
    serverIp: String,
    serverPortText: String,
    token: String?,
    nif: String
): List<ProductItem>? {
    val base = buildBaseUrl(serverIp, serverPortText) ?: return null
    val response = getJson("$base/api/cart/${nif.trim()}", token) ?: return null
    if (response.optString("status") != "success") return null

    return parseCartItems(response)
}

suspend fun resetCart(
    serverIp: String,
    serverPortText: String,
    token: String?,
    nif: String
): Boolean {
    val base = buildBaseUrl(serverIp, serverPortText) ?: return false
    val payload = JSONObject(mapOf("nif" to nif.trim()))
    val response = postJson("$base/api/cart/reset", payload, token) ?: return false
    return response.optString("status") == "success"
}

suspend fun postJson(url: String, payload: JSONObject, token: String? = null): JSONObject? {
    return withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.doOutput = true

            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val responseStream =
                if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream

            val response = responseStream?.bufferedReader()?.readText()
            connection.disconnect()
            if (response.isNullOrBlank()) return@withContext null

            JSONObject(response)
        } catch (_: Exception) {
            null
        }
    }
}

suspend fun getJson(url: String, token: String? = null): JSONObject? {
    return withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connectTimeout = 1500
            connection.readTimeout = 1500

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            JSONObject(response)
        } catch (_: Exception) {
            null
        }
    }
}

fun Double.money(): String {
    return String.format(
        Locale.US,
        "%.2f",
        this
    )
}

fun Int.moneyFromCents(): String {
    return String.format(
        Locale.US,
        "%.2f",
        this / 100.0
    )
}

fun currentTimestamp(): String {
    return SimpleDateFormat(
        "yyyy-MM-dd HH:mm",
        Locale.getDefault()
    ).format(Date())
}


@Composable
fun LazyScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {

    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size

    if (totalItems <= visibleItems || totalItems == 0) return

    val firstVisibleItem = state.firstVisibleItemIndex

    val scrollRatio =
        firstVisibleItem.toFloat() /
                max(1, totalItems - visibleItems).toFloat()

    val thumbHeightRatio =
        visibleItems.toFloat() / totalItems.toFloat()

    Box(
        modifier = modifier
            .width(6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(100)
            )
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(
                    thumbHeightRatio.coerceIn(0.15f, 1f)
                )
                .align(Alignment.TopCenter)
                .offset(y = (220.dp * scrollRatio))
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(100)
                )
        )
    }
}

@Composable
fun AppScrollbar(
    currentValue: Int,
    maxValue: Int,
    modifier: Modifier = Modifier
) {

    if (maxValue <= 0) return

    val scrollRatio =
        currentValue.toFloat() / maxValue.toFloat()

    Box(
        modifier = modifier
            .width(6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(100)
            )
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.20f)
                .align(Alignment.TopCenter)
                .offset(y = (520.dp * scrollRatio))
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(100)
                )
        )
    }
}

fun parseCartItems(payload: JSONObject): List<ProductItem>? {
    val jsonItems = payload.optJSONArray("items") ?: return emptyList()
    val items = mutableListOf<ProductItem>()

    for (i in 0 until jsonItems.length()) {
        val item = jsonItems.optJSONObject(i) ?: continue
        items.add(
            ProductItem(
                name = item.optString("name"),
                weightGrams = item.optInt("weight_grams", 0),
                priceCents = item.optInt("price_cents", 0),
                scale = item.optString("scale")
            )
        )
    }

    return items
}

suspend fun apiRegister(
    serverIp: String,
    serverPortText: String,
    nif: String,
    pin: String,
    name: String,
    email: String
): ApiAuthResult? {
    val base = buildBaseUrl(serverIp, serverPortText) ?: return null
    val payload = JSONObject(
        mapOf(
            "nif" to nif.trim(),
            "pin" to pin.trim(),
            "name" to name.trim(),
            "email" to email.trim()
        )
    )

    val response = postJson("$base/api/auth/register", payload) ?: return null
    val status = response.optString("status")
    return if (status == "success") {
        ApiAuthResult(
            status = "success",
            clientName = response.optString("client_name").ifBlank { null },
            token = response.optString("token").ifBlank { null },
            message = null
        )
    } else {
        ApiAuthResult(
            status = status.ifBlank { "error" },
            clientName = null,
            token = null,
            message = response.optString("message").ifBlank { "Unable to create account." }
        )
    }
}