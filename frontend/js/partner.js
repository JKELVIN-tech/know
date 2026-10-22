(async function () {
  await initShell("partner", ["agent"]);
  let month = null;
  let data = null;
  let filter = "all";

  const label = (p) => new Date(p + "-01T12:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const shift = (p, n) => { const [y, m] = p.split("-").map(Number); const i = y * 12 + (m - 1) + n; return `${Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, "0")}`; };
  const STATUS = { active: ["ok", "Paying"], trial: ["info", "Trial"], overdue: ["warn", "Overdue"], unpaid: ["muted", "Not paid yet"] };

  async function load() {
    try {
      data = await apiFetch("/api/partner/dashboard" + (month ? `?month=${month}` : ""));
    } catch (err) {
      toast(err.status === 0 ? "You need a connection." : err.message || "Could not load your dashboard.");
      return;
    }
    month = data.month;
    render();
  }

  function render() {
    const p = data.profile;
    const t = data.totals;
    $("#ptCode").textContent = p.code;
    $("#ptRate").textContent = p.active ? `You earn ${Number(p.commission_rate)}% of every monthly fee your businesses pay.` : "This agent account is switched off. Contact KNOW.";
    $("#ptMonth").textContent = label(data.month);
    $("#ptNext").disabled = data.month >= data.current_month;
    $("#ptBiz").textContent = t.businesses;
    $("#ptPaying").textContent = t.paying_this_month;
    $("#ptEarned").textContent = money(t.earned_month);
    $("#ptBalance").textContent = money(t.balance_due);
    $("#ptTotals").textContent = `Fees collected this month ${money(t.fees_collected_month)} · Earned in total ${money(t.earned_total)} · Paid out to you ${money(t.paid_out)}`;

    const top = Math.max(...data.series.map((s) => Number(s.earned)), 1);
    $("#ptBars").innerHTML = data.series.map((s) => `
      <div class="bar-col ${s.period === data.month ? "is-now" : ""}">
        <span class="bar-val">${Number(s.earned) ? Number(s.earned).toLocaleString() : ""}</span>
        <div class="bar" style="height:${Math.max(4, (Number(s.earned) / top) * 100)}%"></div>
        <span class="bar-lbl">${new Date(s.period + "-01T12:00:00").toLocaleDateString(undefined, { month: "short" })}</span>
      </div>`).join("");

    drawBusinesses();
    $("#ptHistory").innerHTML = data.recent_commissions.length ? data.recent_commissions.map((c) => `
      <div class="ex-row"><div><strong>${escapeHtml(c.business)}</strong><small>Fee for ${label(c.period)} · paid ${formatDate(c.paid_on)} · ${money(c.fee)} at ${Number(c.rate)}%</small></div><div><strong class="pos">+ ${money(c.commission)}</strong></div></div>`).join("") : `<p class="subtle">Your commissions will appear here once a business you registered pays its monthly fee.</p>`;
    $("#ptPayouts").innerHTML = data.payouts.length ? data.payouts.map((x) => `
      <div class="ex-row"><div><strong>${money(x.amount)}</strong><small>${formatDate(x.paid_on)}${x.reference ? " · " + escapeHtml(x.reference) : ""}</small></div></div>`).join("") : `<p class="subtle">No payouts yet.</p>`;
  }

  function drawBusinesses() {
    const q = $("#ptSearch").value.trim().toLowerCase();
    const counts = { all: data.businesses.length };
    data.businesses.forEach((b) => { counts[b.status] = (counts[b.status] || 0) + 1; });
    $("#ptChips").innerHTML = ["all", "active", "trial", "overdue", "unpaid"].filter((k) => k === "all" || counts[k]).map((k) =>
      `<button type="button" class="chip ${filter === k ? "is-active" : ""}" data-filter="${k}">${k === "all" ? "All" : STATUS[k][1]} ${counts[k] || 0}</button>`).join("");
    const rows = data.businesses.filter((b) => (filter === "all" || b.status === filter) && (!q || [b.name, b.region || "", b.type].some((v) => v.toLowerCase().includes(q))));
    $("#ptCount").textContent = String(rows.length);
    $("#ptList").innerHTML = data.businesses.length ? (rows.length ? rows.map((b) => {
      const [tone, text] = STATUS[b.status] || ["muted", b.status];
      return `<div class="biz-row">
        <div class="biz-avatar">${escapeHtml((b.name[0] || "?").toUpperCase())}</div>
        <div class="biz-main"><strong>${escapeHtml(b.name)}</strong><small>${escapeHtml(b.type)}${b.region ? " · " + escapeHtml(b.region) : ""} · registered ${formatDate(b.registered_at)}${b.owner_name ? " · " + escapeHtml(b.owner_name) : ""}</small></div>
        <div class="biz-side"><span class="pill ${tone}">${text}</span><small>${b.monthly_fee ? money(b.monthly_fee) + " / month" : "Fee not set"}</small><small>You earned ${money(b.earned_total)}</small></div>
      </div>`;
    }).join("") : `<p class="subtle">No businesses match.</p>`) : `<div class="empty-state"><strong>No businesses yet</strong><p class="subtle">Give your partner code to a business when it registers. Every business that registers with your code shows up here, and you earn a share of its monthly fee.</p></div>`;
  }

  $("#ptChips").addEventListener("click", (e) => { const b = e.target.closest("[data-filter]"); if (b) { filter = b.dataset.filter; drawBusinesses(); } });
  $("#ptSearch").addEventListener("input", drawBusinesses);
  $("#ptPrev").addEventListener("click", () => { month = shift(month || data.current_month, -1); load(); });
  $("#ptNext").addEventListener("click", () => { month = shift(month || data.current_month, 1); load(); });
  $("#ptNow").addEventListener("click", () => { month = null; load(); });
  $("#ptCopy").addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(data.profile.code); toast("Code copied."); } catch { toast("Could not copy. Select the code and copy it."); }
  });
  $("#ptShare").addEventListener("click", async () => {
    const text = `Register your business on K.N.O.W. and use my partner code ${data.profile.code}.`;
    try { if (navigator.share) await navigator.share({ text }); else { await navigator.clipboard.writeText(text); toast("Message copied."); } } catch { /* cancelled */ }
  });

  await load();
  onFreshData(load);
})();
