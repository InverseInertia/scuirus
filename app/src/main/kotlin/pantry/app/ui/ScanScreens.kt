package pantry.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pantry.app.scan.BarcodeScannerView
import pantry.app.scan.CameraPermissionGate
import pantry.app.scan.ReceiptCaptureView
import pantry.app.scan.recognizeFromUri
import pantry.core.DraftLine
import pantry.core.Rational
import pantry.core.ReceiptDraft
import java.time.LocalDate

@Composable
fun ScanScreen(vm: PantryViewModel) {
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.scanMode == ScanMode.REMOVE, onClick = { vm.scanMode = ScanMode.REMOVE }, label = { Text("Using up") })
            FilterChip(selected = vm.scanMode == ScanMode.ADD, onClick = { vm.scanMode = ScanMode.ADD }, label = { Text("Putting away") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraPermissionGate {
                BarcodeScannerView(Modifier.fillMaxSize(), onBarcode = vm::onBarcode)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                manual, { manual = it }, label = { Text("Type a barcode") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(enabled = manual.isNotBlank(), onClick = { vm.onBarcode(manual.trim()); manual = "" }) { Text("Go") }
        }
    }
    vm.newProductPrompt?.let { NewProductDialog(it, vm) }
}

@Composable
private fun NewProductDialog(prompt: NewProductPrompt, vm: PantryViewModel) {
    val s = prompt.suggestion
    var name by remember(s) { mutableStateOf(s?.name ?: "") }
    var packSize by remember(s) { mutableStateOf(s?.packSize ?: "") }
    var unit by remember { mutableStateOf("each") }
    var location by remember { mutableStateOf("pantry") }
    var shelf by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    val q = Rational.parseOrNull(qty)?.takeIf { it.isPositive }
    val adding = vm.scanMode == ScanMode.ADD

    AlertDialog(
        onDismissRequest = vm::dismissNewProduct,
        title = { Text("New barcode ${prompt.barcode}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        prompt.loading -> "Looking it up..."
                        s != null -> "Found on Open Food Facts. Check the details."
                        prompt.lookupError != null -> "Could not reach Open Food Facts (${prompt.lookupError}). Enter the details."
                        else -> "Not found online. Enter the details."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit you count in (carton, bag, each)") }, singleLine = true)
                OutlinedTextField(packSize, { packSize = it }, label = { Text("Package size (optional)") }, singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Usually kept in") }, singleLine = true)
                OutlinedTextField(shelf, { shelf = it }, label = { Text("Shelf life in days (optional)") }, singleLine = true)
                if (adding) OutlinedTextField(qty, { qty = it }, label = { Text("Quantity to add") }, singleLine = true, isError = q == null)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && q != null, onClick = {
                vm.confirmNewProduct(prompt.barcode, name.trim(), unit, location, packSize.ifBlank { null }, shelf.toIntOrNull(), q!!)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = vm::dismissNewProduct) { Text("Cancel") } },
    )
}

@Composable
fun ReceiptScreen(vm: PantryViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var capturing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try { vm.onReceiptText(recognizeFromUri(context, uri)) } catch (e: Exception) { vm.snackbar = e.message ?: "Could not read that image" }
        }
    }
    val draft = vm.receiptDraft
    when {
        draft != null -> ReceiptReview(draft, vm)
        capturing -> CameraPermissionGate {
            ReceiptCaptureView(
                onResult = { capturing = false; vm.onReceiptText(it) },
                onError = { capturing = false; vm.snackbar = it },
            )
        }
        else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Photograph a grocery receipt on a flat surface in good light. You review every line before anything is saved.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = { capturing = true }, modifier = Modifier.padding(top = 16.dp)) { Text("Scan receipt") }
            OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.padding(top = 8.dp)) { Text("Use a photo or screenshot") }
        }
    }
}

private class LineState(val line: DraftLine, initialProduct: String?) {
    var include by mutableStateOf(true)
    var productId by mutableStateOf(initialProduct)
    var newName by mutableStateOf(line.name.lowercase().replaceFirstChar { it.uppercase() })
    var qty by mutableStateOf(line.qty.toDecimalString(3))
    var price by mutableStateOf(line.price?.toDecimalString(2) ?: "")
    val qtyValue get() = Rational.parseOrNull(qty)?.takeIf { it.isPositive }
    val priceValue get() = if (price.isBlank()) null else Rational.parseOrNull(price)
    val valid get() = !include || (qtyValue != null && (price.isBlank() || priceValue != null))
}

@Composable
private fun ReceiptReview(draft: ReceiptDraft, vm: PantryViewModel) {
    val states = remember(draft) {
        draft.lines.map { l ->
            val best = vm.suggestionsFor(l.name).firstOrNull()
            LineState(l, best?.product?.id)
        }
    }
    var store by remember(draft) { mutableStateOf(draft.store ?: "") }
    var dateText by remember(draft) { mutableStateOf((draft.date ?: LocalDate.now()).toString()) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val selected = states.count { it.include }
    val allValid = date != null && states.all { it.valid } && selected > 0

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(store, { store = it }, label = { Text("Store") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(dateText, { dateText = it }, label = { Text("Date") }, singleLine = true, isError = date == null, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(states) { _, st -> ReceiptLineCard(st, vm) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.receiptDraft = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                enabled = allValid,
                modifier = Modifier.weight(2f),
                onClick = {
                    vm.confirmReceipt(
                        store.ifBlank { null }, date!!, draft.rawText,
                        states.map { ReceiptChoice(it.line, it.include, it.productId, it.newName, it.qtyValue ?: Rational.ONE, it.priceValue) },
                    )
                },
            ) { Text("Add $selected items") }
        }
    }
}

@Composable
private fun ReceiptLineCard(st: LineState, vm: PantryViewModel) {
    var menu by remember { mutableStateOf(false) }
    val chosen = vm.products.firstOrNull { it.id == st.productId }
    val suggestions = remember(st) { vm.suggestionsFor(st.line.name).map { it.product } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = st.include, onCheckedChange = { st.include = it })
                Text(st.line.raw, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            if (st.include) {
                Box {
                    TextButton(onClick = { menu = true }) { Text(if (chosen != null) "Product: ${chosen.name}" else "New product: ${st.newName}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        suggestions.forEach { p -> DropdownMenuItem(text = { Text("Likely: ${p.name}") }, onClick = { st.productId = p.id; menu = false }) }
                        DropdownMenuItem(text = { Text("Create a new product") }, onClick = { st.productId = null; menu = false })
                        vm.products.sortedBy { it.name.lowercase() }.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { st.productId = p.id; menu = false })
                        }
                    }
                }
                if (chosen == null) OutlinedTextField(st.newName, { st.newName = it }, label = { Text("Name for the new product") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        st.qty, { st.qty = it }, singleLine = true, modifier = Modifier.weight(1f), isError = st.qtyValue == null,
                        label = { Text("Quantity" + (st.line.unit?.let { " ($it)" } ?: "")) },
                    )
                    OutlinedTextField(
                        st.price, { st.price = it }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = st.price.isNotBlank() && st.priceValue == null, label = { Text("Price") },
                    )
                }
            }
        }
    }
}
