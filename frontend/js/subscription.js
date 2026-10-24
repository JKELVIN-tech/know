(async function () {
  await initShell("subscription", ["staff", "institution_admin"]);
  const card = $("#subCard");
  const kes = (n) => "KES " + Number(n).toLocaleString("en-KE");
  try {
    const s = await Api.billingStatus();
    if (!s.applies) {
      card.innerHTML = "<h3>Subscription</h3><p>Your account is managed by your institution. There is nothing for you to pay here.</p>";
      return;
    }
    const trial = s.state === "trial";
    const ends = new Date(s.trial_ends_at).toLocaleDateString("en-KE", { day: "numeric", month: "long", year: "numeric", timeZone: "Africa/Nairobi" });
    card.innerHTML = trial
      ? `<h3>Free trial</h3><p class="sub-days">${s.days_left}</p><p class="subtle">${s.days_left === 1 ? "day" : "days"} left of your ${s.trial_days}-day free trial</p><p class="hint">Your trial ends on ${ends}.</p>`
      : `<h3>Your free trial has ended</h3><p class="subtle">It ended on ${ends}. Payments are not switched on yet, so you can keep using KNOW for now.</p>`;
    const owner = s.plan === "owner";
    $("#subPlanName").textContent = `${s.plan_label} plan`;
    $("#subPrice").textContent = `${kes(s.base_amount)} / month`;
    $("#subList").innerHTML = owner
      ? `<li>Sales, stock, audits and finance records for your business</li><li>Create individual accounts for your team</li><li>${s.included_accounts} individual accounts included, then ${kes(s.extra_rate)} for each extra one</li>`
      : "<li>Sales, stock and finance records</li><li>Works offline and syncs when you are back online</li><li>Cannot create other accounts</li>";
    if (owner) {
      const usage = $("#subUsage");
      usage.hidden = false;
      usage.innerHTML = `<div class="sub-row"><span>Individual accounts</span><strong>${s.accounts_used} of ${s.included_accounts} included</strong></div>`
        + (s.extra_accounts ? `<div class="sub-row"><span>${s.extra_accounts} extra x ${kes(s.extra_rate)}</span><strong>${kes(s.extra_amount)}</strong></div>` : "")
        + `<div class="sub-row sub-total"><span>Total per month</span><strong>${kes(s.total)}</strong></div>`;
    }
    $("#subNote").textContent = s.note;
    $("#subPlan").hidden = false;
  } catch (e) {
    card.innerHTML = "<p class=\"form-error visible\">Could not load your subscription. Try again.</p>";
  }
})();
