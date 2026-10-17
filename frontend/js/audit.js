(async function () {
  const session = await initShell("audit", ["staff"]);
  $("#auditRoleName").textContent = session.full_name;

  let allTransactions = [];
  let allAuditsForLocking = [];

  await loadRecords();
  await loadAuditGrid();
  window.addEventListener("online", handleBackOnline);

  async function handleBackOnline() {
    await Offline.syncPendingTransactions();
    await loadRecords();
    await loadAuditGrid();
  }

  /* ---------------- Records panel (filterable by method) ---------------- */

  async function loadRecords() {
    try {
      [allTransactions, allAuditsForLocking] = await Promise.all([
        Api.transactions.list(),
        Api.audits.list(),
      ]);
      await Offline.cacheSet("transactions", allTransactions);
      await Offline.cacheSet("audits", allAuditsForLocking);
      renderKpis();
    } catch (err) {
      if (err.status === 0) {
        allTransactions = (await Offline.cacheGet("transactions")) || [];
        allAuditsForLocking = (await Offline.cacheGet("audits")) || [];
        renderKpis();
      } else {
        toast(err.message || "Could not load your records.");
      }
    }
  }

  function netFor(method) {
    const rows = method ? allTransactions.filter((t) => t.method === method) : allTransactions;
    const income = rows.filter((t) => t.type === "income").reduce((s, t) => s + parseFloat(t.amount), 0);
    const expense = rows.filter((t) => t.type === "expense").reduce((s, t) => s + parseFloat(t.amount), 0);
    return income - expense;
  }

  function renderKpis() {
    $("#kpiCash").textContent = money(netFor("cash"));
    $("#kpiMpesa").textContent = money(netFor("mpesa"));
    $("#kpiBank").textContent = money(netFor("bank"));
    $("#auditTotalsPill").textContent = `Net · ${money(netFor(null))}`;
  }

  function methodLabel(method) {
    return method === "mpesa" ? "M-Pesa" : method[0].toUpperCase() + method.slice(1);
  }

  /* ================================================================
     Statements — printable, well-organised movement tables.
     Single-method statements (Cash / M-Pesa / Bank) are grouped into
     an Income band and an Expenses band, each with its own subtotal —
     the same shape as a typical income statement. The Combined
     statement instead bands by METHOD (cash/mpesa/bank), so if two
     methods' entries fall next to each other in time they still each
     read as one solid coloured block, never interleaved.
     ================================================================ */

  const METHOD_LABEL = { cash: "Cash", mpesa: "M-Pesa", bank: "Bank" };
  const METHOD_COLOR = { cash: "#0f766e", mpesa: "#15803d", bank: "#1d4ed8" };
  const INCOME_COLOR = "#0f766e";
  const EXPENSE_COLOR = "#b45309";
  const METHOD_ORDER = ["cash", "mpesa", "bank"];

  const today = new Date();
  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
  $("#stmtFrom").valueAsDate = monthStart;
  $("#stmtTo").valueAsDate = today;

  function statementRange() {
    const fromVal = $("#stmtFrom").value;
    const toVal = $("#stmtTo").value;
    if (!fromVal || !toVal) {
      toast("Pick a from and to date first.");
      return null;
    }
    const start = new Date(fromVal + "T00:00:00Z");
    const end = new Date(toVal + "T23:59:59Z");
    if (start > end) {
      toast("The from date must be before the to date.");
      return null;
    }
    return { start, end };
  }

  function transactionsForStatement(method, start, end) {
    return allTransactions
      .filter((t) => {
        const d = new Date(t.transaction_date);
        const inRange = d >= start && d <= end;
        return inRange && (method === "all" || t.method === method);
      })
      .sort((a, b) => new Date(a.transaction_date) - new Date(b.transaction_date));
  }

  function fmtRow(t, signed) {
    const photo = t.image_path
      ? `<div class="stmt-evidence"><img src="${Api.transactions.imageUrl(t)}" alt="Evidence for ${escapeHtml(t.category)}" /></div>`
      : `<span class="stmt-no-evidence">—</span>`;
    const amountText = signed ? `${t.type === "income" ? "+" : "−"}${money(t.amount)}` : money(t.amount);
    const amountClass = signed ? `stmt-amount stmt-${t.type}` : "stmt-amount";
    return `
      <tr>
        <td>${formatDate(t.transaction_date)}</td>
        <td>${escapeHtml(t.category)}</td>
        <td>${escapeHtml(t.description || "-")}</td>
        <td class="stmt-evidence-cell">${photo}</td>
        <td class="${amountClass}">${amountText}</td>
      </tr>`;
  }

  /** One coloured band: header row + line items + subtotal row. */
  function typeBand(title, color, rows) {
    if (!rows.length) return "";
    const total = rows.reduce((s, t) => s + parseFloat(t.amount), 0);
    return `
      <tr class="stmt-band-head" style="background:${color};"><td colspan="5">${title}</td></tr>
      ${rows.map(fmtRow).join("")}
      <tr class="stmt-subtotal"><td colspan="4">Total ${title.toLowerCase()}</td><td class="stmt-amount">${money(total)}</td></tr>
    `;
  }

  function singleMethodBody(rows) {
    const income = rows.filter((t) => t.type === "income");
    const expense = rows.filter((t) => t.type === "expense");
    const net = income.reduce((s, t) => s + parseFloat(t.amount), 0) - expense.reduce((s, t) => s + parseFloat(t.amount), 0);
    return `
      ${typeBand("Income", INCOME_COLOR, income)}
      ${typeBand("Expenses", EXPENSE_COLOR, expense)}
      <tr class="stmt-net" style="background:${INCOME_COLOR};"><td colspan="4">Net</td><td class="stmt-amount">${money(net)}</td></tr>
    `;
  }

  function combinedBody(rows) {
    let body = "";
    let grandNet = 0;
    METHOD_ORDER.forEach((method) => {
      const methodRows = rows.filter((t) => t.method === method);
      if (!methodRows.length) return;
      const income = methodRows.filter((t) => t.type === "income").reduce((s, t) => s + parseFloat(t.amount), 0);
      const expense = methodRows.filter((t) => t.type === "expense").reduce((s, t) => s + parseFloat(t.amount), 0);
      const net = income - expense;
      grandNet += net;
      body += `<tr class="stmt-band-head" style="background:${METHOD_COLOR[method]};"><td colspan="5">${METHOD_LABEL[method]}</td></tr>`;
      body += methodRows.map((t) => fmtRow(t, true)).join("");
      body += `<tr class="stmt-subtotal"><td colspan="4">${METHOD_LABEL[method]} net</td><td class="stmt-amount">${money(net)}</td></tr>`;
    });
    body += `<tr class="stmt-net" style="background:#1f2937;"><td colspan="4">Grand net</td><td class="stmt-amount">${money(grandNet)}</td></tr>`;
    return body;
  }

  const STATEMENT_CSS = `
    body { font-family: 'Segoe UI', Arial, sans-serif; padding: 28px; color: #1a1a1a; background: #fff; }
    h1 { font-size: 19px; margin: 0 0 2px; }
    p.meta { color: #555; font-size: 12px; margin: 0 0 20px; }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { background: #1f2937; color: #fff; padding: 8px 10px; text-align: left; }
    th:last-child, td.stmt-amount { text-align: right; }
    td { padding: 7px 10px; border-bottom: 1px solid #eee; vertical-align: middle; }
    tr.stmt-band-head td { color: #fff; font-weight: 700; font-size: 12.5px; padding: 9px 10px; letter-spacing: 0.02em; }
    tr.stmt-subtotal td { background: #f3f4f6; font-weight: 700; border-top: 1.5px solid #333; border-bottom: none; }
    tr.stmt-net td { color: #fff; font-weight: 800; font-size: 13.5px; padding: 10px; }
    .stmt-income { color: #0f766e; }
    .stmt-expense { color: #b45309; }
    .stmt-evidence-cell { text-align: center; }
    .stmt-evidence img { width: 42px; height: 42px; object-fit: cover; border-radius: 6px; border: 2px solid #d1d5db; display: block; margin: 0 auto; }
    .stmt-no-evidence { color: #bbb; }
    @media print { body { padding: 12px; } }
  `;

  function statementDocument(kind, rows, start, end) {
    const title = kind === "all" ? "Combined Statement" : `${METHOD_LABEL[kind]} Movement Statement`;
    const bodyHtml = kind === "all" ? combinedBody(rows) : singleMethodBody(rows);
    return `
      <html><head><title>${title}</title><style>${STATEMENT_CSS}</style></head><body>
        <h1>${title}</h1>
        <p class="meta">${escapeHtml($("#schoolLine").textContent)} &middot; ${formatDate(start.toISOString())} – ${formatDate(end.toISOString())}</p>
        <table>
          <thead><tr><th>Date</th><th>Category</th><th>Description</th><th>Evidence</th><th>Amount</th></tr></thead>
          <tbody>${bodyHtml || `<tr><td colspan="5" style="text-align:center;color:#999;padding:20px;">No transactions in this range.</td></tr>`}</tbody>
        </table>
      </body></html>
    `;
  }

  // Statements open in an in-app full-screen viewer, never in an external browser
  let viewerEl = null;
  let viewerHtml = "";
  let viewerTitle = "";

  function printStatement() {
    if (window.KnowApp && window.KnowApp.printHtml) {
      window.KnowApp.printHtml(viewerHtml, viewerTitle);
      return;
    }
    const frame = viewerEl && viewerEl.querySelector("iframe");
    if (frame && frame.contentWindow) frame.contentWindow.print();
  }

  function closeStatementViewer() {
    if (viewerEl) viewerEl.remove();
    viewerEl = null;
  }

  function openStatementWindow(kind, autoPrint) {
    const range = statementRange();
    if (!range) return;
    const rows = transactionsForStatement(kind, range.start, range.end);
    viewerTitle = kind === "all" ? "Combined Statement" : `${METHOD_LABEL[kind]} Movement Statement`;
    viewerHtml = statementDocument(kind, rows, range.start, range.end);
    closeStatementViewer();
    viewerEl = document.createElement("div");
    viewerEl.className = "stmt-viewer";
    viewerEl.innerHTML = `
      <div class="stmt-viewer-bar">
        <button type="button" class="ghost-btn" data-stmt-close>&larr; Back</button>
        <strong>${escapeHtml(viewerTitle)}</strong>
        <button type="button" class="primary-btn" data-stmt-print>Print / Save PDF</button>
      </div>
      <iframe class="stmt-viewer-frame" title="${escapeHtml(viewerTitle)}"></iframe>`;
    document.body.appendChild(viewerEl);
    viewerEl.querySelector("iframe").srcdoc = viewerHtml;
    viewerEl.querySelector("[data-stmt-close]").addEventListener("click", closeStatementViewer);
    viewerEl.querySelector("[data-stmt-print]").addEventListener("click", printStatement);
    if (autoPrint) setTimeout(printStatement, 400);
  }

  // Clicking the row itself opens a preview of the well-organised table.
  $$(".statement-btn[data-statement]").forEach((btn) => {
    btn.addEventListener("click", () => openStatementWindow(btn.dataset.statement, false));
  });

  // The dedicated print icon opens the same table and triggers Print immediately.
  $$("[data-print]").forEach((btn) => {
    btn.addEventListener("click", () => openStatementWindow(btn.dataset.print, true));
  });

  $$("[data-download]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const range = statementRange();
      if (!range) return;
      const method = btn.dataset.download;
      if (window.KnowApp && window.KnowApp.printHtml) {
        openStatementWindow(method, true);
        toast("Choose Save as PDF in the print dialog.");
        return;
      }
      btn.disabled = true;
      try {
        const blob = await Api.reports.downloadStatementPdf({
          method: method === "all" ? null : method,
          start: range.start,
          end: range.end,
        });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = `${method}-statement.pdf`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
      } catch (err) {
        toast(err.status === 0 ? "PDF export needs a connection — use the print icon instead to view/print this statement offline." : (err.message || "Could not generate the statement."));
      } finally {
        btn.disabled = false;
      }
    });
  });

  /* ---------------- Audit reports (title/period, finalize, PDF) ---------------- */

  const recordsView = $("#auditRecordsView");
  const detailView = $("#auditDetailView");

  function showRecords() {
    recordsView.classList.add("is-active");
    detailView.classList.remove("is-active");
    history.replaceState(null, "", "audit.html");
  }

  async function showDetail(id) {
    recordsView.classList.remove("is-active");
    detailView.classList.add("is-active");
    history.replaceState(null, "", `audit.html?id=${id}`);
    await loadDetail(id);
  }

  $("#backToAudits").addEventListener("click", showRecords);

  async function loadAuditGrid() {
    const grid = $("#auditGrid");
    let audits;
    try {
      audits = await Api.audits.list();
      await Offline.cacheSet("audits", audits);
    } catch (err) {
      if (err.status !== 0) {
        grid.innerHTML = `<div class="empty-state">${escapeHtml(err.message)}</div>`;
        return;
      }
      audits = (await Offline.cacheGet("audits")) || [];
    }
    grid.innerHTML = audits.length ? audits.map((a) => `
      <button class="audit-card" data-open="${a.id}">
        <h3>${escapeHtml(a.title)}</h3>
        <span class="subtle">${formatDate(a.period_start)} – ${formatDate(a.period_end)}</span>
        <span class="badge badge-${a.status}">${a.status}</span>
        <span class="metric">${a.finalized_at ? "Finalized" : "In progress"}</span>
      </button>
    `).join("") : `<div class="empty-state"><div class="display">No audit reports yet</div><p>Create one to generate a signed-off PDF for a period.</p></div>`;

    $$("[data-open]", grid).forEach((btn) => btn.addEventListener("click", () => showDetail(parseInt(btn.dataset.open, 10))));
  }

  async function loadDetail(id) {
    let audit, transactions;
    try {
      [audit, transactions] = await Promise.all([Api.audits.get(id), Api.audits.transactions(id)]);
      await Offline.cacheSet(`audit_${id}`, audit);
      await Offline.cacheSet(`audit_txns_${id}`, transactions);
    } catch (err) {
      if (err.status === 0) {
        audit = await Offline.cacheGet(`audit_${id}`);
        transactions = await Offline.cacheGet(`audit_txns_${id}`);
        if (!audit) {
          $("#detailTitle").textContent = "Not available offline";
          $("#detailPeriod").textContent = "This audit hasn't been viewed on this device yet, so there's nothing cached to show offline.";
          return;
        }
      } else {
        $("#detailTitle").textContent = "Not found";
        $("#detailPeriod").textContent = err.message;
        return;
      }
    }

    $("#detailTitle").textContent = audit.title;
    $("#detailPeriod").textContent = `${formatDate(audit.period_start)} – ${formatDate(audit.period_end)}`;
    const statusEl = $("#detailStatus");
    statusEl.textContent = audit.status;
    statusEl.className = `pill ${audit.status === "finalized" ? "ok" : "warn"}`;

    const income = transactions.filter((t) => t.type === "income").reduce((s, t) => s + parseFloat(t.amount), 0);
    const expense = transactions.filter((t) => t.type === "expense").reduce((s, t) => s + parseFloat(t.amount), 0);

    $("#detailKpis").innerHTML = `
      <div class="kpi"><strong>${money(income)}</strong><span>Total income</span></div>
      <div class="kpi"><strong>${money(expense)}</strong><span>Total expense</span></div>
      <div class="kpi"><strong>${money(income - expense)}</strong><span>Net</span></div>
      <div class="kpi"><strong>${transactions.length}</strong><span>Transactions</span></div>
    `;

    $("#detailTxBody").innerHTML = transactions.length ? transactions.map((t) => `
      <tr>
        <td>${formatDate(t.transaction_date)}</td>
        <td><span class="badge badge-${t.type}">${t.type}</span></td>
        <td>${methodLabel(t.method)}</td>
        <td>${escapeHtml(t.category)}</td>
        <td>${money(t.amount)}</td>
      </tr>
    `).join("") : `<tr class="empty-row"><td colspan="5">No transactions fell inside this period.</td></tr>`;

    const finalizeBtn = $("#finalizeBtn");
    const finalizedHint = $("#finalizedHint");
    const editAuditBtn = $("#editAuditBtn");
    const deleteAuditBtn = $("#deleteAuditBtn");

    if (audit.status === "finalized") {
      finalizeBtn.style.display = "none";
      finalizedHint.style.display = "block";
      editAuditBtn.style.display = "none";
      deleteAuditBtn.style.display = "none";
    } else {
      finalizeBtn.style.display = "";
      finalizedHint.style.display = "none";
      editAuditBtn.style.display = "";
      deleteAuditBtn.style.display = "";

      finalizeBtn.onclick = async () => {
        if (!confirm("Finalize this audit? It will be locked from further edits.")) return;
        finalizeBtn.disabled = true;
        finalizeBtn.textContent = "Finalizing…";
        try {
          await Api.audits.finalize(id);
          await loadDetail(id);
          toast("Audit finalized.");
        } catch (err) {
          toast(err.message || "Could not finalize the audit.");
        } finally {
          finalizeBtn.disabled = false;
          finalizeBtn.textContent = "Finalize audit";
        }
      };

      editAuditBtn.onclick = () => openEditAuditSheet(audit);

      deleteAuditBtn.onclick = async () => {
        if (!confirm("Delete this audit? This cannot be undone.")) return;
        deleteAuditBtn.disabled = true;
        try {
          await Api.audits.remove(id);
          toast("Audit deleted.");
          await loadAuditGrid();
          showRecords();
        } catch (err) {
          toast(err.message || "Could not delete the audit.");
          deleteAuditBtn.disabled = false;
        }
      };
    }

    $("#downloadReportBtn").onclick = async () => {
      const btn = $("#downloadReportBtn");
      btn.disabled = true;
      btn.textContent = "Generating…";
      try {
        const blob = await Api.reports.downloadAuditPdf(id);
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = `audit-report-${id}.pdf`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
      } catch (err) {
        toast(err.status === 0 ? "The formatted PDF needs a connection — try \"Download CSV\" instead while offline." : (err.message || "Could not generate the report."));
      } finally {
        btn.disabled = false;
        btn.textContent = "Download report (PDF)";
      }
    };

    $("#downloadCsvBtn").onclick = () => {
      // Built entirely from data already on this device — works offline.
      const blob = Offline.csvFromTransactions(transactions, {
        title: audit.title,
        period: `${formatDate(audit.period_start)} – ${formatDate(audit.period_end)}`,
      });
      Offline.downloadBlob(blob, `audit-${id}-transactions.csv`);
    };
  }

  function openEditAuditSheet(audit) {
    Sheet.open("Edit audit", `
      <p class="form-error" id="auditEditMsg"></p>
      <form id="auditEditForm">
        <label class="field"><span>Title</span><input type="text" id="auditEditTitle" required /></label>
        <div class="field-row">
          <label class="field"><span>Period start</span><input type="date" id="auditEditStart" required /></label>
          <label class="field"><span>Period end</span><input type="date" id="auditEditEnd" required /></label>
        </div>
        <label class="field"><span>Summary (optional)</span><input type="text" id="auditEditSummary" /></label>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="auditEditSubmit">Save changes</button>
        </div>
      </form>
    `);

    $("#auditEditTitle").value = audit.title;
    $("#auditEditStart").valueAsDate = new Date(audit.period_start);
    $("#auditEditEnd").valueAsDate = new Date(audit.period_end);
    $("#auditEditSummary").value = audit.summary || "";

    $("#auditEditForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#auditEditMsg");
      hideFormMessage(msg);
      const submitBtn = $("#auditEditSubmit");

      const start = $("#auditEditStart").value;
      const end = $("#auditEditEnd").value;
      if (start > end) {
        showFormMessage(msg, "Period start must be before period end.");
        return;
      }

      submitBtn.disabled = true;
      submitBtn.textContent = "Saving…";
      try {
        await Api.audits.update(audit.id, {
          title: $("#auditEditTitle").value.trim(),
          period_start: new Date(start + "T00:00:00Z").toISOString(),
          period_end: new Date(end + "T23:59:59Z").toISOString(),
          summary: $("#auditEditSummary").value.trim() || null,
        });
        Sheet.close();
        toast("Audit updated.");
        await loadAuditGrid();
        await loadDetail(audit.id);
      } catch (err) {
        showFormMessage(msg, err.message || "Could not save the changes.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Save changes";
      }
    });
  }

  $("#newAuditBtn").addEventListener("click", () => {
    Sheet.open("New audit", `
      <p class="form-error" id="auditMsg"></p>
      <form id="auditForm">
        <label class="field"><span>Title</span><input type="text" id="auditTitle" required /></label>
        <div class="field-row">
          <label class="field"><span>Period start</span><input type="date" id="auditStart" required /></label>
          <label class="field"><span>Period end</span><input type="date" id="auditEnd" required /></label>
        </div>
        <label class="field"><span>Summary (optional)</span><input type="text" id="auditSummary" /></label>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="auditSubmit">Create audit</button>
        </div>
      </form>
    `);

    $("#auditForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#auditMsg");
      hideFormMessage(msg);
      const submitBtn = $("#auditSubmit");

      const start = $("#auditStart").value;
      const end = $("#auditEnd").value;
      if (start > end) {
        showFormMessage(msg, "Period start must be before period end.");
        return;
      }

      submitBtn.disabled = true;
      submitBtn.textContent = "Creating…";
      try {
        const created = await Api.audits.create({
          title: $("#auditTitle").value.trim(),
          period_start: new Date(start + "T00:00:00Z").toISOString(),
          period_end: new Date(end + "T23:59:59Z").toISOString(),
          summary: $("#auditSummary").value.trim() || null,
        });
        Sheet.close();
        await loadAuditGrid();
        await showDetail(created.id);
      } catch (err) {
        showFormMessage(msg, err.message || "Could not create the audit.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Create audit";
      }
    });
  });

  /* ---------------- boot ---------------- */

  const params = new URLSearchParams(location.search);
  const auditId = params.get("id");
  if (auditId) {
    recordsView.classList.remove("is-active");
    detailView.classList.add("is-active");
    await loadDetail(parseInt(auditId, 10));
  }
})();