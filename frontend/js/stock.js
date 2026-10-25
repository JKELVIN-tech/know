(async function () {
  const session = await initShell("stock", ["staff", "institution_admin"]);
  if (session.role === "staff" && session.staff_type === "teacher") {
    location.href = "home.html";
    return;
  }

  const isOwner = session.role === "institution_admin";
  let items = [];
  let categories = [];
  let branches = [];

  await loadCategories();
  if (isOwner) await loadBranches();
  await loadItems();
  refreshExchangeBadge();
  onFreshData(loadItems);

  async function loadBranches() {
    try { branches = await Api.exchange.branches(); } catch { branches = []; }
    const el = $("#branchFilter");
    el.innerHTML = `<option value="">All branches (collective)</option>` + branches.map((b) => `<option value="${b.id}">${escapeHtml(b.name)}</option>`).join("") + `<option value="none">Unassigned (shared)</option>`;
    $("#branchFilterWrap").hidden = false;
    $("#thBranch").hidden = false;
    el.addEventListener("change", () => renderItems());
  }

  function branchOptions(selected) {
    return branches.map((b) => `<option value="${b.id}" ${String(b.id) === String(selected) ? "selected" : ""}>${escapeHtml(b.name)}</option>`).join("");
  }

  async function loadCategories() {
    try {
      categories = await Api.productCategories.list();
    } catch {
      categories = [];
    }
  }

  function categoryOptions(selectedId) {
    const existing = categories.map((c) => `<option value="${c.id}" ${String(c.id) === String(selectedId) ? "selected" : ""}>${escapeHtml(c.name)}</option>`).join("");
    return existing + `<option value="__new__">+ Add new category…</option>`;
  }

  // Shared by both the Add and Edit sheets: shows an inline name field when
  // "+ Add new category…" is picked, creates it, then re-selects it.
  function wireCategoryPicker(selectId, blockId, inputId, addBtnId) {
    const select = $(`#${selectId}`);
    select.addEventListener("change", () => {
      const isNew = select.value === "__new__";
      $(`#${blockId}`).hidden = !isNew;
      if (isNew) $(`#${inputId}`).focus();
    });
    $(`#${addBtnId}`).addEventListener("click", async () => {
      const input = $(`#${inputId}`);
      const name = input.value.trim();
      if (!name) return;
      const btn = $(`#${addBtnId}`);
      btn.disabled = true;
      btn.textContent = "Adding…";
      try {
        const created = await Api.productCategories.create(name);
        await loadCategories();
        select.innerHTML = categoryOptions(created.id);
        $(`#${blockId}`).hidden = true;
        input.value = "";
        toast(`Category "${created.name}" added.`);
      } catch (err) {
        toast(err.message || "Could not add category.");
      } finally {
        btn.disabled = false;
        btn.textContent = "Add";
      }
    });
  }

  async function loadItems() {
    try {
      items = await Api.stock.list();
      renderItems();
    } catch (err) {
      $("#stockBody").innerHTML = `<tr class="empty-row"><td colspan="7">${escapeHtml(err.message)}</td></tr>`;
    }
  }

  $("#lowOnlyBtn").addEventListener("click", () => { lowOnly = !lowOnly; renderItems(); });
  const DEFAULT_LOW = 5;
  let lowOnly = false;
  const lowLimit = (s) => (s.low_stock_threshold != null ? Number(s.low_stock_threshold) : DEFAULT_LOW);
  const isOut = (s) => Number(s.quantity) <= 0;
  const isLow = (s) => !isOut(s) && Number(s.quantity) <= lowLimit(s);

  function renderItems() {
    const body = $("#stockBody");
    const loose = items.filter((s) => s.branch_id == null).length;
    $("#unassignedNote").hidden = !(isOwner && loose);
    $("#unassignedNote").textContent = `${loose} item${loose === 1 ? " is" : "s are"} not assigned to a branch yet, so every branch can see ${loose === 1 ? "it" : "them"}. Edit an item to assign it to a branch.`;
    const pick = isOwner ? $("#branchFilter").value : "";
    const inBranch = items.filter((s) => (pick === "" ? true : pick === "none" ? s.branch_id == null : String(s.branch_id) === pick));
    const lowCount = inBranch.filter(isLow).length;
    const outCount = inBranch.filter(isOut).length;
    $("#lowBanner").hidden = !(lowCount + outCount);
    $("#lowBannerText").textContent = [outCount ? `${outCount} out of stock` : "", lowCount ? `${lowCount} running low` : ""].filter(Boolean).join(" · ") + " — time to restock";
    $("#lowOnlyBtn").textContent = lowOnly ? "Show all items" : "Show low stock only";
    const visible = lowOnly ? inBranch.filter((s) => isLow(s) || isOut(s)) : inBranch;
    body.innerHTML = visible.length ? visible.map((s) => `
        <tr>
          <td ${isOwner ? "" : "hidden"}>${escapeHtml(s.branch_name || "Unassigned")}</td>
          <td><strong>${escapeHtml(s.name)}</strong></td>
          <td>${s.category_name ? `<span class="badge">${escapeHtml(s.category_name)}</span>` : "—"}</td>
          <td>${escapeHtml(s.description || "—")}</td>
          <td>${s.unit_price != null ? money(s.unit_price) : "—"}</td>
          <td>${s.quantity}${isOut(s) ? ' <span class="stock-flag out">Out</span>' : isLow(s) ? ' <span class="stock-flag low">Low</span>' : ""}</td>
          <td>
            <div class="row-actions">
              <button class="ghost-btn" data-edit="${s.id}">Edit</button>
              <button class="danger-btn" data-delete="${s.id}">Delete</button>
            </div>
          </td>
        </tr>
      `).join("") : `<tr class="empty-row"><td colspan="7">No stock items here yet. Add one to start recording sales and restocks against it.</td></tr>`;
    $$("[data-edit]", body).forEach((btn) => btn.addEventListener("click", () => openEditSheet(btn.dataset.edit)));
    $$("[data-delete]", body).forEach((btn) => btn.addEventListener("click", () => deleteItem(btn.dataset.delete)));
  }

  async function deleteItem(id) {
    const item = items.find((s) => String(s.id) === String(id));
    if (!item) return;
    if (!confirm(`Delete "${item.name}"? This only works if it has no recorded transactions yet.`)) return;
    try {
      await Api.stock.remove(id);
      toast("Stock item deleted.");
      await loadItems();
    } catch (err) {
      toast(err.message || "Could not delete this item.");
    }
  }

  /* ---------------- Add item sheet ---------------- */

  $("#newStockBtn").addEventListener("click", async () => {
    await loadCategories();
    Sheet.open("Add item", `
      <p class="form-error" id="stockMsg"></p>
      <form id="stockForm">
        ${isOwner ? `<label class="field"><span>Branch</span><select id="stockBranch"><option value="">Unassigned (shared)</option>${branchOptions($("#branchFilter").value)}</select></label>` : ""}
        <label class="field"><span>Name</span><input type="text" id="stockName" required /></label>
        <label class="field"><span>Product category</span>
          <select id="stockCategory" required>${categoryOptions()}</select>
        </label>
        <div class="inline-form" id="stockCategoryNew" hidden>
          <label class="field wide"><span>New category name</span><input type="text" id="stockCategoryNewName" placeholder="e.g. Pencils" /></label>
          <button type="button" class="ghost-btn" id="stockCategoryNewAdd">Add</button>
        </div>
        <p class="file-hint">Categorizes this item for cross-institution market insights. Pick the closest match, or add a new one if nothing fits.</p>
        <label class="field"><span>Description (optional)</span><input type="text" id="stockDescription" /></label>
        <label class="field"><span>Unit price (optional, KES)</span><input type="number" id="stockPrice" min="0" step="0.01" /></label>
        <label class="field"><span>Starting quantity</span><input type="number" id="stockQuantity" min="0" step="0.01" value="0" /></label>
        <label class="field"><span>Warn me when stock is at or below (empty uses 5)</span><input type="number" id="stockLow" min="0" step="0.01" /></label>
        <p class="file-hint">After this, quantity only moves through Receiving (sale) and Paying (restock) entries on Home.</p>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="stockSubmit">Add item</button>
        </div>
      </form>
    `);

    wireCategoryPicker("stockCategory", "stockCategoryNew", "stockCategoryNewName", "stockCategoryNewAdd");

    $("#stockForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#stockMsg");
      hideFormMessage(msg);
      const submitBtn = $("#stockSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Adding…";
      try {
        const categoryId = $("#stockCategory").value;
        if (!categoryId || categoryId === "__new__") throw new Error("Please pick a product category, or click \"Add\" to save the new one first.");
        await Api.stock.create({
          name: $("#stockName").value.trim(),
          category_id: parseInt(categoryId, 10),
          description: $("#stockDescription").value.trim() || null,
          unit_price: $("#stockPrice").value ? parseFloat($("#stockPrice").value) : null,
          quantity: parseFloat($("#stockQuantity").value || 0),
          low_stock_threshold: $("#stockLow").value ? parseFloat($("#stockLow").value) : null,
          branch_id: isOwner && $("#stockBranch").value ? parseInt($("#stockBranch").value, 10) : undefined,
        });
        Sheet.close();
        toast("Stock item added.");
        await loadItems();
      } catch (err) {
        showFormMessage(msg, err.message || "Could not add the item.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Add item";
      }
    });
  });

  /* ---------------- Edit item sheet ---------------- */

  async function openEditSheet(id) {
    const item = items.find((s) => String(s.id) === String(id));
    if (!item) return;
    await loadCategories();

    Sheet.open("Edit item", `
      <p class="form-error" id="editStockMsg"></p>
      <form id="editStockForm">
        ${isOwner && item.branch_id == null && branches.length ? `<label class="field"><span>Assign to branch</span><select id="editStockBranch"><option value="">Keep shared</option>${branchOptions()}</select></label>` : ""}
        <label class="field"><span>Name</span><input type="text" id="editStockName" value="${escapeHtml(item.name)}" required /></label>
        <label class="field"><span>Product category</span>
          <select id="editStockCategory" required>${categoryOptions(item.category_id)}</select>
        </label>
        <div class="inline-form" id="editStockCategoryNew" hidden>
          <label class="field wide"><span>New category name</span><input type="text" id="editStockCategoryNewName" placeholder="e.g. Pencils" /></label>
          <button type="button" class="ghost-btn" id="editStockCategoryNewAdd">Add</button>
        </div>
        <label class="field"><span>Description (optional)</span><input type="text" id="editStockDescription" value="${escapeHtml(item.description || "")}" /></label>
        <label class="field"><span>Unit price (optional, KES)</span><input type="number" id="editStockPrice" min="0" step="0.01" value="${item.unit_price ?? ""}" /></label>
        <label class="field"><span>Warn me when stock is at or below (empty uses 5)</span><input type="number" id="editStockLow" min="0" step="0.01" value="${item.low_stock_threshold ?? ""}" /></label>
        <p class="file-hint">Current quantity: ${item.quantity} — edit this via a Receiving/Paying entry on Home, not here.</p>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="editStockSubmit">Save changes</button>
        </div>
      </form>
    `);

    wireCategoryPicker("editStockCategory", "editStockCategoryNew", "editStockCategoryNewName", "editStockCategoryNewAdd");

    $("#editStockForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#editStockMsg");
      hideFormMessage(msg);
      const submitBtn = $("#editStockSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Saving…";
      try {
        const categoryId = $("#editStockCategory").value;
        if (categoryId === "__new__") throw new Error("Please click \"Add\" to save the new category first.");
        await Api.stock.update(id, {
          name: $("#editStockName").value.trim(),
          category_id: categoryId ? parseInt(categoryId, 10) : undefined,
          description: $("#editStockDescription").value.trim() || null,
          unit_price: $("#editStockPrice").value ? parseFloat($("#editStockPrice").value) : null,
          low_stock_threshold: $("#editStockLow").value ? parseFloat($("#editStockLow").value) : null,
          branch_id: $("#editStockBranch") && $("#editStockBranch").value ? parseInt($("#editStockBranch").value, 10) : undefined,
        });
        Sheet.close();
        toast("Stock item updated.");
        await loadItems();
      } catch (err) {
        showFormMessage(msg, err.message || "Could not update the item.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Save changes";
      }
    });
  }

  /* ---------------- Exchange panel ---------------- */

  $("#exchangeBtn").addEventListener("click", openExchange);

  async function refreshExchangeBadge() {
    try {
      const pending = (await Api.exchange.list({ status_filter: "pending" })).filter((t) => isOwner || t.direction === "incoming");
      $("#exchangeBtn").textContent = pending.length ? `Exchange stock (${pending.length})` : "Exchange stock";
    } catch { /* badge is optional */ }
  }

  async function openExchange() {
    let all = [];
    let meId = null;
    try {
      [branches, all] = await Promise.all([Api.exchange.branches(), Api.stock.list()]);
      if (!isOwner) meId = (await apiFetch("/api/users/me")).id;
    } catch (err) {
      toast(err.message || "Could not open the exchange.");
      return;
    }
    if (!branches.length) {
      toast(isOwner ? "Add branch accounts first." : "There are no other branches to exchange with.");
      return;
    }
    const opt = (b) => `<option value="${b.id}">${escapeHtml(b.name)}</option>`;
    Sheet.open("Exchange stock", `
      <p class="form-error" id="exMsg"></p>
      <form id="exForm">
        ${isOwner ? `<label class="field"><span>Take the stock from</span><select id="exFrom">${branches.map(opt).join("")}${all.some((s) => s.branch_id == null) ? `<option value="pool">Unassigned (shared stock)</option>` : ""}</select></label>` : ""}
        <label class="field"><span>Item</span><select id="exItem"></select></label>
        <label class="field"><span>Quantity</span><input type="number" id="exQty" min="0" step="0.01" required /></label>
        <label class="field"><span>Send to</span><select id="exTo"></select></label>
        <label class="field"><span>Note (optional)</span><input type="text" id="exNote" maxlength="200" /></label>
        <p class="ex-summary" id="exSummary"></p>
        <p class="file-hint">The quantity leaves the sending branch now and reaches the other branch when they confirm it. It is not a sale or a purchase, so income and expenses don't change.</p>
        <div class="form-actions"><button type="submit" class="primary-btn" id="exSubmit">Send stock</button></div>
      </form>
      <h3>Transfers</h3>
      <div id="exList"><p class="subtle">Loading…</p></div>
    `);
    const fromEl = $("#exFrom");
    const itemEl = $("#exItem");
    const toEl = $("#exTo");
    const sum = $("#exSummary");
    const poolPicked = () => isOwner && fromEl.value === "pool";
    const fromId = () => (isOwner ? (poolPicked() ? null : Number(fromEl.value)) : meId);

    function updateSummary() {
      const item = all.find((s) => String(s.id) === itemEl.value);
      const to = branches.find((b) => String(b.id) === toEl.value);
      const qty = parseFloat($("#exQty").value);
      const fromName = isOwner ? (poolPicked() ? "the shared stock" : fromEl.options[fromEl.selectedIndex]?.text || "") : "your stock";
      if (!item || !to) { sum.textContent = "Choose what to send and who receives it."; return; }
      if (!(qty > 0)) { sum.innerHTML = `Sending <strong>${escapeHtml(item.name)}</strong> from ${escapeHtml(fromName)} to <strong>${escapeHtml(to.name)}</strong>. Enter a quantity.`; return; }
      const left = Number(item.quantity) - qty;
      sum.innerHTML = `Sending <strong>${qty} × ${escapeHtml(item.name)}</strong> from ${escapeHtml(fromName)} to <strong>${escapeHtml(to.name)}</strong>. ` + (left < 0 ? `<span class="neg">Only ${Number(item.quantity)} available.</span>` : `${left} will be left.`);
    }

    function fillOptions() {
      const mine = all.filter((s) => (isOwner ? (poolPicked() ? s.branch_id == null : s.branch_id === fromId()) : s.branch_id === meId || s.branch_id == null));
      itemEl.innerHTML = mine.length
        ? mine.map((s) => `<option value="${s.id}">${escapeHtml(s.name)}${s.branch_id == null ? " (shared)" : ""} — ${Number(s.quantity)} in stock</option>`).join("")
        : `<option value="">Nothing to send</option>`;
      toEl.innerHTML = branches.filter((b) => b.id !== fromId()).map(opt).join("");
      updateSummary();
    }
    if (isOwner) fromEl.addEventListener("change", fillOptions);
    [itemEl, toEl].forEach((el) => el.addEventListener("change", updateSummary));
    $("#exQty").addEventListener("input", updateSummary);
    fillOptions();

    async function loadTransfers() {
      const box = $("#exList");
      if (!box) return;
      try {
        const rows = await Api.exchange.list();
        box.innerHTML = rows.length ? rows.map((t) => {
          const pending = t.status === "pending";
          const mayReceive = t.direction === "incoming" || isOwner;
          const maySend = t.direction === "outgoing" || isOwner;
          const waiting = pending && t.direction === "incoming" ? `<small class="neg">Waiting for you to confirm</small>` : pending && t.direction === "outgoing" ? `<small>Waiting for ${escapeHtml(t.to_branch_name)} to confirm</small>` : "";
          const acts = !pending ? `<span class="pill ${t.status === "received" ? "ok" : "warn"}">${escapeHtml(t.status)}</span>` : [
            mayReceive && `<button type="button" class="ghost-btn" data-ex="receive" data-id="${t.id}">Receive</button>`,
            mayReceive && `<button type="button" class="ghost-btn" data-ex="reject" data-id="${t.id}">Reject</button>`,
            maySend && `<button type="button" class="ghost-btn" data-ex="cancel" data-id="${t.id}">Cancel</button>`,
          ].filter(Boolean).join(" ");
          return `<div class="ex-row"><div><strong>${Number(t.quantity)} × ${escapeHtml(t.item_name)}</strong><small>From ${escapeHtml(t.from_branch_name)} → to ${escapeHtml(t.to_branch_name)} · ${formatDate(t.created_at)}${t.note ? " · " + escapeHtml(t.note) : ""}</small>${waiting}</div><div>${acts}</div></div>`;
        }).join("") : `<p class="subtle">No transfers yet.</p>`;
      } catch (err) {
        box.innerHTML = `<p class="subtle">${escapeHtml(err.message || "Could not load transfers.")}</p>`;
      }
    }
    await loadTransfers();

    $("#exList").addEventListener("click", async (e) => {
      const btn = e.target.closest("[data-ex]");
      if (!btn) return;
      btn.disabled = true;
      try {
        await Api.exchange[btn.dataset.ex](btn.dataset.id);
        all = await Api.stock.list();
        fillOptions();
        await loadItems();
        await loadTransfers();
        refreshExchangeBadge();
      } catch (err) {
        toast(err.message || "Could not update this transfer.");
        btn.disabled = false;
      }
    });

    $("#exForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#exMsg");
      hideFormMessage(msg);
      const submit = $("#exSubmit");
      submit.disabled = true;
      try {
        if (!itemEl.value) throw new Error("There is no stock to send here. Add stock first, or ask your owner to assign shared stock to a branch.");
        if (!confirm(sum.textContent.replace(/\s+/g, " ").trim() + "\n\nSend it?")) { submit.disabled = false; return; }
        await Api.exchange.send({
          item_id: parseInt(itemEl.value, 10),
          quantity: parseFloat($("#exQty").value),
          to_branch_id: parseInt(toEl.value, 10),
          from_branch_id: isOwner && fromId() != null ? fromId() : undefined,
          note: $("#exNote").value.trim() || null,
        });
        $("#exQty").value = "";
        $("#exNote").value = "";
        all = await Api.stock.list();
        fillOptions();
        await loadItems();
        await loadTransfers();
        toast("Stock sent. It reaches the other branch when they confirm.");
      } catch (err) {
        showFormMessage(msg, err.message || "Could not send the stock.");
      } finally {
        submit.disabled = false;
      }
    });
  }
})();
