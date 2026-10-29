const Receipt = (() => {
  const money = (n) => "KES " + Number(n).toLocaleString("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const plain = (n) => Number(n).toLocaleString("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const methodName = (m) => ({ mpesa: "M-Pesa", bank: "Bank" })[m] || "Cash";
  const when = (iso) => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? "" : d.toLocaleString("en-KE", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Africa/Nairobi" });
  };

  /* A saved receipt: every entry that shares one receipt id, or a single entry on its own. */
  function fromTransactions(entries, business, servedBy) {
    const rows = [...entries].sort((a, b) => String(a.id).localeCompare(String(b.id), undefined, { numeric: true }));
    const first = rows[0];
    const lines = rows.map((t) => {
      const qty = t.quantity ? Number(t.quantity) : null;
      return { name: t.category, quantity: qty, unit: qty ? Number(t.amount) / qty : null, amount: Number(t.amount) };
    });
    return {
      isIncome: first.type === "income",
      number: first.batch_id ? first.batch_id.slice(0, 6).toUpperCase() : `T${first.id}`,
      when: when(first.transaction_date),
      business, servedBy, lines,
      total: lines.reduce((a, l) => a + l.amount, 0),
      method: methodName(first.method),
      reference: (rows.find((t) => t.mpesa_code) || {}).mpesa_code || null,
      payer: (rows.find((t) => t.mpesa_payer_name) || {}).mpesa_payer_name || null,
      note: (rows.find((t) => t.description) || {}).description || null,
      pending: rows.some((t) => t._pending),
    };
  }

  /* A sale kept on this device while offline: shown as pending until it syncs. */
  function fromFields(lines, common, business, servedBy, number) {
    const mapped = lines.map((l) => ({ name: l.category, quantity: l.quantity ?? null, unit: l.quantity ? Number(l.amount) / l.quantity : null, amount: Number(l.amount) }));
    return {
      isIncome: common.type === "income", number, when: when(new Date().toISOString()), business, servedBy, lines: mapped,
      total: mapped.reduce((a, l) => a + l.amount, 0), method: methodName(common.method),
      reference: common.mpesa_code || null, payer: common.mpesa_payer_name || null, note: common.description || null, pending: true,
    };
  }

  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

  function paper(d) {
    const lines = d.lines.map((l) => `<div class="rc-line"><div><b>${esc(l.name)}</b>${l.quantity ? `<small>${l.quantity}${l.unit ? " x " + plain(l.unit) : ""}</small>` : ""}</div><b>${plain(l.amount)}</b></div>`).join("");
    const pay = (label, value, mono) => (value ? `<div class="rc-row"><span>${label}</span><span class="${mono ? "rc-mono" : ""}">${esc(value)}</span></div>` : "");
    return `<div class="rc-paper">
      <h3>${esc(d.business)}</h3>
      <p class="rc-title">${d.isIncome ? "RECEIPT" : "PAYMENT VOUCHER"}</p>
      <div class="rc-row rc-small"><span>No. ${esc(d.number)}</span><span>${esc(d.when)}</span></div>
      <div class="rc-row rc-small"><span>Served by</span><span>${esc(d.servedBy)}</span></div>
      ${d.pending ? `<p class="rc-pending">Saved on this device, not synced yet</p>` : ""}
      <hr />${lines}<hr class="rc-solid" />
      <div class="rc-total"><span>TOTAL</span><span>${money(d.total)}</span></div><hr />
      ${pay(d.isIncome ? "Paid by" : "Paid through", d.method)}${pay("Reference", d.reference, true)}${pay(d.isIncome ? "From" : "To", d.payer)}${pay("Note", d.note)}
      <p class="rc-thanks">${d.isIncome ? "Thank you!" : "Payment recorded"}</p>
      <p class="rc-made">Made by KNOW</p>
    </div>`;
  }

  const PRINT_CSS = `@page { size: 80mm auto; margin: 3mm; }
    body { margin: 0; font-family: Arial, Helvetica, sans-serif; color: #10201d; font-size: 11px; }
    .rc-paper { width: 74mm; } h3 { margin: 0; text-align: center; font-size: 15px; }
    .rc-title { text-align: center; color: #0f766e; font-weight: 700; letter-spacing: .12em; margin: 4px 0 10px; }
    .rc-row, .rc-line, .rc-total { display: flex; justify-content: space-between; gap: 8px; }
    .rc-small { color: #3f514d; font-size: 10px; } .rc-line { padding: 4px 0; } .rc-line small { display: block; color: #5d6f6b; font-weight: 400; }
    hr { border: 0; border-top: 1px dashed #5d6f6b; margin: 8px 0; } .rc-solid { border-top: 1px solid #10201d; }
    .rc-total { font-size: 15px; font-weight: 800; } .rc-mono { font-family: monospace; }
    .rc-thanks { text-align: center; font-weight: 700; margin: 14px 0 2px; } .rc-made { text-align: center; color: #5d6f6b; font-size: 9px; margin: 0; }
    .rc-pending { color: #b45309; font-weight: 700; font-size: 10px; }`;

  /* Opens the browser's print dialog, which can print on any printer or save as a PDF to share. */
  function print(d) {
    const frame = document.createElement("iframe");
    frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;";
    document.body.appendChild(frame);
    const doc = frame.contentWindow.document;
    doc.open();
    doc.write(`<!doctype html><html><head><meta charset="utf-8"><title>Receipt ${esc(d.number)}</title><style>${PRINT_CSS}</style></head><body>${paper(d)}</body></html>`);
    doc.close();
    setTimeout(() => {
      frame.contentWindow.focus();
      frame.contentWindow.print();
      setTimeout(() => frame.remove(), 1500);
    }, 250);
  }

  function show(d) {
    Sheet.open("Receipt", `${paper(d)}<div class="form-actions"><button type="button" class="primary-btn" id="rcPrint">Print receipt</button></div><p class="file-hint">Choose a printer, or Save as PDF to share it.</p>`);
    $("#rcPrint").addEventListener("click", () => print(d));
  }

  /* Shown after saving: the entry is stored, and the receipt can be printed straight away. */
  function saved(d) {
    Sheet.open("Saved", `
      <div class="rc-saved"><div class="rc-check">&#10003;</div><strong>${d.pending ? "Saved on this device" : "Saved"}</strong>
      <p class="subtle">${d.lines.length} ${d.lines.length === 1 ? "item" : "items"} &middot; ${money(d.total)}</p></div>
      <div class="form-actions rc-actions"><button type="button" class="primary-btn" id="rcSavedPrint">Print receipt</button>
      <button type="button" class="ghost-btn" id="rcSavedView">View receipt</button>
      <button type="button" class="ghost-btn" id="rcSavedDone">Done</button></div>`);
    $("#rcSavedPrint").addEventListener("click", () => print(d));
    $("#rcSavedView").addEventListener("click", () => show(d));
    $("#rcSavedDone").addEventListener("click", () => Sheet.close());
  }

  return { fromTransactions, fromFields, print, show, saved, money };
})();
