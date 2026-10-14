(async function () {
  const session = await initShell("imports", ["staff"]);
  let stockItems = [];
  try { stockItems = await Api.stock.list(); } catch { stockItems = []; }

  const kindName = { sent: "Sent", paybill: "Paybill", till: "Till", received: "Received", withdrawal: "Withdrawal", reversal: "Reversal", unknown: "Unknown" };
  const reasonName = { bank_bound: "Goes to a bank: only the M-Pesa side is recorded here", reversal_needs_review: "Reversal: enter it manually", missing_fields: "Missing details: enter it manually", already_recorded: "Already recorded" };

  function row(it) {
    const who = it.counterparty ? escapeHtml(it.counterparty) : "—";
    const sign = it.direction === "in" ? "+" : it.direction === "out" ? "−" : "";
    const note = it.reason && reasonName[it.reason] ? `<small>${escapeHtml(reasonName[it.reason])}</small>` : "";
    return `<li class="txn" data-id="${it.id}"><div><strong>${escapeHtml(kindName[it.kind] || it.kind)} · ${who}</strong><small>${escapeHtml(it.code)}${it.message_time ? " · " + formatDate(it.message_time, true) : ""}</small>${note}</div><div><strong>${sign}${it.amount == null ? "—" : formatMoney(it.amount)}</strong></div></li>`;
  }

  function form(it) {
    const items = stockItems.map((s) => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join("");
    return `<li class="txn-form" data-form="${it.id}">
      <label class="field"><span>Record as</span><select class="fType"><option value="OTHER">Other (rent, transport, ...)</option>${items ? '<option value="STOCK">Stock item</option>' : ""}</select></label>
      <label class="field fCatWrap"><span>Category</span><input class="fCat" maxlength="80" /></label>
      <label class="field fItemWrap" hidden><span>Item</span><select class="fItem">${items}</select></label>
      <label class="field fQtyWrap" hidden><span>Quantity</span><input class="fQty" type="number" min="0" step="any" /></label>
      <label class="field"><span>Note (optional)</span><input class="fDesc" maxlength="200" /></label>
      <p class="form-message fMsg" hidden></p>
      <button type="button" class="primary-btn fSave">Save to books</button>
    </li>`;
  }

  async function load() {
    let all = [];
    try { all = await Api.imports.list(); } catch (err) { toast(err.status === 0 ? "You need a connection." : err.message || "Could not load."); return; }
    const open = all.filter((i) => i.status === "detected" || i.status === "needs_review");
    $("#impCount").textContent = String(open.length);
    $("#impList").innerHTML = open.length
      ? open.map((i) => row(i) + `<li class="txn-actions" data-actions="${i.id}"><button type="button" class="ghost-btn" data-approve="${i.id}">Review</button> <button type="button" class="ghost-btn" data-ignore="${i.id}">Ignore</button></li>`).join("")
      : `<li class="subtle">Nothing waiting.</li>`;
    const done = all.filter((i) => !open.includes(i)).slice(0, 30);
    $("#impDone").innerHTML = done.length ? done.map((i) => row(i) + `<li class="subtle">${escapeHtml(i.status)}</li>`).join("") : `<li class="subtle">Nothing yet.</li>`;
  }

  $("#impScan").addEventListener("click", async () => {
    const msg = $("#impMsg");
    const text = $("#impText").value.trim();
    if (!text) { showFormMessage(msg, "Paste at least one message."); return; }
    const parts = text.split(/(?=\b[A-Z0-9]{8,12}\s+Confirmed)/).map((s) => s.trim()).filter(Boolean).slice(0, 50);
    try {
      const res = await Api.imports.scan(parts);
      $("#impText").value = "";
      showFormMessage(msg, `${res.items.length} detected${res.skipped ? `, ${res.skipped} not recognised` : ""}.`, false);
      await load();
    } catch (err) {
      showFormMessage(msg, err.status === 0 ? "You need a connection to detect messages." : err.message || "Could not detect.");
    }
  });

  $("#impList").addEventListener("click", async (e) => {
    const approve = e.target.closest("[data-approve]");
    const ignore = e.target.closest("[data-ignore]");
    const save = e.target.closest(".fSave");
    if (ignore) {
      try { await Api.imports.ignore(ignore.dataset.ignore); await load(); } catch (err) { toast(err.message || "Could not ignore."); }
    } else if (approve) {
      const it = (await Api.imports.list()).find((i) => String(i.id) === approve.dataset.approve);
      if (!it || $(`[data-form="${it.id}"]`)) return;
      $(`[data-actions="${it.id}"]`).insertAdjacentHTML("afterend", form(it));
    } else if (save) {
      const box = save.closest("[data-form]");
      const type = $(".fType", box).value;
      const payload = { category_type: type, description: $(".fDesc", box).value };
      if (type === "STOCK") { payload.stock_item_id = Number($(".fItem", box).value); payload.quantity = $(".fQty", box).value; } else { payload.category = $(".fCat", box).value; }
      const msg = $(".fMsg", box);
      try {
        await Api.imports.approve(box.dataset.form, payload);
        toast("Saved to books.");
        await load();
      } catch (err) {
        if (err.status === 409 && /already recorded/i.test(err.message || "") && confirm(err.message)) {
          try { await Api.imports.approve(box.dataset.form, { ...payload, force: true }); await load(); } catch (e2) { showFormMessage(msg, e2.message || "Could not save."); }
        } else {
          showFormMessage(msg, err.status === 0 ? "You need a connection to save." : err.message || "Could not save.");
        }
      }
    }
  });

  $("#impList").addEventListener("change", (e) => {
    const sel = e.target.closest(".fType");
    if (!sel) return;
    const box = sel.closest("[data-form]");
    const stock = sel.value === "STOCK";
    $(".fCatWrap", box).hidden = stock;
    $(".fItemWrap", box).hidden = !stock;
    $(".fQtyWrap", box).hidden = !stock;
  });

  await load();
})();
