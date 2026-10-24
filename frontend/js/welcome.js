(async function () {
  const session = Session.get();
  if (!session) { location.href = "login.html"; return; }
  const root = document.getElementById("welcomeRoot");
  const first = escapeHtml((session.full_name || "").split(" ")[0] || "there");
  const go = () => { location.href = homePageFor(session.role); };
  try {
    const s = await Api.billingStatus();
    if (!s.applies) { go(); return; }
    root.innerHTML = `<p class="sub-welcome">Welcome, ${first}</p>`
      + BillingView.hero(s, "Your account is ready")
      + `<div class="sub-panel sub-callout"><strong>You are on a ${s.trial_days}-day free trial.</strong>
          <p>You will not pay anything until <b>${BillingView.fmt(s.trial_ends_at)}</b>. After that your ${s.plan_label} plan is <b>${BillingView.kes(s.base_amount)} / month</b>${s.plan === "owner" ? `, which includes ${s.included_accounts} individual accounts (then ${BillingView.kes(s.extra_rate)} for each extra one)` : ""}.</p></div>`
      + BillingView.timeline(s)
      + `<button class="primary-btn auth-submit" id="welcomeGo" type="button">Get started</button>
         <p class="hint">Using the Android app? Log in there with the same username and password.</p>`;
    document.getElementById("welcomeGo").addEventListener("click", go);
  } catch (e) { go(); }
})();
