const err = document.getElementById("authError");
const done = document.getElementById("authDone");
document.getElementById("forgotForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  err.classList.remove("visible");
  const btn = document.getElementById("forgotSubmit");
  btn.disabled = true;
  try {
    const res = await Api.forgotPassword(document.getElementById("forgotIdent").value.trim());
    done.textContent = res.message;
    done.hidden = false;
    btn.textContent = "Link requested";
  } catch (error) {
    err.textContent = error.status === 429 ? "Too many requests. Please wait a while and try again." : (error.message || "Could not send the link. Try again.");
    err.classList.add("visible");
    btn.disabled = false;
  }
});
