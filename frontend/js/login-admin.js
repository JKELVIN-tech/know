paintIcons();
if (Session.isAuthenticated()) {
  location.href = Session.get().role === "super_admin" ? "dashboard.html" : "home.html";
}

const form = document.getElementById("loginForm");
const msg = document.getElementById("authError");
const submitBtn = document.getElementById("loginSubmit");

form.addEventListener("submit", (e) => {
  e.preventDefault();
  handleLoginSubmit({
    username: document.getElementById("loginUsername").value.trim(),
    password: document.getElementById("loginPassword").value,
    expectedRole: "super_admin",
    wrongRoleMessage: "This sign-in is for super admins only. Use the staff sign-in page instead.",
    form, msgEl: msg, submitBtn,
  });
});
