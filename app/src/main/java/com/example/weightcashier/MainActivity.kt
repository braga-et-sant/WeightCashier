package com.example.weightcashier

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random
import android.util.Log

const val LOG_TAG = "WeightCashierPi"
const val RASPBERRY_PORT = 5000

const val DEFAULT_RASPBERRY_ENDPOINT = "/supermarket/scale/1/reading"

const val DEFAULT_RASPBERRY_IP = "192.168.0.2"
data class ProductItem(
    val productId: String,
    val name: String,
    val weightKg: Double,
    val pricePerKg: Double,
    val scale: String
) {
    val totalPrice: Double
        get() = weightKg * pricePerKg
}

data class ArchivedTransaction(
    val timestamp: String,
    val items: List<ProductItem>,
    val total: Double
)

data class ProductDefinition(
    val tagCode: String,
    val name: String,
    val defaultPricePerKg: Double,
    val defaultWeightKg: Double
)



val productDatabase = listOf(
    ProductDefinition("PROD_BANANA", "Banana", 1.79, 0.85),
    ProductDefinition("PROD_APPLE", "Apple", 2.49, 1.10),
    ProductDefinition("PROD_ORANGE", "Orange", 1.99, 1.20),
    ProductDefinition("PROD_PEAR", "Pear", 2.29, 0.95),
    ProductDefinition("PROD_MANGO", "Mango", 3.99, 0.70),
    ProductDefinition("PROD_PEACH", "Peach", 2.89, 0.60),
    ProductDefinition("PROD_GRAPES", "Grapes", 4.49, 0.50),
    ProductDefinition("PROD_STRAWBERRY", "Strawberry", 5.99, 0.35),
    ProductDefinition("PROD_PINEAPPLE", "Pineapple", 2.79, 1.50),
    ProductDefinition("PROD_WATERMELON", "Watermelon", 1.29, 2.80)
)

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var onTagRead: ((String) -> Unit)? = null
    private var onNfcStatusChange: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
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
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)

        if (rawMessages != null) {
            val messages = rawMessages.map { it as NdefMessage }

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

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return null
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

    var isLoggedIn by remember { mutableStateOf(false) }
    var loggedUser by remember { mutableStateOf("") }

    if (!isLoggedIn) {
        LoginScreen(
            onLoginSuccess = { username ->
                loggedUser = username
                isLoggedIn = true
            }
        )
        return
    }

    var selectedScale by remember { mutableStateOf("Scale 1") }
    var developerMode by remember { mutableStateOf(false) }
    var readProductMode by remember { mutableStateOf(false) }
    var readScaleMode by remember { mutableStateOf(false) }

    var raspberryIp by remember { mutableStateOf(DEFAULT_RASPBERRY_IP) }
    var raspberryPortText by remember { mutableStateOf(RASPBERRY_PORT.toString()) }
    var raspberryEndpointPath by remember { mutableStateOf(DEFAULT_RASPBERRY_ENDPOINT) }
    var raspberryPollingEnabled by remember { mutableStateOf(false) }
    var raspberryStatus by remember { mutableStateOf("Raspberry Pi polling disabled.") }

    var items by remember { mutableStateOf(listOf<ProductItem>()) }
    var archive by remember { mutableStateOf(listOf<ArchivedTransaction>()) }
    var selectedArchive by remember { mutableStateOf<ArchivedTransaction?>(null) }

    var productName by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var warningText by remember { mutableStateOf<String?>(null) }

    var rfidStatus by remember {
        mutableStateOf("Read a scale tag first, then read product tags.")
    }

    val total = items.sumOf { it.totalPrice }

    val screenScrollState = rememberScrollState()
    val shoppingListState = rememberLazyListState()
    val archiveListState = rememberLazyListState()

    LaunchedEffect(selectedScale, readProductMode, readScaleMode) {
        onRegisterNfcCallbacks(
            { tagText ->

                when {

                    readScaleMode -> {

                        when (tagText) {

                            "SCALE_1" -> {
                                selectedScale = "Scale 1"
                                rfidStatus = "Selected Scale 1."
                                readScaleMode = false
                            }

                            "SCALE_2" -> {
                                selectedScale = "Scale 2"
                                rfidStatus = "Selected Scale 2."
                                readScaleMode = false
                            }

                            else -> {
                                rfidStatus = "Unknown scale tag: $tagText"
                            }
                        }
                    }

                    readProductMode -> {

                        val product = productDatabase.find {
                            it.tagCode == tagText
                        }

                        if (product != null) {

                            items = items + ProductItem(
                                productId = product.tagCode,
                                name = product.name,
                                weightKg = product.defaultWeightKg,
                                pricePerKg = product.defaultPricePerKg,
                                scale = selectedScale
                            )

                            rfidStatus = "Added ${product.name} from $selectedScale."
                            readProductMode = false

                        } else {
                            rfidStatus = "Unknown product tag: $tagText"
                        }
                    }

                    else -> {
                        rfidStatus =
                            "Tag read: $tagText. Press Read Scale or Read Product first."
                    }
                }
            },
            { status ->
                rfidStatus = status
            }
        )
    }

    LaunchedEffect(
        raspberryPollingEnabled,
        raspberryIp,
        raspberryPortText,
        raspberryEndpointPath
    ) {
        while (raspberryPollingEnabled) {
            val port = raspberryPortText.toIntOrNull()

            if (port == null || port !in 1..65535) {
                raspberryStatus = "Invalid Raspberry Pi port."
                Log.d(LOG_TAG, "Invalid Raspberry Pi port: $raspberryPortText")
                delay(1000)
                continue
            }

            val cleanEndpointPath =
                if (raspberryEndpointPath.startsWith("/")) {
                    raspberryEndpointPath
                } else {
                    "/$raspberryEndpointPath"
                }

            val endpoint = "http://$raspberryIp:$port$cleanEndpointPath"
            val rawJson = fetchRawJson(endpoint)

            if (rawJson != null) {
                Log.d(LOG_TAG, "Raspberry endpoint response from $endpoint: $rawJson")
                raspberryStatus = "Logged Raspberry response to Logcat."
            } else {
                Log.d(LOG_TAG, "No response from Raspberry endpoint: $endpoint")
                raspberryStatus = "No response from Raspberry endpoint."
            }

            delay(1000)
        }
    }

    MaterialTheme {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Weighting Cashier - $loggedUser")
                    }
                )
            }
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

                    Text(
                        text = "Current station: $selectedScale",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {

                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                            Text(
                                "RFID / NFC Reader",
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

                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    Button(
                                        onClick = {
                                            readScaleMode = true
                                            readProductMode = false
                                            rfidStatus =
                                                "Waiting for scale tag..."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Read Scale")
                                    }

                                    Button(
                                        onClick = {
                                            readProductMode = true
                                            readScaleMode = false
                                            rfidStatus =
                                                "Waiting for product tag..."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Read Product")
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            developerMode = !developerMode
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (developerMode)
                                "Developer Mode: ON"
                            else
                                "Developer Mode: OFF"
                        )
                    }

                    if (developerMode) {

                        Card(modifier = Modifier.fillMaxWidth()) {

                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {

                                Text(
                                    "Developer Tools",
                                    style =
                                        MaterialTheme.typography.titleMedium
                                )

                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    Button(
                                        onClick = {
                                            selectedScale = "Scale 1"
                                        }
                                    ) {
                                        Text("Mock Scale 1")
                                    }

                                    Button(
                                        onClick = {
                                            selectedScale = "Scale 2"
                                        }
                                    ) {
                                        Text("Mock Scale 2")
                                    }
                                }

                                OutlinedTextField(
                                    value = productName,
                                    onValueChange = {
                                        productName = it
                                    },
                                    label = {
                                        Text("Product name")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = weightText,
                                    onValueChange = {
                                        weightText =
                                            it.filterNumericDecimal()
                                    },
                                    label = {
                                        Text("Weight (kg)")
                                    },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType =
                                                KeyboardType.Decimal
                                        ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = {
                                        priceText =
                                            it.filterNumericDecimal()
                                    },
                                    label = {
                                        Text("Price per kg (€)")
                                    },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType =
                                                KeyboardType.Decimal
                                        ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    Button(
                                        onClick = {

                                            val weight =
                                                weightText.toDecimalOrNull()

                                            val price =
                                                priceText.toDecimalOrNull()

                                            if (
                                                productName.isNotBlank() &&
                                                weight != null &&
                                                price != null
                                            ) {

                                                items = items + ProductItem(
                                                    productId =
                                                        "MANUAL_PRODUCT",
                                                    name = productName,
                                                    weightKg = weight,
                                                    pricePerKg = price,
                                                    scale = selectedScale
                                                )

                                                productName = ""
                                                weightText = ""
                                                priceText = ""
                                            }
                                        }
                                    ) {
                                        Text("Add Item")
                                    }

                                    Button(
                                        onClick = {
                                            items =
                                                items +
                                                        generateRandomProduct(
                                                            selectedScale
                                                        )
                                        }
                                    ) {
                                        Text("Quick Add")
                                    }

                                    Button(
                                        onClick = {

                                            val mock =
                                                productDatabase.random()

                                            items = items + ProductItem(
                                                productId = mock.tagCode,
                                                name = mock.name,
                                                weightKg =
                                                    Random.nextDouble(
                                                        0.20,
                                                        3.00
                                                    ).roundTo2Decimals(),
                                                pricePerKg =
                                                    mock.defaultPricePerKg,
                                                scale = selectedScale
                                            )

                                            rfidStatus =
                                                "Mock Raspberry update received."
                                        }
                                    ) {
                                        Text("Mock Pi")
                                    }
                                }

                                Divider()

                                OutlinedTextField(
                                    value = raspberryIp,
                                    onValueChange = {
                                        raspberryIp = it
                                    },
                                    label = {
                                        Text("Raspberry Pi IP")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = raspberryPortText,
                                    onValueChange = {
                                        raspberryPortText =
                                            it.filter { character ->
                                                character.isDigit()
                                            }.take(5)
                                    },
                                    label = {
                                        Text("Raspberry Pi Port")
                                    },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType =
                                                KeyboardType.Number
                                        ),
                                    isError =
                                        raspberryPortText.toIntOrNull()
                                            ?.let { port ->
                                                port !in 1..65535
                                            } ?: true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = raspberryEndpointPath,
                                    onValueChange = {
                                        raspberryEndpointPath = it
                                    },
                                    label = {
                                        Text("Raspberry Endpoint Path")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(raspberryStatus)

                                OutlinedButton(
                                    onClick = {
                                        raspberryPollingEnabled = !raspberryPollingEnabled

                                        val cleanEndpointPath =
                                            if (raspberryEndpointPath.startsWith("/")) {
                                                raspberryEndpointPath
                                            } else {
                                                "/$raspberryEndpointPath"
                                            }

                                        if (raspberryPollingEnabled) {
                                            Log.d(
                                                LOG_TAG,
                                                "Raspberry polling started. Target: http://$raspberryIp:$raspberryPortText$cleanEndpointPath"
                                            )
                                        } else {
                                            Log.d(LOG_TAG, "Raspberry polling stopped.")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (raspberryPollingEnabled)
                                            "Stop Raspberry Polling"
                                        else
                                            "Start Raspberry Polling"
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "Current Shopping List",
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
                                        Text("No products added yet.")
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

                    Text(
                        text = "Total: €${total.money()}",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Button(
                        onClick = {

                            if (items.isNotEmpty()) {

                                archive = archive + ArchivedTransaction(
                                    timestamp = currentTimestamp(),
                                    items = items,
                                    total = total
                                )

                                items = emptyList()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Archive / Checkout Current List")
                    }

                    Text(
                        "Archived Transactions",
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
                                        Text("No archived transactions yet.")
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
                        Text("Archived Transaction")
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
                                        Text("Product ID: ${item.productId}")
                                        Text("Source: ${item.scale}")
                                        Text("${item.weightKg.money()} kg × €${item.pricePerKg.money()}/kg")
                                        Text("Total: €${item.totalPrice.money()}")
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
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit
) {

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MaterialTheme {

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
                        "Weighting Cashier Login",
                        style = MaterialTheme.typography.titleLarge
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            error = null
                        },
                        label = {
                            Text("Username")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        label = {
                            Text("Password")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {

                            if (mockLogin(username, password)) {
                                onLoginSuccess(username)
                            } else {
                                error =
                                    "Invalid username or password."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login")
                    }
                    OutlinedButton(
                        onClick = {
                            onLoginSuccess("debug-user")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Debug Auto Login")
                    }
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

                Text("Product ID: ${item.productId}")
                Text("Source: ${item.scale}")
                Text("Weight: ${item.weightKg.money()} kg")
                Text("Price/kg: €${item.pricePerKg.money()}")
                Text("Item total: €${item.totalPrice.money()}")
            }

            OutlinedButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

fun mockLogin(
    username: String,
    password: String
): Boolean {

    val accounts = mapOf(
        "admin" to "admin",
        "igor" to "1234",
        "cashier" to "cashier"
    )

    return accounts[username.trim()] == password
}

suspend fun fetchRawJson(endpoint: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1500
            connection.readTimeout = 1500

            if (connection.responseCode != 200) {
                connection.disconnect()
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            response
        } catch (e: Exception) {
            //Log.e(LOG_TAG, "Error fetching raw JSON from $endpoint", e)
            null
        }
    }
}

fun generateRandomProduct(
    scale: String
): ProductItem {

    val product = productDatabase.random()

    return ProductItem(
        productId = product.tagCode,
        name = product.name,
        weightKg =
            Random.nextDouble(0.20, 3.00).roundTo2Decimals(),
        pricePerKg = product.defaultPricePerKg,
        scale = scale
    )
}

fun Double.roundTo2Decimals(): Double {
    return String.format(
        Locale.US,
        "%.2f",
        this
    ).toDouble()
}

fun Double.money(): String {
    return String.format(
        Locale.US,
        "%.2f",
        this
    )
}

fun currentTimestamp(): String {
    return SimpleDateFormat(
        "yyyy-MM-dd HH:mm",
        Locale.getDefault()
    ).format(Date())
}

fun String.toDecimalOrNull(): Double? {
    return this.replace(",", ".").toDoubleOrNull()
}

fun String.filterNumericDecimal(): String {

    val normalized = this.replace(",", ".")
    val builder = StringBuilder()
    var hasDecimal = false

    for (char in normalized) {

        when {

            char.isDigit() -> {
                builder.append(char)
            }

            char == '.' && !hasDecimal -> {
                builder.append(char)
                hasDecimal = true
            }
        }
    }

    return builder.toString()
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