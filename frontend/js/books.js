(async function () {
  const session = await initShell("books", ["institution_admin"]);

  let staff = [];
  const filterEl = $("#staffFilter");

  try {
    staff = (await Api.users.list()).filter((u) => u.role === "staff");
    filterEl.innerHTML = `<option value="">All staff (collective)</option>` +
      staff.map((u) => `<option value="${u.id}">${escapeHtml(u.full_name)} (${escapeHtml(u.username)})</option>`).join("");
  } catch (err) {
    toast(err.message || "Could not load staff.");
  }

  const nameOf = (id) => staff.find((u) => u.id === id)?.full_name || "—";
  const methodName = (m) => (m === "mpesa" ? "M-Pesa" : m[0].toUpperCase() + m.slice(1));

  function params() {
    const p = {};
    if (filterEl.value) p.staff_id = filterEl.value;
    if ($("#booksFrom").value) p.start_date = new Date($("#booksFrom").value + "T00:00:00").toISOString();
    if ($("#booksTo").value) p.end_date = new Date($("#booksTo").value + "T23:59:59").toISOString();
    return p;
  }

  async function load() {
    const p = params();
    try {
      const [summary, tb, txns] = await Promise.all([
        Api.accounting.summary(p),
        Api.accounting.trialBalance(p),
        Api.transactions.list(p),
      ]);
      $("#bkIncome").textContent = money(summary.income);
      $("#bkExpense").textContent = money(summary.expenses);
      $("#bkNet").textContent = money(summary.net);
      const held = Object.fromEntries(summary.money.map((m) => [m.key, m.balance]));
      $("#bkCash").textContent = money(held.cash || 0);
      $("#bkMpesa").textContent = money(held.mpesa || 0);
      $("#bkBank").textContent = money(held.bank || 0);

      $("#tbBody").innerHTML = tb.accounts.length
        ? tb.accounts.map((a) => `<tr><td>${a.code}</td><td>${escapeHtml(a.name)}</td><td>${parseFloat(a.debit) ? money(a.debit) : ""}</td><td>${parseFloat(a.credit) ? money(a.credit) : ""}</td></tr>`).join("") +
          `<tr><td></td><td><strong>Total</strong></td><td><strong>${money(tb.total_debit)}</strong></td><td><strong>${money(tb.total_credit)}</strong></td></tr>`
        : `<tr class="empty-row"><td colspan="4">No entries yet.</td></tr>`;
      $("#tbStatus").textContent = tb.balanced ? "Balanced" : "Out of balance";
      $("#tbStatus").className = tb.balanced ? "pill" : "pill warn";

      $("#recBody").innerHTML = txns.length
        ? txns.map((t) => `<tr><td>${formatDate(t.transaction_date, true)}</td><td>${escapeHtml(nameOf(t.recorded_by_id))}</td><td><span class="badge badge-${t.type}">${t.type}</span></td><td>${methodName(t.method)}</td><td>${escapeHtml(t.category)}</td><td>${money(t.amount)}</td></tr>`).join("")
        : `<tr class="empty-row"><td colspan="6">No records for this selection.</td></tr>`;
    } catch (err) {
      toast(err.message || "Could not load the books.");
    }
  }

  let lastKey = "";
  let lastFrom = "";
  let lastTo = "";

  const dayValue = (iso) => {
    const d = new Date(iso);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  };

  function settle(from, to) {
    lastFrom = from;
    lastTo = to;
    lastKey = from && to ? `${from}|${to}` : "";
  }

  function askTitle(from, to) {
    const staffName = filterEl.value ? nameOf(Number(filterEl.value)) : "";
    Sheet.open("Name this audit", `
      <p class="form-error" id="auditMsg"></p>
      <form id="auditForm">
        <label class="field"><span>Title</span><input type="text" id="auditTitle" maxlength="200" required value="Books ${from} to ${to}" /></label>
        <p class="file-hint">This range is saved in Audits with its title and dates, and the books are generated for it. Nothing is locked until you finalize it there.</p>
        <div class="form-actions"><button type="submit" class="primary-btn" id="auditSubmit">Save and generate</button></div>
      </form>
    `);
    $("#auditTitle").focus();
    Sheet.onClose = () => {
      $("#booksFrom").value = lastFrom;
      $("#booksTo").value = lastTo;
      load();
    };
    $("#auditForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#auditMsg");
      hideFormMessage(msg);
      const btn = $("#auditSubmit");
      btn.disabled = true;
      try {
        await Api.audits.create({
          title: $("#auditTitle").value.trim(),
          period_start: new Date(from + "T00:00:00").toISOString(),
          period_end: new Date(to + "T23:59:59").toISOString(),
          summary: staffName ? `Books viewed for ${staffName}` : null,
        });
        settle(from, to);
        Sheet.onClose = null;
        Sheet.close();
        toast("Saved to Audits.");
        await load();
      } catch (err) {
        showFormMessage(msg, err.status === 0 ? "You need a connection to save an audit." : err.message || "Could not save the audit.");
        btn.disabled = false;
      }
    });
  }

  function onRangeChange() {
    const from = $("#booksFrom").value;
    const to = $("#booksTo").value;
    if (!from || !to) { settle(from, to); load(); return; }
    if (from > to) {
      toast("The start date must come before the end date.");
      $("#booksFrom").value = lastFrom;
      $("#booksTo").value = lastTo;
      return;
    }
    if (`${from}|${to}` === lastKey) { load(); return; }
    askTitle(from, to);
  }

  filterEl.addEventListener("change", load);
  $("#booksFrom").addEventListener("change", onRangeChange);
  $("#booksTo").addEventListener("change", onRangeChange);

  const auditId = new URLSearchParams(location.search).get("audit");
  if (auditId) {
    try {
      const a = await Api.audits.get(auditId);
      $("#booksFrom").value = dayValue(a.period_start);
      $("#booksTo").value = dayValue(a.period_end);
      settle($("#booksFrom").value, $("#booksTo").value);
      $("#booksAuditNote").textContent = `Audit: ${a.title}`;
      $("#booksAuditNote").hidden = false;
    } catch (err) {
      toast(err.message || "Could not open that audit.");
    }
  }
  await load();
})();
