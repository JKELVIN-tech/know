(async function () {
  await initShell("profit", ["institution_admin"]);
  const todayKenya = () => new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Nairobi" });
  let day = todayKenya();
  let costs = [];

  const dayEl = $("#pfDay");
  const branchEl = $("#pfBranch");
  dayEl.value = day;

  try {
    const branches = await Api.exchange.branches();
    branchEl.innerHTML += branches.map((b) => `<option value="${b.id}">${escapeHtml(b.name)}</option>`).join("");
  } catch { /* the filter simply stays on all branches */ }

  const shift = (n) => {
    const d = new Date(day + "T12:00:00");
    d.setDate(d.getDate() + n);
    day = d.toISOString().slice(0, 10);
    dayEl.value = day;
    load();
  };
  $("#pfPrev").addEventListener("click", () => shift(-1));
  $("#pfNext").addEventListener("click", () => shift(1));
  $("#pfToday").addEventListener("click", () => { day = todayKenya(); dayEl.value = day; load(); });
  dayEl.addEventListener("change", () => { if (dayEl.value) { day = dayEl.value; load(); } });
  branchEl.addEventListener("change", load);

  const tone = (v) => (Number(v) > 0 ? "pos" : Number(v) < 0 ? "neg" : "");
  const pct = (m) => (m == null ? "—" : `${m}%`);

  function itemRow(i) {
    const flag = Number(i.unknown_units) > 0 ? `<span class="pill warn">${escapeHtml(i.unknown_units)} without cost</span>` : "";
    return `<li class="pf-row"><div><strong>${escapeHtml(i.name)}</strong><small>${escapeHtml(i.units)} sold · sales ${money(i.revenue)} · cost ${money(i.cost)} ${flag}</small></div><div class="pf-right"><strong class="${tone(i.profit)}">${money(i.profit)}</strong><small>${pct(i.margin)}</small></div></li>`;
  }

  async function load() {
    const params = new URLSearchParams({ day });
    if (branchEl.value) params.set("branch_id", branchEl.value);
    let data;
    try {
      data = await apiFetch(`/api/profit/daily?${params}`);
    } catch (err) {
      toast(err.status === 0 ? "You need a connection." : err.message || "Could not load profit.");
      return;
    }
    $("#pfDayLabel").textContent = new Date(day + "T12:00:00").toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short", year: "numeric" });
    const t = data.totals;
    $("#pfSales").textContent = money(t.revenue);
    $("#pfCost").textContent = money(t.cost);
    $("#pfProfit").textContent = money(t.profit);
    $("#pfProfit").className = tone(t.profit);
    $("#pfMargin").textContent = pct(t.margin);
    const warn = $("#pfWarn");
    warn.hidden = !(Number(t.unknown_units) > 0);
    warn.textContent = `${t.unknown_units} units were sold without a known cost, so their profit is not counted. Set their cost below.`;
    $("#pfBranches").innerHTML = data.branches.length ? data.branches.map((b) => `
      <div class="card">
        <div class="card-head"><h2>${escapeHtml(b.branch_name)}</h2><span class="pill ${Number(b.totals.profit) >= 0 ? "ok" : "warn"}">${money(b.totals.profit)} profit</span></div>
        <p class="subtle">Sales ${money(b.totals.revenue)} · cost ${money(b.totals.cost)} · margin ${pct(b.totals.margin)}</p>
        <ul class="txn-list">${b.items.map(itemRow).join("")}</ul>
      </div>`).join("") : `<div class="card"><p class="subtle">No stock sales on this day.</p></div>`;
  }

  async function loadCosts() {
    try { costs = await apiFetch("/api/profit/costs"); } catch { costs = []; }
    const missing = costs.filter((c) => c.avg_cost == null).length;
    $("#pfCosts").innerHTML = costs.length ? costs.map((c) => `
      <li class="pf-row"><div><strong>${escapeHtml(c.name)}</strong><small>${escapeHtml(c.branch_name || "Unassigned")} · price ${c.unit_price != null ? money(c.unit_price) : "—"} · ${escapeHtml(c.quantity)} in stock</small></div>
        <div class="pf-right">${c.avg_cost == null ? `<span class="pill warn">No cost</span>` : `<strong>${money(c.avg_cost)}</strong>`}<button type="button" class="ghost-btn" data-cost="${c.item_id}">${c.avg_cost == null ? "Set cost" : "Edit"}</button></div></li>`).join("") : `<li class="subtle">No stock items yet.</li>`;
    $("#pfCostCard .card-head .subtle").textContent = missing ? `${missing} without a cost` : "What one unit costs you";
  }

  $("#pfCosts").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-cost]");
    if (!btn) return;
    const item = costs.find((c) => String(c.item_id) === btn.dataset.cost);
    Sheet.open(`Cost of ${item.name}`, `
      <p class="form-error" id="costMsg"></p>
      <form id="costForm">
        <label class="field"><span>Cost of one unit</span><input type="number" id="costValue" min="0" step="0.01" required value="${item.avg_cost ?? ""}" /></label>
        <p class="file-hint">Used for sales from now on. Past sales keep the cost they had.</p>
        <div class="form-actions"><button type="submit" class="primary-btn" id="costSave">Save cost</button></div>
      </form>`);
    $("#costValue").focus();
    $("#costForm").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = $("#costMsg");
      hideFormMessage(msg);
      $("#costSave").disabled = true;
      try {
        await apiFetch(`/api/profit/costs/${item.item_id}`, { method: "PATCH", body: { avg_cost: $("#costValue").value } });
        Sheet.close();
        toast("Cost saved.");
        await Promise.all([loadCosts(), load()]);
      } catch (err) {
        showFormMessage(msg, err.message || "Could not save the cost.");
        $("#costSave").disabled = false;
      }
    });
  });

  await Promise.all([load(), loadCosts()]);
  onFreshData(() => { load(); loadCosts(); });
})();
