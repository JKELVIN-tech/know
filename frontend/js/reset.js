// The token travels after the # so it is never sent to a server; read it once, then remove it from the address bar
const token = new URLSearchParams(location.hash.slice(1)).get("token");
history.replaceState(null, "", location.pathname);
const err = document.getElementById("authError");
const done = document.getElementById("authDone");
const form = document.getElementById("resetForm");
const showError = (text) => { err.textContent = text; err.classList.add("visible"); };

PasswordRules.attach(document.getElementById("newPw"), document.getElementById("pwRules"));
if (!token) {
  form.hidden = true;
  showError("This reset link is incomplete. Open the link from your email again, or request a new one.");
}
form.addEventListener("submit", async (e) => {
  e.preventDefault();
  err.classList.remove("visible");
  const pw = document.getElementById("newPw").value;
  if (!PasswordRules.valid(pw)) return showError("The password does not meet every rule below.");
  if (pw !== document.getElementById("newPw2").value) return showError("The two passwords do not match.");
  const btn = document.getElementById("resetSubmit");
  btn.disabled = true;
  try {
    const res = await Api.resetPassword(token, pw);
    form.hidden = true;
    done.innerHTML = "";
    done.append(res.message + " ");
    const link = document.createElement("a");
    link.href = "login.html";
    link.textContent = "Log in";
    done.append(link);
    done.hidden = false;
  } catch (error) {
    showError(error.message || "Could not set the password.");
    btn.disabled = false;
  }
});
