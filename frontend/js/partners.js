(async function () {
  await initShell("partners", ["super_admin"]);
  let agents = [];
  const list = $("#pList");

  async function load() {
    try { agents = await apiFetch("/api/admin/partners"); } catch (err) { list.innerHTML = `<div class="card"><p class="subtle">${escapeHtml(err.message || "Could not load agents.")}</p></div>`; return; }
    const sum = (k) => agents.reduce((n, a) => n + Number(a[k]), 0);
    $("#pAgents").textContent = agents.length;
    $("#pBiz").textContent = agents.reduce((n, a) => n + a.businesses, 0);
    $("#pEarned").textContent = money(sum("earned_total"));
    $("#pOwed").textContent = money(sum("balance_due"));
    list.innerHTML = agents.length ? agents.map((a) => `
      <div class="card">
        <div class="card-head"><h2>${escapeHtml(a.name)}</h2><span class="pill ${a.is_active ? "ok" : "muted"}">${a.is_active ? "Active" : "Switched off"}</span></div>
        <p class="subtle">${escapeHtml(a.username)} · ${escapeHtml(a.email || "no email")}${a.phone ? " · " + escapeHtml(a.phone) : ""}</p>
        <div class="code-box small">${escapeHtml(a.code)}</div>
        <div class="kpi-row">
          <div class="kpi"><strong>${Number(a.commission_rate)}%</strong><span>Rate</span></div>
          <div class="kpi"><strong>${a.businesses}</strong><span>Businesses</span></div>
          <div class="kpi"><strong>${money(a.earned_total)}</strong><span>Earned</span></div>
          <div class="kpi kpi-accent"><strong>${money(a.balance_due)}</strong><span>Owed</span></div>
        </div>
        <div class="head-actions" style="margin-top:12px">
          <button type="button" class="primary-btn" data-payout="${a.id}">Record payout</button>
          <button type="button" class="ghost-btn" data-edit="${a.id}">Edit</button>
          <button type="button" class="ghost-btn" data-newcode="${a.id}">New code</button>
        </div>
      </div>`).join("") : `<div class="card"><p class="subtle">No agents yet. Add one, give them their code, and enter that code when you register their businesses.</p></div>`;
  }

  $("#newPartner").addEventListener("click", () => {
    Sheet.open("New agent", `
      <p class="form-error" id="paMsg"></p>
      <form id="paForm">
        <label class="field"><span>Full name</span><input type="text" id="paName" required /></label>
        <label class="field"><span>Username</span><input type="text" id="paUser" required autocomplete="off" /></label>
        <label class="field"><span>Email (for password reset links)</span><input type="email" id="paEmail" required autocomplete="off" /></label>
        <label class="field"><span>Phone (optional)</span><input type="tel" id="paPhone" /></label>
        <label class="field"><span>Commission (% of each monthly fee)</span><input type="number" id="paRate" min="0" max="100" step="0.5" value="20" required /></label>
        <label class="field"><span>Temporary password</span><input type="text" id="paPass" required /></label>
        <ul class="pw-rules" id="paRules"></ul>
        <button type="button" class="ghost-btn" id="paGen">Generate a strong password</button>
        <div class="form-actions"><button type="submit" class="primary-btn" id="paSave">Create agent</button></div>
      </form>`);
    PasswordRules.attach($("#paPass"), $("#paRules"), () => $("#paUser").value.trim());
    $("#paGen").addEventListener("click", () => { $("#paPass").value = PasswordRules.generate(); $("#paPass").dispatchEvent(new Event("input")); });
    $("#paForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#paMsg"); hideFormMessage(msg); $("#paSave").disabled = true;
      try {
        const a = await apiFetch("/api/admin/partners", { method: "POST", body: { full_name: $("#paName").value.trim(), username: $("#paUser").value.trim(), email: $("#paEmail").value.trim(), phone: $("#paPhone").value.trim() || null, commission_rate: $("#paRate").value, password: $("#paPass").value } });
        Sheet.close();
        alert(`Agent created.\n\nPartner code: ${a.code}\nUsername: ${a.username}\nTemporary password: ${$("#paPass").value || "(as entered)"}\n\nShare these with the agent privately.`);
        await load();
      } catch (err) { showFormMessage(msg, err.message || "Could not create the agent."); $("#paSave").disabled = false; }
    });
  });

  list.addEventListener("click", async (e) => {
    const payout = e.target.closest("[data-payout]"), edit = e.target.closest("[data-edit]"), code = e.target.closest("[data-newcode]");
    if (payout) openPayout(agents.find((a) => String(a.id) === payout.dataset.payout));
    else if (edit) openEdit(agents.find((a) => String(a.id) === edit.dataset.edit));
    else if (code) {
      const a = agents.find((x) => String(x.id) === code.dataset.newcode);
      if (!confirm(`Replace ${a.name}'s partner code? The old code stops working. Businesses already registered stay with them.`)) return;
      try { const r = await apiFetch(`/api/admin/partners/${a.id}/new-code`, { method: "POST" }); toast(`New code: ${r.code}`); await load(); } catch (err) { toast(err.message || "Could not change the code."); }
    }
  });

  async function openPayout(a) {
    let past = [];
    try { past = await apiFetch(`/api/admin/partners/${a.id}/payouts`); } catch { past = []; }
    Sheet.open(`Payout to ${a.name}`, `
      <p class="form-error" id="poMsg"></p>
      <p class="subtle">Owed now: <strong>${money(a.balance_due)}</strong></p>
      <form id="poForm">
        <label class="field"><span>Amount paid</span><input type="number" id="poAmount" min="0.01" step="0.01" max="${a.balance_due}" required value="${a.balance_due}" /></label>
        <label class="field"><span>Date</span><input type="date" id="poDate" value="${new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Nairobi" })}" /></label>
        <label class="field"><span>M-Pesa code or reference</span><input type="text" id="poRef" maxlength="80" /></label>
        <div class="form-actions"><button type="submit" class="primary-btn" id="poSave">Record payout</button></div>
      </form>
      <h3>Earlier payouts</h3>
      ${past.length ? past.map((p) => `<div class="ex-row"><div><strong>${money(p.amount)}</strong><small>${formatDate(p.paid_on)}${p.reference ? " · " + escapeHtml(p.reference) : ""}${p.voided ? " · voided" : ""}</small></div>${p.voided ? "" : `<button type="button" class="ghost-btn" data-void="${p.id}">Void</button>`}</div>`).join("") : `<p class="subtle">None yet.</p>`}`);
    $("#poForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#poMsg"); hideFormMessage(msg); $("#poSave").disabled = true;
      try {
        await apiFetch(`/api/admin/partners/${a.id}/payouts`, { method: "POST", body: { amount: $("#poAmount").value, paid_on: $("#poDate").value, reference: $("#poRef").value.trim() || null } });
        Sheet.close(); toast("Payout recorded."); await load();
      } catch (err) { showFormMessage(msg, err.message || "Could not record the payout."); $("#poSave").disabled = false; }
    });
    $("#sheetBody").addEventListener("click", async (e) => {
      const v = e.target.closest("[data-void]");
      if (!v || !confirm("Void this payout? It will count as unpaid again.")) return;
      try { await apiFetch(`/api/admin/payouts/${v.dataset.void}/void`, { method: "POST" }); Sheet.close(); toast("Payout voided."); await load(); } catch (err) { toast(err.message || "Could not void it."); }
    });
  }

  function openEdit(a) {
    Sheet.open(`Edit ${a.name}`, `
      <p class="form-error" id="peMsg"></p>
      <form id="peForm">
        <label class="field"><span>Commission (% of each monthly fee)</span><input type="number" id="peRate" min="0" max="100" step="0.5" value="${Number(a.commission_rate)}" required /></label>
        <p class="file-hint">A new rate applies to fees paid from now on. Earlier commissions keep the rate they were earned at.</p>
        <label class="field"><span>Phone</span><input type="tel" id="pePhone" value="${escapeHtml(a.phone || "")}" /></label>
        <label class="field"><span>Status</span><select id="peActive"><option value="1" ${a.is_active ? "selected" : ""}>Active</option><option value="0" ${a.is_active ? "" : "selected"}>Switched off (cannot sign in, code stops working)</option></select></label>
        <div class="form-actions"><button type="submit" class="primary-btn" id="peSave">Save</button></div>
      </form>`);
    $("#peForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#peMsg"); hideFormMessage(msg); $("#peSave").disabled = true;
      try {
        await apiFetch(`/api/admin/partners/${a.id}`, { method: "PATCH", body: { commission_rate: $("#peRate").value, phone: $("#pePhone").value.trim(), is_active: $("#peActive").value === "1" } });
        Sheet.close(); toast("Saved."); await load();
      } catch (err) { showFormMessage(msg, err.message || "Could not save."); $("#peSave").disabled = false; }
    });
  }

  await load();
})();
