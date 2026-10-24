(async function () {
  await initShell("subscription", ["staff", "institution_admin"]);
  const root = $("#subRoot");
  try {
    const s = await Api.billingStatus();
    root.innerHTML = s.applies
      ? BillingView.render(s)
      : "<div class=\"sub-panel\"><h3>Subscription</h3><p>Your account is managed by your institution. There is nothing for you to pay here.</p></div>";
  } catch (e) {
    root.innerHTML = "<p class=\"form-error visible\">Could not load your subscription. Try again.</p>";
  }
})();
