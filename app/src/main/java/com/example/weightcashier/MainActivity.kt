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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

data class ProductItem(
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
    val pricePerKg: Double,
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
            onNfcStatusChange?.invoke("Could not read text from this NFC tag.")
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
    var selectedScale by remember { mutableStateOf("Scale 1") }
    var developerMode by remember { mutableStateOf(false) }
    var readProductMode by remember { mutableStateOf(false) }
    var readScaleMode by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf(listOf<ProductItem>()) }
    var archive by remember { mutableStateOf(listOf<ArchivedTransaction>()) }
    var selectedArchive by remember { mutableStateOf<ArchivedTransaction?>(null) }

    var productName by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var warningText by remember { mutableStateOf<String?>(null) }
    var rfidStatus by remember { mutableStateOf("Read a scale tag first, then read product tags.") }

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
                            it.tagCode == tagText || it.name.uppercase() == tagText
                        }

                        if (product != null) {
                            items = items + ProductItem(
                                name = product.name,
                                weightKg = product.defaultWeightKg,
                                pricePerKg = product.pricePerKg,
                                scale = selectedScale
                            )
                            rfidStatus = "Added ${product.name} from $selectedScale."
                            readProductMode = false
                        } else {
                            rfidStatus = "Unknown product tag: $tagText"
                        }
                    }

                    else -> {
                        rfidStatus = "Tag read: $tagText. Press Read Scale or Read Product first."
                    }
                }
            },
            { status ->
                rfidStatus = status
            }
        )
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Weighting Cashier") })
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
                            Text("RFID / NFC Reader", style = MaterialTheme.typography.titleMedium)

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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            readScaleMode = true
                                            readProductMode = false
                                            rfidStatus = "Waiting for scale tag: SCALE_1 or SCALE_2..."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Read Scale")
                                    }

                                    Button(
                                        onClick = {
                                            readProductMode = true
                                            readScaleMode = false
                                            rfidStatus = "Waiting for product RFID/NFC tag..."
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
                        onClick = { developerMode = !developerMode },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (developerMode) {
                                "Developer Mode: ON"
                            } else {
                                "Developer Mode: OFF"
                            }
                        )
                    }

                    if (developerMode) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Developer Tools",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            selectedScale = "Scale 1"
                                            rfidStatus = "Developer selected Scale 1."
                                        }
                                    ) {
                                        Text("Mock Scale 1")
                                    }

                                    Button(
                                        onClick = {
                                            selectedScale = "Scale 2"
                                            rfidStatus = "Developer selected Scale 2."
                                        }
                                    ) {
                                        Text("Mock Scale 2")
                                    }
                                }

                                OutlinedTextField(
                                    value = productName,
                                    onValueChange = { productName = it },
                                    label = { Text("Product name") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = weightText,
                                    onValueChange = {
                                        weightText = it.filterNumericDecimal()
                                        warningText = null
                                    },
                                    label = { Text("Weight (kg)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = weightText.isNotBlank() && weightText.toDecimalOrNull() == null,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = {
                                        priceText = it.filterNumericDecimal()
                                        warningText = null
                                    },
                                    label = { Text("Price per kg (€)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = priceText.isNotBlank() && priceText.toDecimalOrNull() == null,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                warningText?.let {
                                    Text(
                                        text = it,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val weight = weightText.toDecimalOrNull()
                                            val price = priceText.toDecimalOrNull()

                                            when {
                                                productName.isBlank() -> warningText = "Please enter a product name."
                                                weight == null -> warningText = "Please enter a valid numeric weight."
                                                price == null -> warningText = "Please enter a valid numeric price."
                                                else -> {
                                                    items = items + ProductItem(
                                                        name = productName.trim(),
                                                        weightKg = weight,
                                                        pricePerKg = price,
                                                        scale = selectedScale
                                                    )
                                                    productName = ""
                                                    weightText = ""
                                                    priceText = ""
                                                    warningText = null
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Add Item")
                                    }

                                    Button(
                                        onClick = {
                                            items = items + generateRandomProduct(selectedScale)
                                        }
                                    ) {
                                        Text("Quick Add")
                                    }

                                    Button(
                                        onClick = {
                                            val mockProduct = productDatabase.random()
                                            items = items + ProductItem(
                                                name = mockProduct.name,
                                                weightKg = Random.nextDouble(0.20, 3.00).roundTo2Decimals(),
                                                pricePerKg = mockProduct.pricePerKg,
                                                scale = selectedScale
                                            )
                                            rfidStatus = "Mock Raspberry update received."
                                        }
                                    ) {
                                        Text("Mock Pi")
                                    }
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
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (items.isEmpty()) {
                                    item {
                                        Text("No products added yet.")
                                    }
                                }

                                items(items) { item ->
                                    ProductRow(
                                        item = item,
                                        onRemove = { items = items - item }
                                    )
                                }
                            }

                            LazyScrollbar(
                                state = shoppingListState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
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

                    Text("Archived Transactions", style = MaterialTheme.typography.titleMedium)

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

                    Spacer(modifier = Modifier.height(16.dp))
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
                                        Text("Source: ${item.scale}")
                                        Text("${item.weightKg.money()} kg × €${item.pricePerKg.money()}/kg")
                                        Text("Total: €${item.totalPrice.money()}")
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(8.dp))
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
fun ProductRow(
    item: ProductItem,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
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
    val scrollRatio = firstVisibleItem.toFloat() / max(1, totalItems - visibleItems).toFloat()
    val thumbHeightRatio = visibleItems.toFloat() / totalItems.toFloat()

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
                .fillMaxHeight(thumbHeightRatio.coerceIn(0.15f, 1f))
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

    val scrollRatio = currentValue.toFloat() / maxValue.toFloat()

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

fun generateRandomProduct(scale: String): ProductItem {
    val product = productDatabase.random()

    return ProductItem(
        name = product.name,
        weightKg = Random.nextDouble(0.20, 3.00).roundTo2Decimals(),
        pricePerKg = product.pricePerKg,
        scale = scale
    )
}

fun Double.roundTo2Decimals(): Double {
    return String.format(Locale.US, "%.2f", this).toDouble()
}

fun Double.money(): String {
    return String.format(Locale.US, "%.2f", this)
}

fun currentTimestamp(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
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
            char.isDigit() -> builder.append(char)
            char == '.' && !hasDecimal -> {
                builder.append(char)
                hasDecimal = true
            }
        }
    }

    return builder.toString()
}