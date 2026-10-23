const err = document.getElementById("authError");
document.getElementById("signupForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  err.classList.remove("visible");
  const btn = document.getElementById("signupSubmit");
  btn.disabled = true;
  try {
    const data = await Api.signup({
      account_type: document.getElementById("signupType").value,
      full_name: document.getElementById("signupName").value.trim(),
      username: document.getElementById("signupUsername").value.trim(),
      email: document.getElementById("signupEmail").value.trim() || null,
      password: document.getElementById("signupPassword").value,
    });
    const { user_id, role, staff_type, full_name, institution_id, access_token } = data;
    TokenStore.set(access_token);
    Session.set({ user_id, role, staff_type, full_name, institution_id });
    location.href = homePageFor(role);
  } catch (error) {
    err.textContent = error.status === 429 ? "Too many sign-ups from this connection. Please try again later." : (error.message || "Could not create the account. Try again.");
    err.classList.add("visible");
    btn.disabled = false;
  }
});
