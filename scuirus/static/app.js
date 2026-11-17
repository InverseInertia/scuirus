"use strict";

// ---------- helpers ----------

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (v === true) el.setAttribute(k, "");
    else el.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

async function api(path, opts = {}) {
  const init = { ...opts };
  if (opts.json !== undefined) {
    init.method = init.method || "POST";
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(opts.json);
  }
  const r = await fetch(path, init);
  const body = r.headers.get("content-type")?.includes("json") ? await r.json() : null;
  if (!r.ok) {
    let msg = body?.detail ?? `Request failed (${r.status})`;
    if (Array.isArray(msg)) msg = msg.map(d => d.msg).join("; ");
    throw new Error(msg);
  }
  return body;
}

let toastTimer;
function toast(msg, bad = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "show" + (bad ? " bad" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.className = ""), bad ? 5000 : 2500);
}

function todayISO() {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function daysUntil(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  const target = new Date(y, m - 1, d);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((target - today) / 86400000);
}

function expiryBadge(iso) {
  if (!iso) return null;
  const n = daysUntil(iso);
  const text = n < 0 ? `expired ${-n}d ago` : n === 0 ? "expires today" : n === 1 ? "tomorrow" : `${n} days`;
  return h("span", { class: "badge" + (n < 0 ? " expired" : n <= 3 ? " soon" : ""), title: iso }, text);
}

function money(v) { return v == null ? "" : `$${v.toFixed(2)}`; }

function formError(form, msg) {
  const p = $(".error", form);
  p.textContent = msg || "";
  p.hidden = !msg;
}

const state = { inventory: [], products: [], status: { locations: ["pantry", "fridge", "freezer"] }, view: "stock" };

// ---------- navigation ----------

const titles = { stock: "Stock", scan: "Scan", receipts: "Receipts", history: "History" };

function show(view) {
  state.view = view;
  for (const s of $$(".view")) s.hidden = s.id !== `view-${view}`;
  for (const b of $$(".tabs button")) b.classList.toggle("on", b.dataset.view === view);
  $("#title").textContent = titles[view];
  $("#add-btn").hidden = view !== "stock";
  if (view !== "scan") stopCamera();
  if (view === "stock") loadStock();
  if (view === "receipts") loadReceipts();
  if (view === "history") loadHistory();
  try { localStorage.setItem("scuirus.view", view); } catch {}
}

$$(".tabs button").forEach(b => b.addEventListener("click", () => show(b.dataset.view)));

// ---------- stock ----------

async function loadProducts() {
  state.products = await api("/api/products");
  return state.products;
}

async function loadStock() {
  const [inv] = await Promise.all([api("/api/inventory"), loadProducts()]);
  state.inventory = inv;
  renderStock();
}

function renderStock() {
  const q = $("#search").value.trim().toLowerCase();
  const loc = $("#loc-filter").value;
  const list = $("#stock-list");
  const open = new Set($$(".card.open", list).map(c => c.dataset.id));
  list.replaceChildren();
  const items = state.inventory.filter(i =>
    (!q || i.product.name.toLowerCase().includes(q)) &&
    (!loc || i.lots.some(l => l.location === loc)));
  for (const item of items) list.append(stockCard(item, open.has(item.product.id)));
  $("#stock-empty").hidden = state.inventory.length > 0;
}

function stockCard(item, isOpen) {
  const p = item.product;
  const locs = [...new Set(item.lots.map(l => l.location).filter(Boolean))].join(", ");
  const card = h("li", { class: "card" + (isOpen ? " open" : ""), "data-id": p.id });
  const lots = h("div", { class: "lots", hidden: !isOpen });
  const head = h("div", { class: "card-head" },
    h("div", { class: "grow", onclick: () => { card.classList.toggle("open"); lots.hidden = !card.classList.contains("open"); } },
      h("div", { class: "name" }, p.name),
      h("div", { class: "sub" }, `${item.remaining.display} ${p.unit}`, locs ? ` · ${locs}` : "", " ", expiryBadge(item.nearest_expiry))),
    h("button", { "aria-label": `Use ${p.default_use.display} ${p.name}`, onclick: () => useProduct(p, p.default_use.exact) },
      `−${p.default_use.display}`));
  for (const l of item.lots) lots.append(lotRow(p, l));
  const s = item.stats;
  if (s) {
    const bits = [];
    if (s.average_unit_price != null) bits.push(`avg ${money(s.average_unit_price)}/${p.unit}`);
    if (s.days_of_stock != null) bits.push(`~${Math.round(s.days_of_stock)} days of stock`);
    if (s.waste_ratio != null && s.waste_ratio > 0) bits.push(`${Math.round(s.waste_ratio * 100)}% thrown out`);
    if (bits.length) lots.append(h("div", { class: "stats" }, bits.join(" · ")));
  }
  lots.append(h("div", { class: "row" },
    h("button", { onclick: () => openAddDialog(p.id) }, "Add more"),
    h("button", { onclick: () => openProductDialog(p) }, "Edit product")));
  card.append(head, lots);
  return card;
}

function lotRow(p, l) {
  const meta = [`${l.remaining.display} of ${l.initial.display} ${p.unit}`];
  if (l.location) meta.push(l.location);
  if (l.bought) meta.push(`bought ${l.bought}`);
  if (l.price != null) meta.push(money(l.price));
  const use = (qty, discard = false) => async () => {
    try {
      await api(`/api/lots/${l.id}/use`, { json: { qty, discard } });
      toast(discard ? `Tossed ${p.name}` : `Used ${p.name}`);
      loadStock();
    } catch (e) { toast(e.message, true); }
  };
  const quarter = l.remaining.value > 0.25 ? h("button", { onclick: use("1/4") }, "−¼") : null;
  const one = l.remaining.value > 1 ? h("button", { onclick: use("1") }, "−1") : null;
  return h("div", { class: "lot" },
    h("div", { class: "sub" }, meta.join(" · "), " ", expiryBadge(l.expires)),
    h("div", { class: "actions" },
      quarter, one,
      h("button", { onclick: use(l.remaining.exact) }, "Used up"),
      h("button", { class: "danger", onclick: use(l.remaining.exact, true) }, "Toss"),
      h("button", { onclick: () => openLotDialog(l) }, "Edit")));
}

async function useProduct(p, qty) {
  try {
    const r = await api(`/api/products/${p.id}/use`, { json: { qty } });
    toast(r.removed.value > 0 ? `Used ${r.removed.display} ${p.unit} ${p.name} · ${r.remaining.display} left` : `${p.name} is out of stock`, r.removed.value === 0);
    loadStock();
  } catch (e) { toast(e.message, true); }
}

$("#search").addEventListener("input", renderStock);
$("#loc-filter").addEventListener("change", renderStock);
$("#add-btn").addEventListener("click", () => openAddDialog());

// ---------- dialogs ----------

function fillLocations(select, withDefault) {
  select.replaceChildren(...(withDefault ? [h("option", { value: "" }, "Default")] : []),
    ...state.status.locations.map(l => h("option", { value: l }, l)));
}

function openProductDialog(p = {}, onSaved) {
  const d = $("#product-dialog");
  const f = $("#product-form");
  f.reset();
  formError(f);
  fillLocations(f.location, false);
  f.id.value = p.id || "";
  f.name.value = p.name || "";
  f.barcode.value = p.barcode || "";
  f.unit.value = p.unit || "each";
  f.location.value = p.location || "pantry";
  f.pack_size.value = p.pack_size || "";
  f.default_use.value = p.default_use?.exact || "1";
  f.category.value = p.category || "";
  f.shelf_life_days.value = p.shelf_life_days || "";
  d.onSaved = onSaved;
  d.showModal();
  f.name.focus();
}

$("#product-form").addEventListener("submit", async ev => {
  const f = ev.target;
  if (ev.submitter?.value !== "save") return;
  ev.preventDefault();
  const body = {
    name: f.name.value, barcode: f.barcode.value || null, unit: f.unit.value || "each",
    location: f.location.value, pack_size: f.pack_size.value || null, default_use: f.default_use.value || "1",
    category: f.category.value || null, shelf_life_days: f.shelf_life_days.value ? Number(f.shelf_life_days.value) : null,
  };
  try {
    const p = f.id.value
      ? await api(`/api/products/${f.id.value}`, { method: "PUT", json: body })
      : await api("/api/products", { json: body });
    const d = $("#product-dialog");
    d.close();
    toast(`Saved ${p.name}`);
    await loadProducts();
    if (d.onSaved) d.onSaved(p);
    else if (state.view === "stock") loadStock();
  } catch (e) { formError(f, e.message); }
});

async function openAddDialog(productId) {
  const d = $("#add-dialog");
  const f = $("#add-form");
  f.reset();
  formError(f);
  if (!state.products.length) await loadProducts();
  f.product_id.replaceChildren(h("option", { value: "" }, "Choose…"),
    ...state.products.map(p => h("option", { value: p.id }, p.name)));
  f.product_id.value = productId || "";
  fillLocations(f.location, true);
  d.showModal();
}

$("#add-new-product").addEventListener("click", () => {
  $("#add-dialog").close();
  openProductDialog({}, p => openAddDialog(p.id));
});

$("#add-form").addEventListener("submit", async ev => {
  const f = ev.target;
  if (ev.submitter?.value !== "save") return;
  ev.preventDefault();
  try {
    await api("/api/lots", { json: {
      product_id: f.product_id.value, qty: f.qty.value || "1", price: f.price.value || null,
      expires: f.expires.value || null, location: f.location.value || null,
    } });
    $("#add-dialog").close();
    toast("Added");
    loadStock();
  } catch (e) { formError(f, e.message); }
});

function openLotDialog(l) {
  const f = $("#lot-form");
  f.reset();
  formError(f);
  fillLocations(f.location, false);
  f.id.value = l.id;
  f.remaining.value = l.remaining.exact;
  f.expires.value = l.expires || "";
  f.location.value = l.location || "pantry";
  f.price.value = l.price ?? "";
  $("#lot-dialog").showModal();
}

$("#lot-form").addEventListener("submit", async ev => {
  const f = ev.target;
  if (ev.submitter?.value !== "save") return;
  ev.preventDefault();
  try {
    await api(`/api/lots/${f.id.value}/adjust`, { json: {
      remaining: f.remaining.value, expires: f.expires.value || null, location: f.location.value, price: f.price.value || null,
    } });
    $("#lot-dialog").close();
    loadStock();
  } catch (e) { formError(f, e.message); }
});

$("#void-lot").addEventListener("click", async () => {
  const f = $("#lot-form");
  if (!confirm("Remove this lot as if it was never bought?")) return;
  try {
    await api(`/api/lots/${f.id.value}/void`, { method: "POST" });
    $("#lot-dialog").close();
    loadStock();
  } catch (e) { formError(f, e.message); }
});

// ---------- scanning ----------

let scanMode = "use";
let stream = null;
let scanTimer = null;
let decoding = false;
let lastCode = { code: null, at: 0 };

$$(".segmented button").forEach(b => b.addEventListener("click", () => {
  scanMode = b.dataset.mode;
  lastCode = { code: null, at: 0 };
  $$(".segmented button").forEach(x => {
    x.classList.toggle("on", x === b);
    x.setAttribute("aria-checked", String(x === b));
  });
}));

async function startCamera(keepRecent = false) {
  if (!keepRecent) lastCode = { code: null, at: 0 };
  const msg = $("#camera-msg");
  if (!navigator.mediaDevices?.getUserMedia) {
    msg.textContent = window.isSecureContext ? "This browser has no camera access" : "Live camera needs HTTPS. Use Photo instead.";
    return;
  }
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false,
    });
  } catch (e) {
    msg.textContent = `Camera unavailable: ${e.message}`;
    return;
  }
  const v = $("#video");
  v.srcObject = stream;
  await v.play();
  msg.textContent = "Point at a barcode";
  $("#cam-toggle").textContent = "Stop camera";
  scanTimer = setInterval(grabFrame, 350);
}

function stopCamera() {
  clearInterval(scanTimer);
  scanTimer = null;
  if (stream) stream.getTracks().forEach(t => t.stop());
  stream = null;
  $("#video").srcObject = null;
  $("#camera-msg").textContent = "Camera is off";
  $("#cam-toggle").textContent = "Start camera";
}

const frameCanvas = document.createElement("canvas");

async function grabFrame() {
  const v = $("#video");
  if (decoding || !stream || v.readyState < 2) return;
  // Send the middle band where the aim box is; it is smaller and keeps stray codes out.
  const sw = v.videoWidth, sh = v.videoHeight;
  const cw = Math.round(sw * 0.8), ch = Math.round(sh * 0.5);
  frameCanvas.width = cw;
  frameCanvas.height = ch;
  frameCanvas.getContext("2d").drawImage(v, (sw - cw) / 2, (sh - ch) / 2, cw, ch, 0, 0, cw, ch);
  decoding = true;
  try {
    const blob = await new Promise(r => frameCanvas.toBlob(r, "image/jpeg", 0.85));
    const codes = await decodeBlob(blob);
    if (codes.length) {
      const code = codes[0].code;
      const now = Date.now();
      if (code !== lastCode.code || now - lastCode.at > 3000) {
        lastCode = { code, at: now };
        if (navigator.vibrate) navigator.vibrate(60);
        await handleCode(code);
      }
      lastCode.at = now;  // holding the code in view does not re-trigger
    }
  } catch (e) {
    $("#camera-msg").textContent = e.message;
  } finally {
    decoding = false;
  }
}

async function decodeBlob(blob) {
  const fd = new FormData();
  fd.append("image", blob, "frame.jpg");
  const r = await api("/api/barcode", { method: "POST", body: fd });
  return r.codes;
}

$("#cam-toggle").addEventListener("click", () => (stream ? stopCamera() : startCamera()));

$("#scan-photo").addEventListener("change", async ev => {
  const file = ev.target.files[0];
  ev.target.value = "";
  if (!file) return;
  try {
    const codes = await decodeBlob(file);
    if (!codes.length) return toast("No barcode found in that photo", true);
    await handleCode(codes[0].code);
  } catch (e) { toast(e.message, true); }
});

$("#manual-code").addEventListener("submit", async ev => {
  ev.preventDefault();
  const code = ev.target.code.value.trim();
  if (!code) return;
  ev.target.code.value = "";
  await handleCode(code);
});

function logScan(text, ok = true) {
  const time = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  $("#scan-log").prepend(h("li", {}, h("span", { class: ok ? "ok" : "bad" }, text), h("span", { class: "when" }, time)));
}

async function handleCode(code) {
  try {
    const r = await api("/api/scan", { json: { barcode: code, mode: scanMode } });
    if (r.status === "added") {
      const msg = `Added ${r.qty.display} ${r.product.name} · ${r.remaining.display} ${r.product.unit} now`;
      toast(msg); logScan(msg);
    } else if (r.status === "used") {
      const msg = `Used ${r.qty.display} ${r.product.name} · ${r.remaining.display} ${r.product.unit} left`;
      toast(msg); logScan(msg);
    } else if (r.status === "out_of_stock") {
      const msg = `${r.product.name}: none in stock`;
      toast(msg, true); logScan(msg, false);
    } else if (r.status === "unknown") {
      await newProductForCode(code);
    }
  } catch (e) { toast(e.message, true); logScan(e.message, false); }
}

async function newProductForCode(code) {
  const wasRunning = !!stream;
  stopCamera();
  toast("New barcode, looking it up…");
  let guess = {};
  try {
    const r = await api(`/api/lookup/${encodeURIComponent(code)}`);
    guess = r.suggestion || {};
  } catch {}
  openProductDialog({ barcode: code, name: guess.name, pack_size: guess.pack_size, category: guess.category }, async () => {
    await handleCode(code);
    lastCode = { code, at: Date.now() };  // the code is likely still in view; don't add it a second time
    if (wasRunning) startCamera(true);
  });
}

// ---------- receipts ----------

let receiptPoll = null;

async function loadReceipts() {
  const list = await api("/api/receipts");
  const ul = $("#receipt-list");
  ul.replaceChildren(...list.map(receiptCard));
  if (!list.length) ul.append(h("p", { class: "empty" }, "No receipts yet."));
  clearTimeout(receiptPoll);
  if (list.some(r => r.status === "processing") && state.view === "receipts") receiptPoll = setTimeout(loadReceipts, 3000);
}

const statusText = { processing: "Reading…", ready: "Ready to review", error: "Failed", committed: "Added to stock" };

function receiptCard(r) {
  const title = r.store || "Receipt";
  const sub = [r.date, r.items ? `${r.items} items` : null, r.total != null ? money(r.total) : null].filter(Boolean).join(" · ");
  return h("li", { class: "card" },
    h("div", { class: "card-head" },
      h("img", { src: `/receipt-images/${r.images[0]}`, alt: "", style: "width:48px;height:64px;object-fit:cover;border-radius:6px" }),
      h("div", { class: "grow" },
        h("div", { class: "name" }, title),
        h("div", { class: "sub" }, sub),
        h("div", { class: `status ${r.status}` }, statusText[r.status] || r.status),
        r.error ? h("div", { class: "error" }, r.error) : null),
      r.status === "ready" ? h("button", { class: "primary", onclick: () => openReceipt(r.id) }, "Review") : null,
      r.status === "committed" ? h("button", { onclick: () => openReceipt(r.id) }, "View") : null,
      r.status === "error" ? h("button", { onclick: () => retryReceipt(r.id) }, "Retry") : null));
}

async function retryReceipt(id) {
  await api(`/api/receipts/${id}/retry`, { method: "POST" });
  loadReceipts();
}

$("#receipt-files").addEventListener("change", async ev => {
  const files = [...ev.target.files];
  ev.target.value = "";
  if (!files.length) return;
  const fd = new FormData();
  files.forEach(f => fd.append("files", f, f.name));
  fd.append("one_receipt", $("#one-receipt").checked ? "true" : "false");
  toast(`Uploading ${files.length} photo${files.length > 1 ? "s" : ""}…`);
  try {
    const r = await api("/api/receipts", { method: "POST", body: fd });
    const dup = r.skipped.filter(s => s.receipt).length;
    const bad = r.skipped.filter(s => s.error).length;
    const parts = [`${r.created.length} receipt${r.created.length === 1 ? "" : "s"} queued`];
    if (dup) parts.push(`${dup} already imported`);
    if (bad) parts.push(`${bad} unreadable`);
    toast(parts.join(" · "), bad > 0);
    $("#one-receipt").checked = false;
    loadReceipts();
  } catch (e) { toast(e.message, true); }
});

function guessLocation(item) {
  const c = (item.category || "").toLowerCase();
  if (["dairy", "meat", "seafood", "produce"].includes(c)) return "fridge";
  if (c === "frozen") return "freezer";
  return "pantry";
}

async function openReceipt(id) {
  const r = await api(`/api/receipts/${id}`);
  if (!state.products.length) await loadProducts();
  const done = r.status === "committed";
  const ex = r.extraction || {};
  $("#receipt-title").textContent = ex.store || "Receipt";
  const body = $("#receipt-body");
  body.replaceChildren();

  body.append(h("div", { class: "thumbs" }, r.images.map(src =>
    h("img", { src: `/receipt-images/${src}`, alt: "Receipt photo", onclick: () => {
      $("#image-full").src = `/receipt-images/${src}`;
      $("#image-dialog").showModal();
    } }))));
  for (const c of r.checks || []) body.append(h("div", { class: "notice" }, c));
  if (done) body.append(h("div", { class: "notice" }, "These items were already added to stock."));

  const dateInput = h("input", { type: "date", value: ex.date || todayISO(), disabled: done });
  body.append(h("label", {}, "Purchase date", dateInput));
  const summary = [ex.subtotal != null ? `subtotal ${money(ex.subtotal)}` : null, ex.tax != null ? `tax ${money(ex.tax)}` : null,
    ex.total != null ? `total ${money(ex.total)}` : null].filter(Boolean).join(" · ");
  if (summary) body.append(h("p", { class: "sub" }, summary));

  const rows = r.lines.map(line => receiptLine(line, done));
  body.append(...rows.map(x => x.el));

  if (!done) {
    const count = () => rows.filter(x => x.included()).length;
    const btn = h("button", { class: "primary big" });
    const refresh = () => (btn.textContent = `Add ${count()} item${count() === 1 ? "" : "s"} to stock`);
    rows.forEach(x => x.onToggle(refresh));
    refresh();
    btn.addEventListener("click", async () => {
      const lines = [];
      for (const x of rows) {
        if (!x.included()) continue;
        const v = x.value();
        if (v.error) return toast(v.error, true);
        lines.push(v);
      }
      if (!lines.length) return toast("Nothing selected", true);
      btn.disabled = true;
      try {
        await api(`/api/receipts/${id}/commit`, { json: { date: dateInput.value || null, lines } });
        toast(`Added ${lines.length} items`);
        $("#receipt-dialog").close();
        loadReceipts();
        loadProducts();
      } catch (e) { toast(e.message, true); btn.disabled = false; }
    });
    const del = h("button", { class: "danger", onclick: async () => {
      if (!confirm("Delete this receipt? Its photos can then be imported again.")) return;
      await api(`/api/receipts/${id}`, { method: "DELETE" });
      $("#receipt-dialog").close();
      loadReceipts();
    } }, "Delete receipt");
    body.append(h("div", { class: "commit-bar" }, btn), h("div", { class: "row end" }, del));
  }
  $("#receipt-dialog").showModal();
}

function receiptLine(line, done) {
  const include = h("input", { type: "checkbox", checked: line.is_food, disabled: done, "aria-label": `Include ${line.name}` });
  const priceText = line.line_total != null ? money(line.line_total) : "price?";
  const el = h("div", { class: "rline" + (line.is_food ? "" : " off") });
  const head = h("div", { class: "rline-head" }, include,
    h("div", { class: "grow" },
      h("div", { class: "name" }, line.name, " ", h("span", { class: "qty" }, priceText)),
      h("div", { class: "raw" }, line.raw_text, line.discount ? ` (−${money(line.discount)} off)` : "")));

  const select = h("select", { disabled: done, "aria-label": `Product for ${line.name}` });
  const suggested = line.suggestions.map(s => s.product.id);
  if (suggested.length) {
    select.append(h("optgroup", { label: line.suggestions[0].alias ? "Matched before" : "Suggested" },
      line.suggestions.map(s => h("option", { value: s.product.id }, s.product.name))));
  }
  select.append(h("option", { value: "__new" }, `New product: ${line.name}`));
  const others = state.products.filter(p => !suggested.includes(p.id));
  if (others.length) select.append(h("optgroup", { label: "All products" }, others.map(p => h("option", { value: p.id }, p.name))));
  select.value = suggested[0] || "__new";

  const newName = h("input", { class: "new-name", value: line.name, "aria-label": "New product name" });
  const newRow = h("div", {}, newName);
  const unitText = line.unit && line.unit !== "each" ? line.unit : "";
  const qty = h("input", { value: String(line.quantity ?? 1), inputmode: "decimal", disabled: done, "aria-label": "Quantity" });
  const price = h("input", { value: line.line_total ?? "", inputmode: "decimal", disabled: done, "aria-label": "Price" });
  const expires = h("input", { type: "date", disabled: done, "aria-label": "Expires" });
  const sync = () => (newRow.hidden = select.value !== "__new");
  select.addEventListener("change", sync);
  sync();

  el.append(head, select, newRow, h("div", { class: "grid3" },
    h("label", {}, `Qty${unitText ? ` (${unitText})` : ""}`, qty),
    h("label", {}, "Paid", price),
    h("label", {}, "Expires", expires)));

  let toggleCb = () => {};
  include.addEventListener("change", () => { el.classList.toggle("off", !include.checked); toggleCb(); });

  return {
    el,
    included: () => include.checked,
    onToggle: cb => (toggleCb = cb),
    value: () => {
      const v = { index: line.index, raw_text: line.raw_text, qty: qty.value.trim(), price: price.value.trim() || null,
        expires: expires.value || null };
      if (!v.qty) return { error: `Quantity missing for ${line.name}` };
      if (select.value === "__new") {
        if (!newName.value.trim()) return { error: "New product needs a name" };
        v.new_product = { name: newName.value.trim(), unit: line.unit && line.unit !== "each" ? line.unit : "each",
          location: guessLocation(line), category: line.category };
      } else {
        v.product_id = select.value;
      }
      return v;
    },
  };
}

$("#receipt-close").addEventListener("click", () => $("#receipt-dialog").close());
$(".close-img").addEventListener("click", () => $("#image-dialog").close());

// ---------- history ----------

const verbs = { add: "Added", consume: "Used", discard: "Tossed", adjust: "Adjusted", void: "Voided" };

async function loadHistory() {
  const events = await api("/api/history?limit=200");
  $("#history-list").replaceChildren(...events.map(e => {
    const when = new Date(e.ts).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
    const what = [verbs[e.type] || e.type, e.qty ? e.qty.display : null, e.qty && e.unit && e.unit !== "each" ? e.unit : null, e.name || e.product].filter(Boolean).join(" ");
    const src = e.source && e.source !== "manual" ? ` · ${e.source.split(":")[0]}` : "";
    return h("li", {}, h("span", {}, what, h("span", { class: "sub" }, src)), h("span", { class: "when" }, when));
  }));
  if (!events.length) $("#history-list").append(h("p", { class: "empty" }, "No history yet."));
}

// ---------- start ----------

async function init() {
  try {
    state.status = await api("/api/status");
  } catch {}
  fillLocations($("#loc-filter"), false);
  $("#loc-filter").prepend(h("option", { value: "" }, "All places"));
  $("#loc-filter").value = "";
  const w = state.status.warnings || [];
  if (w.length) $("#warnings").append(h("div", { class: "notice" }, `${w.length} history problem${w.length > 1 ? "s" : ""}: ${w[0].message}`));
  $("#reader-info").textContent = state.status.reader
    ? `Receipts are read on the server with ${state.status.reader === "claude" ? "Claude" : "a local model (Ollama)"}.`
    : "No receipt reader is configured on the server.";
  let start = "stock";
  try { start = localStorage.getItem("scuirus.view") || "stock"; } catch {}
  show(titles[start] ? start : "stock");
}

init();
