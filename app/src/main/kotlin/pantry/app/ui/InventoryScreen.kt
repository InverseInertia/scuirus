package pantry.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pantry.core.InventoryItem
import pantry.core.Lot
import pantry.core.Product
import pantry.core.ProductStats
import pantry.core.Rational
import pantry.core.pretty
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class Filter(val label: String) { ALL("All"), FRIDGE("Fridge"), FREEZER("Freezer"), PANTRY("Pantry"), EXPIRING("Expiring") }

private sealed interface RemoveTarget {
    data class OfProduct(val productId: String, val name: String) : RemoveTarget
    data class OfLot(val lotId: String, val name: String) : RemoveTarget
}

@Composable
fun InventoryScreen(vm: PantryViewModel) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var customRemove by remember { mutableStateOf<RemoveTarget?>(null) }
    var editing by remember { mutableStateOf<Lot?>(null) }

    val today = LocalDate.now()
    val shown = vm.items.filter { item ->
        (query.isBlank() || item.product.name.contains(query, ignoreCase = true)) && when (filter) {
            Filter.ALL -> true
            Filter.FRIDGE -> item.lots.any { (it.location ?: item.product.location) == "fridge" }
            Filter.FREEZER -> item.lots.any { (it.location ?: item.product.location) == "freezer" }
            Filter.PANTRY -> item.lots.any { (it.location ?: item.product.location) == "pantry" }
            Filter.EXPIRING -> item.nearestExpiry?.let { !it.isAfter(today.plusDays(3)) } ?: false
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add stock") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.tossMode, onCheckedChange = { vm.tossMode = it })
                Text(
                    if (vm.tossMode) "Removals count as thrown away" else "Removals count as used",
                    modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shown.isEmpty()) item { Text(if (vm.items.isEmpty()) "Nothing in stock yet. Scan something or tap +." else "No matches.") }
                items(shown, key = { it.product.id }) { item ->
                    ProductCard(
                        item = item, stats = vm.stats[item.product.id], expanded = expanded == item.product.id, today = today,
                        onToggle = { expanded = if (expanded == item.product.id) null else item.product.id },
                        onRemoveProduct = { q -> vm.removeFromProduct(item.product.id, q) },
                        onCustomProduct = { customRemove = RemoveTarget.OfProduct(item.product.id, item.product.name) },
                        onRemoveLot = { lot, q -> vm.removeFromLot(lot.id, q) },
                        onCustomLot = { lot -> customRemove = RemoveTarget.OfLot(lot.id, item.product.name) },
                        onEditLot = { editing = it },
                    )
                }
            }
        }
    }

    customRemove?.let { target ->
        val name = when (target) { is RemoveTarget.OfProduct -> target.name; is RemoveTarget.OfLot -> target.name }
        QuantityDialog(
            title = "Remove from $name", hint = "Amount, such as 1/3, 0.5 or 2",
            onDismiss = { customRemove = null },
            onConfirm = { q ->
                when (target) {
                    is RemoveTarget.OfProduct -> vm.removeFromProduct(target.productId, q)
                    is RemoveTarget.OfLot -> vm.removeFromLot(target.lotId, q)
                }
                customRemove = null
            },
        )
    }
    editing?.let { lot ->
        EditLotDialog(lot, onDismiss = { editing = null }, onVoid = { vm.voidLot(lot.id); editing = null }) { rem, exp, loc, price ->
            vm.adjustLot(lot.id, rem, exp, loc, price)
            editing = null
        }
    }
    if (showAdd) AddStockDialog(vm.products, onDismiss = { showAdd = false }, vm = vm)
}

@Composable
private fun ProductCard(
    item: InventoryItem, stats: ProductStats?, expanded: Boolean, today: LocalDate,
    onToggle: () -> Unit,
    onRemoveProduct: (Rational?) -> Unit, onCustomProduct: () -> Unit,
    onRemoveLot: (Lot, Rational?) -> Unit, onCustomLot: (Lot) -> Unit, onEditLot: (Lot) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.product.name, style = MaterialTheme.typography.titleMedium)
                    item.nearestExpiry?.let { Text(expiryText(it, today), color = expiryColor(it, today), style = MaterialTheme.typography.bodySmall) }
                }
                Text("${item.remaining.pretty()} ${item.product.unit}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onToggle) { Text(if (expanded) "Less" else "More") }
            }
            QuickButtons(onRemove = onRemoveProduct, onCustom = onCustomProduct)

            if (expanded) {
                stats?.let { Text(statsLine(it, item.product), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                item.lots.forEach { lot ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${lot.remaining.pretty()} of ${lot.initialQty.pretty()}" +
                                    (lot.location?.let { " \u00B7 $it" } ?: "") +
                                    (lot.expires?.let { " \u00B7 exp $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            lot.price?.let { Text("$" + it.toDecimalString(2), style = MaterialTheme.typography.bodyMedium) }
                        }
                        QuickButtons(onRemove = { onRemoveLot(lot, it) }, onCustom = { onCustomLot(lot) }, extra = {
                            AssistChip(onClick = { onEditLot(lot) }, label = { Text("Edit") })
                        })
                    }
                }
            }
        }
    }
}

/** The one-tap removal row. A null amount means "all of it". */
@Composable
private fun QuickButtons(onRemove: (Rational?) -> Unit, onCustom: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { onRemove(Rational.of(1, 4)) }, label = { Text("\u2212\u00BC") })
        AssistChip(onClick = { onRemove(Rational.of(1, 2)) }, label = { Text("\u2212\u00BD") })
        AssistChip(onClick = { onRemove(Rational.ONE) }, label = { Text("\u22121") })
        AssistChip(onClick = { onRemove(null) }, label = { Text("All") })
        AssistChip(onClick = onCustom, label = { Text("Other") })
        extra()
    }
}

private fun expiryText(d: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, d)
    return when {
        days < 0 -> "Expired ${-days} day${if (days == -1L) "" else "s"} ago"
        days == 0L -> "Expires today"
        days == 1L -> "Expires tomorrow"
        else -> "Expires in $days days"
    }
}

private fun expiryColor(d: LocalDate, today: LocalDate): Color {
    val days = ChronoUnit.DAYS.between(today, d)
    return if (days <= 0) Color(0xFFB3261E) else if (days <= 3) Color(0xFF9A6700) else Color.Unspecified
}

private fun statsLine(s: ProductStats, p: Product): String {
    val parts = ArrayList<String>()
    s.averageUnitPrice?.let { parts += "avg $" + it.toDecimalString(2) + " per ${p.unit}" }
    s.lastUnitPrice?.let { parts += "last $" + it.toDecimalString(2) }
    s.usagePerDay?.let { parts += "uses %.2f %s/day".format(it, p.unit) }
    s.daysOfStock?.let { parts += "about %.0f days left".format(it) }
    s.averageDaysToUse?.let { parts += "lasts %.1f days".format(it) }
    s.wasteRatio?.let { parts += "%.0f%% thrown away".format(it * 100) }
    return if (parts.isEmpty()) "No history yet" else parts.joinToString(" \u00B7 ")
}

@Composable
fun QuantityDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (Rational) -> Unit) {
    var text by remember { mutableStateOf("") }
    val q = Rational.parseOrNull(text)?.takeIf { it.isPositive }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(enabled = q != null, onClick = { onConfirm(q!!) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditLotDialog(lot: Lot, onDismiss: () -> Unit, onVoid: () -> Unit, onSave: (Rational?, LocalDate?, String?, Rational?) -> Unit) {
    var remaining by remember { mutableStateOf(lot.remaining.toString()) }
    var expires by remember { mutableStateOf(lot.expires?.toString() ?: "") }
    var location by remember { mutableStateOf(lot.location ?: "") }
    var price by remember { mutableStateOf(lot.price?.toDecimalString(2) ?: "") }
    val rem = Rational.parseOrNull(remaining)?.takeIf { !it.isNegative }
    val exp = runCatching { LocalDate.parse(expires) }.getOrNull()
    val okExpires = expires.isBlank() || exp != null
    val pr = if (price.isBlank()) null else Rational.parseOrNull(price)
    val okPrice = price.isBlank() || pr != null
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit lot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(remaining, { remaining = it }, label = { Text("Remaining") }, singleLine = true, isError = rem == null)
                OutlinedTextField(expires, { expires = it }, label = { Text("Expires (yyyy-mm-dd)") }, singleLine = true, isError = !okExpires)
                OutlinedTextField(location, { location = it }, label = { Text("Location (fridge, freezer, pantry)") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Price paid") }, singleLine = true, isError = !okPrice)
                TextButton(onClick = onVoid) { Text("This lot was a mistake, remove it from records") }
            }
        },
        confirmButton = {
            TextButton(enabled = rem != null && okExpires && okPrice, onClick = {
                onSave(rem, exp, location.takeIf { it.isNotBlank() && it != lot.location }, pr)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddStockDialog(products: List<Product>, onDismiss: () -> Unit, vm: PantryViewModel) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("each") }
    var location by remember { mutableStateOf("pantry") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }
    var shelf by remember { mutableStateOf("") }

    val match = products.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    val suggestions = if (name.isBlank() || match != null) emptyList() else products.filter { it.name.contains(name, ignoreCase = true) }.take(4)
    val q = Rational.parseOrNull(qty)?.takeIf { it.isPositive }
    val exp = runCatching { LocalDate.parse(expires) }.getOrNull()
    val pr = if (price.isBlank()) null else Rational.parseOrNull(price)
    val valid = name.isNotBlank() && q != null && (expires.isBlank() || exp != null) && (price.isBlank() || pr != null)

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add stock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Product") }, singleLine = true)
                if (suggestions.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { p -> AssistChip(onClick = { name = p.name }, label = { Text(p.name) }) }
                    }
                }
                OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true, isError = q == null)
                if (match == null) {
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit (carton, lb, each)") }, singleLine = true)
                    OutlinedTextField(location, { location = it }, label = { Text("Usually kept in") }, singleLine = true)
                    OutlinedTextField(shelf, { shelf = it }, label = { Text("Shelf life in days (optional)") }, singleLine = true)
                }
                OutlinedTextField(price, { price = it }, label = { Text("Price paid (optional)") }, singleLine = true)
                OutlinedTextField(expires, { expires = it }, label = { Text("Expires yyyy-mm-dd (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (match != null) vm.addManual(match.id, q!!, pr, exp, null)
                else vm.createProductAndAdd(name.trim(), unit, location, shelf.toIntOrNull(), q!!, pr, exp)
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
