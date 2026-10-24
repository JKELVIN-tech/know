(async function () {
  await initShell("subscription", ["staff"]);
  const card = $("#subCard");
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
    $("#subNote").textContent = s.note;
    $("#subPlan").hidden = false;
  } catch (e) {
    card.innerHTML = "<p class=\"form-error visible\">Could not load your subscription. Try again.</p>";
  }
})();
