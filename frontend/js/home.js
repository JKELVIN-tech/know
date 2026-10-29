(async function () {
  const session = await initShell("home", ["staff"]);

  let transactions = [];
  let stockItems = [];
  let opening = { cash: 0, mpesa: 0, bank: 0, is_set: false };

  await loadData();
  onFreshData(loadData);
  window.addEventListener("online", handleBackOnline);
  window.addEventListener("offline", refreshOfflineBanner);

  let business = "";
  async function loadBusiness() {
    try {
      const me = await Api.users.me();
      business = me.institution_name && !me.institution_name.includes("(@") ? me.institution_name : me.full_name;
      Offline.cacheSet("business_name", business);
    } catch { business = (await Offline.cacheGet("business_name")) || session.full_name || "KNOW"; }
  }
  loadBusiness();

  async function loadLowStock() {
    const card = $("#lowStockCard");
    if (!card || session.staff_type === "teacher") return;
    try {
      const items = await Api.stock.list();
      const low = items.filter((s) => Number(s.quantity) <= (s.low_stock_threshold != null ? Number(s.low_stock_threshold) : 5)).sort((a, b) => Number(a.quantity) - Number(b.quantity));
      card.hidden = !low.length;
      $("#lowStockList").innerHTML = low.slice(0, 4).map((s) => `<li><span>${escapeHtml(s.name)}</span><strong class="${Number(s.quantity) <= 0 ? "neg" : "warn"}">${Number(s.quantity) <= 0 ? "Out" : Number(s.quantity) + " left"}</strong></li>`).join("")
        + (low.length > 4 ? `<li class="subtle">and ${low.length - 4} more in Stock</li>` : "");
    } catch { card.hidden = true; }
  }

  async function loadData() {
    try {
      transactions = await Api.transactions.list();
      await Offline.cacheSet("transactions", transactions);
      await mergeInPending();
      await loadOpening();
      renderOverview();
      renderRecent();
      loadLowStock();
    } catch (err) {
      if (err.status === 0) {
        // Offline — fall back to the last data cached on this device.
        transactions = (await Offline.cacheGet("transactions")) || [];
        await mergeInPending();
        opening = (await Offline.cacheGet("opening")) || opening;
        renderOverview();
        renderRecent();
      } else {
        $("#recentList").innerHTML = `<li class="empty-state">${escapeHtml(err.message)}</li>`;
      }
    }
    await refreshOfflineBanner();
  }

  async function loadOpening() {
    try {
      opening = await Api.accounting.openingGet();
      await Offline.cacheSet("opening", opening);
    } catch (err) {
      if (err.status === 0) opening = (await Offline.cacheGet("opening")) || opening;
    }
  }

  // Folds queued-but-not-yet-synced entries into the same list real
  // transactions render from, so they show up (marked "Pending sync")
  // immediately rather than only appearing after the next server round trip.
  async function mergeInPending() {
    const pending = await Offline.listPending();
    transactions = [...transactions, ...pending.map(Offline.pendingAsTransaction)];
  }

  async function refreshOfflineBanner() {
    const pending = await Offline.listPending();
    if (!Offline.isOnline()) {
      setOfflineBanner(pending.length
        ? `Offline — ${pending.length} ${pending.length === 1 ? "entry" : "entries"} will sync automatically once you're back online.`
        : "Offline — showing the last data saved to this device.");
    } else if (pending.length) {
      setOfflineBanner(`${pending.length} offline ${pending.length === 1 ? "entry" : "entries"} waiting to sync…`);
    } else {
      setOfflineBanner(null);
    }
  }

  async function handleBackOnline() {
    const result = await Offline.syncPendingTransactions();
    if (result.failed) toast(`${result.failed} offline ${result.failed === 1 ? "entry" : "entries"} couldn't be saved — check Home for details.`);
    await loadData();
  }

  function renderOverview() {
    const income = transactions.filter((t) => t.type === "income").reduce((s, t) => s + parseFloat(t.amount), 0);
    const expense = transactions.filter((t) => t.type === "expense").reduce((s, t) => s + parseFloat(t.amount), 0);
    const net = income - expense;

    $("#kpiIncome").textContent = money(income);
    $("#kpiExpense").textContent = money(expense);
    $("#kpiNet").textContent = money(net);
    $("#totalPill").textContent = `Net · ${money(net)}`;

    // Money held = starting balance + everything received − everything paid, per method (pending entries included)
    const held = (method) => {
      const rows = transactions.filter((t) => t.method === method);
      const inn = rows.filter((t) => t.type === "income").reduce((s, t) => s + parseFloat(t.amount), 0);
      const out = rows.filter((t) => t.type === "expense").reduce((s, t) => s + parseFloat(t.amount), 0);
      return parseFloat(opening[method] || 0) + inn - out;
    };
    $("#moneyCash").textContent = money(held("cash"));
    $("#moneyMpesa").textContent = money(held("mpesa"));
    $("#moneyBank").textContent = money(held("bank"));
    $("#moneyNote").textContent = opening.is_set ? "What you hold right now" : "Add what you already hold to see accurate balances";
    $("#openingBtn").textContent = opening.is_set ? "Edit starting balances" : "Set starting balances";
  }

  $("#openingBtn").addEventListener("click", () => {
    Sheet.open("Starting balances", `
      <p class="form-error" id="openMsg"></p>
      <form id="openForm">
        <p class="file-hint">Enter what the business already holds today, before your first entry here.</p>
        <label class="field"><span>Cash (KES)</span><input type="number" id="openCash" min="0" step="0.01" value="${opening.cash || 0}" /></label>
        <label class="field"><span>M-Pesa (KES)</span><input type="number" id="openMpesa" min="0" step="0.01" value="${opening.mpesa || 0}" /></label>
        <label class="field"><span>Bank (KES)</span><input type="number" id="openBank" min="0" step="0.01" value="${opening.bank || 0}" /></label>
        <div class="form-actions"><button type="submit" class="primary-btn" id="openSubmit">Save starting balances</button></div>
      </form>`);
    $("#openForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#openMsg");
      hideFormMessage(msg);
      const btn = $("#openSubmit");
      btn.disabled = true;
      try {
        opening = await Api.accounting.openingSet({
          cash: parseFloat($("#openCash").value || 0),
          mpesa: parseFloat($("#openMpesa").value || 0),
          bank: parseFloat($("#openBank").value || 0),
        });
        await Offline.cacheSet("opening", opening);
        Sheet.close();
        toast("Starting balances saved.");
        renderOverview();
      } catch (err) {
        showFormMessage(msg, err.status === 0 ? "You need a connection to save starting balances." : (err.message || "Could not save."));
        btn.disabled = false;
      }
    });
  });

  function groupsOfRecent() {
    const sorted = [...transactions].sort((a, b) => new Date(b.transaction_date) - new Date(a.transaction_date));
    const map = new Map();
    for (const t of sorted) {
      const key = t.batch_id || `t${t.id}`;
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(t);
    }
    return [...map.entries()].slice(0, 6);
  }

  function renderRecent() {
    const list = $("#recentList");
    const groups = groupsOfRecent();
    list.innerHTML = groups.length ? groups.map(([key, g]) => {
      const t = g[0];
      const total = g.reduce((a, x) => a + Number(x.amount), 0);
      const title = g.length === 1
        ? `${escapeHtml(t.category)}${t.category_type === "STOCK" && t.quantity ? ` &times; ${t.quantity}` : ""}`
        : `${g.slice(0, 2).map((x) => escapeHtml(x.category)).join(", ")}${g.length > 2 ? ` +${g.length - 2}` : ""}`;
      const sub = g.length === 1 ? escapeHtml(t.description || formatDate(t.transaction_date, true)) : `${g.length} items &middot; ${formatDate(t.transaction_date, true)}`;
      return `
      <li class="txn${t._pending ? (t._pendingFailed ? ' is-failed' : ' is-pending') : ''}">
        <span class="txn-badge ${t.type === "income" ? "in" : "out"}">
          <span class="ico" data-ico="${t.type === "income" ? "in" : "out"}"></span>
        </span>
        <span class="txn-info">
          <strong>${title}</strong>
          <span class="subtle">
            ${t._pendingFailed ? `<span class="badge-pending">Sync failed</span> &middot; ` : ""}
            ${sub} &middot; ${t.method.toUpperCase()}${t.mpesa_code ? ` &middot; ${escapeHtml(t.mpesa_code)}` : ""}
          </span>
        </span>
        ${g.length === 1 && !t._pending && t.image_path ? `<a class="ghost-btn" href="${Api.transactions.imageUrl(t)}" target="_blank" rel="noopener">Photo</a>` : ""}
        <span class="txn-amount ${t.type === "income" ? "in" : "out"}">${t.type === "income" ? "+" : "\u2212"}${money(total)}</span>
        <button type="button" class="rx-menu" data-key="${escapeHtml(key)}" aria-label="Receipt options">&#9776;</button>
      </li>`;
    }).join("") : `<li class="empty-state">No transactions recorded yet.</li>`;

    paintIcons(list);
  }

  $("#recentList").addEventListener("click", (e) => {
    const btn = e.target.closest(".rx-menu");
    if (!btn) return;
    const entry = groupsOfRecent().find(([key]) => key === btn.dataset.key);
    if (!entry) return;
    const g = entry[1];
    const receipt = Receipt.fromTransactions(g, business || session.full_name || "KNOW", session.full_name || "");
    Sheet.open("Entry options", `
      <div class="form-actions rc-actions">
        <button type="button" class="primary-btn" id="optView">View receipt</button>
        <button type="button" class="ghost-btn" id="optPrint">Print receipt</button>
        ${g.length === 1 && !g[0]._pending ? `<button type="button" class="ghost-btn" id="optEdit">Edit entry</button>` : ""}
      </div>`);
    $("#optView").addEventListener("click", () => Receipt.show(receipt));
    $("#optPrint").addEventListener("click", () => Receipt.print(receipt));
    if ($("#optEdit")) $("#optEdit").addEventListener("click", () => openEditSheet(g[0]));
  });

  /* ---------------- Edit a saved entry ---------------- */

  function openEditSheet(t) {
    const isStock = t.category_type === "STOCK";
    const opt = (v, label) => `<option value="${v}" ${t.method === v ? "selected" : ""}>${label}</option>`;
    Sheet.open("Edit entry", `
      <p class="form-error" id="editMsg"></p>
      <p class="subtle">The date stays the same. Totals and stock update when you save.</p>
      <form id="editForm">
        <label class="field"><span>Amount (KES)</span><input type="number" id="editAmount" min="0.01" step="0.01" required value="${t.amount}" /></label>
        ${isStock && t.quantity ? `<label class="field"><span>Quantity</span><input type="number" id="editQty" min="0.01" step="0.01" value="${t.quantity}" /></label>` : ""}
        ${isStock ? "" : `<label class="field"><span>Category</span><input type="text" id="editCategory" required value="${escapeHtml(t.category)}" /></label>`}
        <label class="field"><span>Method</span><select id="editMethod">${opt("cash", "Cash")}${opt("mpesa", "M-Pesa")}${opt("bank", "Bank")}</select></label>
        <label class="field"><span>Reference (M-Pesa or bank code)</span><input type="text" id="editRef" value="${escapeHtml(t.mpesa_code || "")}" /></label>
        <label class="field"><span>Note</span><input type="text" id="editNote" value="${escapeHtml(t.description || "")}" /></label>
        <button class="primary-btn" type="submit" id="editSubmit">Save changes</button>
      </form>
    `);
    $("#editForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#editMsg");
      hideFormMessage(msg);
      const btn = $("#editSubmit");
      btn.disabled = true;
      btn.textContent = "Saving…";
      const method = $("#editMethod").value;
      const payload = {
        amount: parseFloat($("#editAmount").value),
        method,
        mpesa_code: method === "cash" ? "" : $("#editRef").value.trim(),
        description: $("#editNote").value.trim(),
      };
      if (!isStock) payload.category = $("#editCategory").value.trim();
      if ($("#editQty")) payload.quantity = parseFloat($("#editQty").value);
      try {
        await Api.transactions.update(t.id, payload);
        Sheet.close();
        toast("Entry updated.");
        await loadData();
      } catch (err) {
        showFormMessage(msg, err.status === 0 ? "You are offline. Connect to the internet to edit a saved entry." : (err.message || "Could not save the changes."));
        btn.disabled = false;
        btn.textContent = "Save changes";
      }
    });
  }

  /* ---------------- Receiving / Paying sheet ---------------- */

  $$(".action-card[data-flow]").forEach((btn) => {
    btn.addEventListener("click", () => openTxnSheet(btn.dataset.flow));
  });

  async function openTxnSheet(flow) {
    const title = flow === "income" ? "Receiving" : "Paying";
    const stockVerb = flow === "income" ? "Sale (removes from stock)" : "Restock (adds to stock)";
    const isTeacher = session.staff_type === "teacher";

    if (!isTeacher) {
      try {
        stockItems = await Api.stock.list();
        await Offline.cacheSet("stock_items", stockItems);
      } catch (err) {
        stockItems = (err.status === 0 ? await Offline.cacheGet("stock_items") : null) || [];
      }
    }

    const stockOptions = stockItems.map((s) =>
      `<option value="${s.id}" data-price="${s.unit_price || 0}" data-qty="${s.quantity}">${escapeHtml(s.name)} (${s.quantity} in stock)</option>`
    ).join("");

    Sheet.open(title, `
      <p class="form-error" id="txnMsg"></p>
      <form id="txnForm">
        <div class="cart-box" id="cartBox" hidden><p class="cart-title" id="cartTitle"></p><ul id="cartList"></ul></div>
        <label class="field"><span>Method</span>
          <select id="txnMethod">
            <option value="cash">Cash</option>
            <option value="mpesa">M-Pesa</option>
            <option value="bank">Bank</option>
          </select>
        </label>

        ${isTeacher ? "" : `
        <div class="segmented" id="categoryTypeSegmented">
          <button type="button" data-type="OTHER" class="is-active">Other</button>
          <button type="button" data-type="STOCK">Stock item</button>
        </div>`}

        <div id="mpesaPasteBlock" hidden>
          <label class="field"><span>Paste M-Pesa message</span><textarea id="mpesaMessage" rows="3" placeholder="Paste the confirmation SMS here to auto-fill amount, code, and payer name"></textarea></label>
          <button type="button" class="ghost-btn" id="mpesaParseBtn">Auto-fill from message</button>
          <p class="file-hint" id="mpesaParseResult"></p>
        </div>

        <div id="otherCategoryField">
          <label class="field"><span>Category</span><input type="text" id="txnCategory" placeholder="e.g. rent, utilities" /></label>
        </div>

        <div id="stockCategoryFields" hidden>
          ${stockItems.length ? `
            <label class="field"><span>Item</span><select id="txnStockItem">${stockOptions}</select></label>
            <p class="file-hint" id="stockTypeHint">${stockVerb}</p>
            <label class="field"><span>Quantity</span><input type="number" id="txnQuantity" min="0.01" step="0.01" value="1" /></label>
          ` : `<p class="file-hint">No stock items yet — add one on the Stock page first, or use "Other" for this entry.</p>`}
        </div>

        <label class="field"><span>Amount (KES)</span><input type="number" id="txnAmount" min="0.01" step="0.01" required /></label>
        <button type="button" class="ghost-btn" id="addItemBtn">+ Add another item</button>
        <label class="field"><span>Description (optional)</span><input type="text" id="txnDescription" /></label>
        <label class="chip-input wide">
          <span class="ico" data-ico="image"></span> Add a photo (optional)
          <input type="file" id="txnImage" accept="image/png,image/jpeg,image/webp" hidden />
        </label>
        <p class="file-hint" id="txnImageName">Receipt, till slip, or similar evidence — not required.</p>
        <p class="file-hint">Date and time are recorded automatically.</p>
        <div class="cart-total" id="cartTotal" hidden><span id="cartTotalLabel">Total</span><strong id="cartTotalValue"></strong></div>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="txnSubmit">Save ${title.toLowerCase()}</button>
        </div>
      </form>
    `);

    let categoryType = "OTHER";
    let mpesaCode = null;
    let mpesaPayerName = null;

    // --- category type toggle ---
    $$("#categoryTypeSegmented button").forEach((btn) => {
      btn.addEventListener("click", () => {
        categoryType = btn.dataset.type;
        $$("#categoryTypeSegmented button").forEach((b) => b.classList.toggle("is-active", b === btn));
        $("#otherCategoryField").hidden = categoryType !== "OTHER";
        $("#stockCategoryFields").hidden = categoryType !== "STOCK";
        if (categoryType === "STOCK") applyStockPricing();
      });
    });

    function applyStockPricing() {
      const select = $("#txnStockItem");
      if (!select) return;
      const opt = select.selectedOptions[0];
      const price = parseFloat(opt?.dataset.price || 0);
      const qty = parseFloat($("#txnQuantity").value || 1);
      if (price > 0) $("#txnAmount").value = (price * qty).toFixed(2);
    }
    if ($("#txnStockItem")) {
      $("#txnStockItem").addEventListener("change", applyStockPricing);
      $("#txnQuantity").addEventListener("input", applyStockPricing);
    }

    // --- method toggle shows/hides the M-Pesa paste block ---
    function applyMethodVisibility() {
      $("#mpesaPasteBlock").hidden = $("#txnMethod").value !== "mpesa";
    }
    $("#txnMethod").addEventListener("change", applyMethodVisibility);
    applyMethodVisibility();

    // --- M-Pesa paste-to-fill ---
    $("#mpesaParseBtn").addEventListener("click", async () => {
      const message = $("#mpesaMessage").value.trim();
      const resultEl = $("#mpesaParseResult");
      if (!message) return;
      resultEl.textContent = "Reading message…";
      try {
        const result = await Api.mpesa.parse(message);
        if (!result.matched) {
          resultEl.textContent = "Couldn't recognize that as an M-Pesa message — fields left as-is.";
          return;
        }
        mpesaCode = result.code;
        mpesaPayerName = result.payer_name;
        if (result.amount != null && categoryType !== "STOCK") $("#txnAmount").value = result.amount;
        const parts = [];
        if (result.code) parts.push(`Code ${result.code}`);
        if (result.amount != null) parts.push(`KES ${result.amount}`);
        if (result.payer_name) parts.push(`from ${result.payer_name}`);
        resultEl.textContent = parts.length
          ? `Filled in: ${parts.join(" · ")}. Item and quantity still need picking manually — M-Pesa doesn't carry that.`
          : "Recognized as M-Pesa, but couldn't read the details clearly.";
      } catch (err) {
        resultEl.textContent = err.status === 0
          ? "Can't read M-Pesa messages while offline — fields left as-is, fill them in manually."
          : (err.message || "Could not read that message.");
      }
    });

    $("#txnImage").addEventListener("change", () => {
      const file = $("#txnImage").files[0];
      $("#txnImageName").textContent = file ? `Selected: ${file.name}` : "Receipt, till slip, or similar evidence — not required.";
    });

    // --- several items in one payment ---
    const cart = [];
    const batchId = (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : `${Date.now().toString(16)}${Math.random().toString(16).slice(2, 14)}`;
    const touched = () => $("#txnAmount").value !== "" || (categoryType === "OTHER" && $("#txnCategory").value.trim() !== "");

    function readLine() {
      const msg = $("#txnMsg");
      hideFormMessage(msg);
      const amount = parseFloat($("#txnAmount").value);
      if (categoryType === "OTHER" && !$("#txnCategory").value.trim()) { showFormMessage(msg, "Type what this item is, for example Sugar or Delivery."); return null; }
      if (categoryType === "STOCK" && !stockItems.length) { showFormMessage(msg, "Add a stock item first, or switch to \"Other\"."); return null; }
      if (!(amount > 0)) { showFormMessage(msg, "Enter an amount."); return null; }
      if (categoryType === "STOCK") {
        const item = stockItems.find((s) => String(s.id) === $("#txnStockItem").value);
        const quantity = parseFloat($("#txnQuantity").value);
        if (!(quantity > 0)) { showFormMessage(msg, "Enter a quantity."); return null; }
        return { category_type: "STOCK", category: item.name, stock_item_id: item.id, quantity, amount };
      }
      return { category_type: "OTHER", category: $("#txnCategory").value.trim(), amount };
    }

    function renderCart() {
      const sum = cart.reduce((a, l) => a + l.amount, 0) + (touched() ? (parseFloat($("#txnAmount").value) || 0) : 0);
      $("#cartBox").hidden = !cart.length;
      $("#cartTitle").textContent = `Items in this ${flow === "income" ? "sale" : "payment"} (${cart.length})`;
      $("#cartList").innerHTML = cart.map((l, i) => `<li><span><b>${escapeHtml(l.category)}</b>${l.quantity ? ` <small>x ${l.quantity}</small>` : ""}</span><span>${money(l.amount)} <button type="button" class="cart-x" data-i="${i}" aria-label="Remove ${escapeHtml(l.category)}">&times;</button></span></li>`).join("");
      $("#cartTotal").hidden = !cart.length;
      $("#cartTotalLabel").textContent = `Total (${cart.length + (touched() ? 1 : 0)} items)`;
      $("#cartTotalValue").textContent = money(sum);
      $("#txnSubmit").textContent = cart.length ? `Save \u00b7 ${money(sum)}` : `Save ${title.toLowerCase()}`;
    }
    $("#cartList").addEventListener("click", (e) => {
      const x = e.target.closest(".cart-x");
      if (!x) return;
      cart.splice(Number(x.dataset.i), 1);
      renderCart();
    });
    $("#addItemBtn").addEventListener("click", () => {
      const line = readLine();
      if (!line) return;
      cart.push(line);
      $("#txnAmount").value = "";
      $("#txnCategory").value = "";
      if ($("#txnQuantity")) $("#txnQuantity").value = "1";
      renderCart();
    });
    $("#txnAmount").addEventListener("input", renderCart);
    $("#txnCategory").addEventListener("input", renderCart);

    $("#txnForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#txnMsg");
      if (cart.length) {
        hideFormMessage(msg);
        const extra = touched() ? readLine() : null;
        if (touched() && !extra) return;
        const lines = [...cart, ...(extra ? [extra] : [])];
        const submit = $("#txnSubmit");
        submit.disabled = true;
        submit.textContent = "Saving…";
        const common = { type: flow, method: $("#txnMethod").value, description: $("#txnDescription").value.trim() || null, mpesa_code: mpesaCode, mpesa_payer_name: mpesaPayerName };
        const image = $("#txnImage").files[0] || null;
        const who = [business || session.full_name || "KNOW", session.full_name || ""];
        try {
          const created = await Api.transactions.batch({ batch_id: batchId, ...common, lines: lines.map((l) => ({ ...l, amount: String(l.amount) })) }, image);
          Sheet.close();
          await loadData();
          Receipt.saved(Receipt.fromTransactions(created, ...who));
        } catch (err) {
          if (err.status === 0) {
            // No connection: each item is kept on this device with the same receipt id and groups back together after syncing
            for (let i = 0; i < lines.length; i++) {
              await Offline.queueTransaction({ ...common, ...lines[i], batch_id: batchId, imageBlob: i === 0 ? image : null, imageName: i === 0 ? image?.name : undefined });
            }
            Sheet.close();
            await loadData();
            Receipt.saved(Receipt.fromFields(lines, common, ...who, batchId.slice(0, 6).toUpperCase()));
          } else {
            showFormMessage(msg, err.message || "Could not save the items.");
            submit.disabled = false;
            renderCart();
          }
        }
        return;
      }
      await submitSingle(e);
    });

    async function submitSingle(e) {
      const msg = $("#txnMsg");
      hideFormMessage(msg);
      const submitBtn = $("#txnSubmit");

      if (categoryType === "OTHER" && !$("#txnCategory").value.trim()) {
        showFormMessage(msg, "Category is required.");
        return;
      }
      if (categoryType === "STOCK" && !stockItems.length) {
        showFormMessage(msg, "Add a stock item first, or switch to \"Other\".");
        return;
      }

      submitBtn.disabled = true;
      submitBtn.textContent = "Saving…";

      const fields = {
        type: flow,
        method: $("#txnMethod").value,
        category_type: categoryType,
        category: categoryType === "OTHER" ? $("#txnCategory").value.trim() : undefined,
        stock_item_id: categoryType === "STOCK" ? parseInt($("#txnStockItem").value, 10) : undefined,
        quantity: categoryType === "STOCK" ? parseFloat($("#txnQuantity").value) : undefined,
        amount: parseFloat($("#txnAmount").value),
        description: $("#txnDescription").value.trim() || null,
        mpesa_code: mpesaCode,
        mpesa_payer_name: mpesaPayerName,
        image: $("#txnImage").files[0] || null,
      };

      try {
        const created = await Api.transactions.create(fields);
        Sheet.close();
        await loadData();
        Receipt.saved(Receipt.fromTransactions([created], business || session.full_name || "KNOW", session.full_name || ""));
      } catch (err) {
        if (err.status === 0) {
          // No connection — queue it locally instead of losing the entry;
          // it'll sync automatically on the next "online" event.
          const { image, ...rest } = fields;
          await Offline.queueTransaction({ ...rest, imageBlob: image, imageName: image?.name });
          Sheet.close();
          await loadData();
          const line = { category: fields.category || (stockItems.find((x) => x.id === fields.stock_item_id) || {}).name || "Item", quantity: fields.quantity, amount: fields.amount };
          Receipt.saved(Receipt.fromFields([line], fields, business || session.full_name || "KNOW", session.full_name || "", "PENDING"));
        } else {
          showFormMessage(msg, err.message || "Could not save the transaction.");
          submitBtn.disabled = false;
          submitBtn.textContent = `Save ${title.toLowerCase()}`;
        }
      }
    }
  }
})();
