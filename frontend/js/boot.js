const session = Session.get();
const dest = !Session.isAuthenticated()
  ? "login.html"
  : session.role === "super_admin" ? "dashboard.html" : "home.html";
location.replace(dest);
