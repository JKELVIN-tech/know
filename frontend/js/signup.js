const err = document.getElementById("authError");
const stepPlan = document.getElementById("stepPlan");
const stepForm = document.getElementById("stepForm");
let plan = "individual";

function choosePlan(next) {
  plan = next;
  const owner = plan === "owner";
  document.getElementById("ownerFields").hidden = !owner;
  document.getElementById("useForField").hidden = owner;
  document.getElementById("signupBusiness").required = owner;
  document.getElementById("formTitle").textContent = owner ? "Create your owner account" : "Create your individual account";
  document.getElementById("formHint").textContent = owner
    ? "KES 500/month after your free week. Includes 5 individual accounts, then KES 200 each."
    : "KES 200/month after your free week. This account cannot create other accounts.";
  stepPlan.hidden = true;
  stepForm.hidden = false;
  err.classList.remove("visible");
}

document.querySelectorAll(".plan-card").forEach((card) => card.addEventListener("click", () => choosePlan(card.dataset.plan)));
document.getElementById("changePlan").addEventListener("click", () => { stepForm.hidden = true; stepPlan.hidden = false; });

document.getElementById("signupForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  err.classList.remove("visible");
  const btn = document.getElementById("signupSubmit");
  btn.disabled = true;
  const owner = plan === "owner";
  try {
    const data = await Api.signup({
      account_type: owner ? "owner" : document.getElementById("signupType").value,
      business_name: owner ? document.getElementById("signupBusiness").value.trim() : null,
      business_type: owner ? document.getElementById("signupBusinessType").value : null,
      full_name: document.getElementById("signupName").value.trim(),
      username: document.getElementById("signupUsername").value.trim(),
      email: document.getElementById("signupEmail").value.trim() || null,
      partner_code: document.getElementById("signupPartner").value.trim() || null,
      password: document.getElementById("signupPassword").value,
    });
    const { user_id, role, staff_type, full_name, institution_id, access_token } = data;
    TokenStore.set(access_token);
    Session.set({ user_id, role, staff_type, full_name, institution_id });
    location.href = "welcome.html";
  } catch (error) {
    err.textContent = error.status === 429 ? "Too many sign-ups from this connection. Please try again later." : (error.message || "Could not create the account. Try again.");
    err.classList.add("visible");
    btn.disabled = false;
  }
});
