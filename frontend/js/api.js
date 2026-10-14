/* ============================================================
   KNOW — API client
   Auth is an httpOnly session cookie (set by the backend on login) plus a
   readable CSRF cookie that gets echoed back as a header on state-changing
   requests. The frontend never persists the raw access token — only
   non-sensitive display fields (role, name, institution_id, user_id).
   ============================================================ */

const API_BASE = window.TEACH_API_BASE || "http://localhost:8000";

function readCookie(name) {
  const match = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
  return match ? decodeURIComponent(match[1]) : null;
}

// Holds the access token for the frontend+backend-on-different-domains case.
// The httpOnly session cookie still carries auth for same-site deployments;
// here it also doubles as the CSRF bypass, since a cross-site page can't
// read the backend's csrf_token cookie to echo it back (browsers block
// that regardless of SameSite). Authorization headers aren't sent
// automatically cross-site, so this doesn't reintroduce CSRF risk.
const TokenStore = {
  KEY: "know_access_token",
  get() { return sessionStorage.getItem(this.KEY); },
  set(token) { sessionStorage.setItem(this.KEY, token); },
  clear() { sessionStorage.removeItem(this.KEY); },
};

const Session = {
  KEY: "know_session_info", // display data only — never the token

  get() {
    const raw = localStorage.getItem(this.KEY);
    if (!raw) return null;
    try { return JSON.parse(raw); } catch { return null; }
  },

  set(info) {
    localStorage.setItem(this.KEY, JSON.stringify(info));
  },

  clear() {
    localStorage.removeItem(this.KEY);
    TokenStore.clear();
  },

  isAuthenticated() {
    // Reflects "we were logged in as of the last successful response."
    // The real session lives in the httpOnly cookie; if it's expired or
    // cleared server-side, the next API call 401s and apiFetch() below
    // clears this and redirects.
    return !!this.get()?.role;
  },

  /** Which login page this session (or the last known one) belongs on. */
  loginPageFor(role) {
    return role === "super_admin" ? "admin-login.html" : "login.html";
  },
};

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

async function apiFetch(path, { method = "GET", body, isForm = false } = {}) {
  const headers = {};
  if (!isForm && body !== undefined) headers["Content-Type"] = "application/json";

  if (UNSAFE_METHODS.has(method)) {
    // Same-site deployments can read+echo the CSRF cookie; cross-site ones
    // can't, so fall back to the Authorization header, which the backend
    // exempts from the CSRF check (see api.js header comment above).
    const csrfToken = readCookie("csrf_token");
    if (csrfToken) headers["X-CSRF-Token"] = csrfToken;
    const token = TokenStore.get();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }

  let payload = body;
  if (body !== undefined && !isForm) payload = JSON.stringify(body);

  let res;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: payload,
      credentials: "include", // send/receive the session + CSRF cookies
    });
  } catch (err) {
    throw new ApiError("Could not reach the server. Is the backend running?", 0);
  }

  if (res.status === 401) {
    const expiredRole = Session.get()?.role;
    Session.clear();
    const loginPage = Session.loginPageFor(expiredRole);
    if (!location.pathname.endsWith(loginPage)) {
      location.href = loginPage;
    }
    throw new ApiError("Session expired — please log in again.", 401);
  }

  if (res.status === 204) return null;

  const contentType = res.headers.get("content-type") || "";
  const data = contentType.includes("application/json") ? await res.json() : await res.text();

  if (!res.ok) {
    const detail = typeof data === "object" && data?.detail ? data.detail : "Something went wrong.";
    throw new ApiError(typeof detail === "string" ? detail : JSON.stringify(detail), res.status);
  }

  return data;
}

/**
 * Shared submit handler for both login pages. Each page only accepts its
 * own role — staff and super admins each have their own dedicated door
 * even though both hit the same /api/auth/login endpoint.
 */
async function handleLoginSubmit({ username, password, expectedRole, wrongRoleMessage, form, msgEl, submitBtn }) {
  msgEl.classList.remove("visible");
  submitBtn.disabled = true;
  submitBtn.textContent = "Signing in…";

  try {
    const data = await Api.login(username, password);
    const allowedRoles = Array.isArray(expectedRole) ? expectedRole : [expectedRole];
    if (!allowedRoles.includes(data.role)) {
      // Wrong-role login still set session cookies server-side — clear
      // them immediately so a half-authenticated cookie doesn't linger.
      await Api.logout().catch(() => {});
      msgEl.textContent = wrongRoleMessage;
      msgEl.classList.add("visible");
      submitBtn.disabled = false;
      submitBtn.textContent = "Log in";
      return;
    }
    // The httpOnly cookie carries auth day-to-day; the token is also kept
    // in sessionStorage (tab-scoped, cleared on logout) purely so
    // state-changing requests can authenticate via the Authorization
    // header when the CSRF cookie isn't readable cross-site — see the
    // TokenStore comment above.
    const { user_id, role, staff_type, full_name, institution_id, access_token } = data;
    TokenStore.set(access_token);
    Session.set({ user_id, role, staff_type, full_name, institution_id });
    location.href = homePageFor(data.role);
  } catch (err) {
    msgEl.textContent = err.status === 401 || err.status === 400
      ? "Incorrect username or password."
      : (err.message || "Could not log in. Try again.");
    msgEl.classList.add("visible");
    submitBtn.disabled = false;
    submitBtn.textContent = "Log in";
  }
}

const Api = {
  async login(username, password) {
    const form = new URLSearchParams();
    form.append("username", username);
    form.append("password", password);
    const res = await fetch(`${API_BASE}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
      credentials: "include",
    });
    const data = await res.json();
    if (!res.ok) throw new ApiError(data.detail || "Login failed", res.status);
    return data;
  },

  async logout() {
    return apiFetch("/api/auth/logout", { method: "POST" });
  },

  institutions: {
    list: () => apiFetch("/api/institutions"),
    get: (id) => apiFetch(`/api/institutions/${id}`),
    create: (payload) => apiFetch("/api/institutions", { method: "POST", body: payload }),
    // logo_path is served directly (Supabase Storage public URL, or the
    // static /uploads mount for older records) — this must stay
    // unauthenticated since <img> tags can't send credentials
    // cross-origin the way fetch can.
    logoUrl: (institution) => (institution?.logo_path ? (/^https?:\/\//.test(institution.logo_path) ? institution.logo_path : `${API_BASE}/${institution.logo_path}`) : null),
    async uploadLogo(id, file) {
      const form = new FormData();
      form.append("file", file);
      return apiFetch(`/api/institutions/${id}/logo`, { method: "POST", body: form, isForm: true });
    },
  },

  users: {
    list: () => apiFetch("/api/users"),
    me: () => apiFetch("/api/users/me"),
    changeMyPassword: (current_password, new_password) => apiFetch("/api/users/me/password", { method: "POST", body: { current_password, new_password } }),
    updateAuditSharing: (share_audits) => apiFetch("/api/users/me/audit-sharing", { method: "PATCH", body: { share_audits } }),
    create: (payload) => apiFetch("/api/users", { method: "POST", body: payload }),
    update: (id, payload) => apiFetch(`/api/users/${id}`, { method: "PATCH", body: payload }),
    deactivate: (id) => apiFetch(`/api/users/${id}/deactivate`, { method: "PATCH" }),
    reactivate: (id) => apiFetch(`/api/users/${id}/reactivate`, { method: "PATCH" }),
    resetPassword: (id, new_password) => apiFetch(`/api/users/${id}/reset-password`, { method: "POST", body: { new_password } }),
  },

  admin: {
    overview: () => apiFetch("/api/admin/overview"),
    institutions: () => apiFetch("/api/admin/institutions"),
  },

  stock: {
    list: (params = {}) => apiFetch(`/api/stock?${new URLSearchParams(params)}`),
    get: (id) => apiFetch(`/api/stock/${id}`),
    create: (payload) => apiFetch("/api/stock", { method: "POST", body: payload }),
    update: (id, payload) => apiFetch(`/api/stock/${id}`, { method: "PATCH", body: payload }),
    remove: (id) => apiFetch(`/api/stock/${id}`, { method: "DELETE" }),
  },

  accounting: {
    summary: (params = {}) => apiFetch(`/api/accounting/summary?${new URLSearchParams(params)}`),
    trialBalance: (params = {}) => apiFetch(`/api/accounting/trial-balance?${new URLSearchParams(params)}`),
    openingGet: () => apiFetch("/api/accounting/opening-balances"),
    openingSet: (payload) => apiFetch("/api/accounting/opening-balances", { method: "PUT", body: payload }),
  },

  productCategories: {
    list: () => apiFetch("/api/product-categories"),
    create: (name) => apiFetch("/api/product-categories", { method: "POST", body: { name } }),
    update: (id, name) => apiFetch(`/api/product-categories/${id}`, { method: "PATCH", body: { name } }),
    remove: (id) => apiFetch(`/api/product-categories/${id}`, { method: "DELETE" }),
  },

  marketAnalysis: {
    categories: () => apiFetch("/api/market-analysis/categories"),
    categoryDetail: (id) => apiFetch(`/api/market-analysis/categories/${id}`),
  },

  mpesa: {
    /** Best-effort: amount/code/payer_name only — M-Pesa messages never
     * carry item or quantity info. matched=false is a normal "not an
     * M-Pesa message" result, not an error. */
    parse: (message) => apiFetch("/api/mpesa/parse", { method: "POST", body: { message } }),
  },

  imports: {
    scan: (messages) => apiFetch("/api/imports", { method: "POST", body: { messages } }),
    list: () => apiFetch("/api/imports"),
    approve: (id, payload) => apiFetch(`/api/imports/${id}/approve`, { method: "POST", body: payload }),
    ignore: (id) => apiFetch(`/api/imports/${id}/ignore`, { method: "POST" }),
  },

  transactions: {
    list: (params = {}) => apiFetch(`/api/transactions?${new URLSearchParams(params)}`),
    imageUrl: (txn) => (txn?.image_path ? (/^https?:\/\//.test(txn.image_path) ? txn.image_path : `${API_BASE}/${txn.image_path}`) : null),
    /**
     * Multipart. Always: type, method, amount. For an OTHER entry
     * (category_type omitted or "OTHER"): category is required (e.g.
     * "rent"). For a STOCK entry (category_type: "STOCK"): stock_item_id
     * and quantity are required instead — category defaults to the
     * item's own name if not given. mpesa_code/mpesa_payer_name are
     * optional on any entry (usually prefilled via Api.mpesa.parse).
     * description and image are always optional. Date is set server-side.
     */
    async create({ type, method, category_type, category, description, amount, stock_item_id, quantity, mpesa_code, mpesa_payer_name, image }) {
      const form = new FormData();
      form.append("type", type);
      form.append("method", method);
      if (category_type) form.append("category_type", category_type);
      if (category) form.append("category", category);
      if (description) form.append("description", description);
      form.append("amount", amount);
      if (stock_item_id !== undefined && stock_item_id !== null) form.append("stock_item_id", stock_item_id);
      if (quantity !== undefined && quantity !== null) form.append("quantity", quantity);
      if (mpesa_code) form.append("mpesa_code", mpesa_code);
      if (mpesa_payer_name) form.append("mpesa_payer_name", mpesa_payer_name);
      if (image) form.append("image", image);
      return apiFetch("/api/transactions", { method: "POST", body: form, isForm: true });
    },
    /**
     * Multipart, any subset. transaction_date, category_type and
     * stock_item_id are never editable here — delete and re-create a
     * STOCK entry instead if it's linked to the wrong item. Changing
     * type/quantity on an existing STOCK entry still correctly adjusts
     * the item's running count on the backend.
     */
    async update(id, { type, method, category, description, amount, quantity, mpesa_code, mpesa_payer_name, image }) {
      const form = new FormData();
      if (type !== undefined) form.append("type", type);
      if (method !== undefined) form.append("method", method);
      if (category !== undefined) form.append("category", category);
      if (description !== undefined) form.append("description", description ?? "");
      if (amount !== undefined) form.append("amount", amount);
      if (quantity !== undefined) form.append("quantity", quantity);
      if (mpesa_code !== undefined) form.append("mpesa_code", mpesa_code ?? "");
      if (mpesa_payer_name !== undefined) form.append("mpesa_payer_name", mpesa_payer_name ?? "");
      if (image) form.append("image", image);
      return apiFetch(`/api/transactions/${id}`, { method: "PATCH", body: form, isForm: true });
    },
    remove: (id) => apiFetch(`/api/transactions/${id}`, { method: "DELETE" }),
  },

  audits: {
    list: (params = {}) => apiFetch(`/api/audits?${new URLSearchParams(params)}`),
    get: (id) => apiFetch(`/api/audits/${id}`),
    transactions: (id) => apiFetch(`/api/audits/${id}/transactions`),
    create: (payload) => apiFetch("/api/audits", { method: "POST", body: payload }),
    update: (id, payload) => apiFetch(`/api/audits/${id}`, { method: "PATCH", body: payload }),
    remove: (id) => apiFetch(`/api/audits/${id}`, { method: "DELETE" }),
    finalize: (id) => apiFetch(`/api/audits/${id}/finalize`, { method: "POST" }),
  },

  posts: {
    list: (params = {}) => apiFetch(`/api/posts?${new URLSearchParams(params)}`),
    imageUrl: (post) => (post?.image_path ? (/^https?:\/\//.test(post.image_path) ? post.image_path : `${API_BASE}/${post.image_path}`) : null),
    /** Multipart: title, body, image? (File, no video support). */
    async create({ title, body, image }) {
      const form = new FormData();
      form.append("title", title);
      form.append("body", body);
      if (image) form.append("image", image);
      return apiFetch("/api/posts", { method: "POST", body: form, isForm: true });
    },
    remove: (id) => apiFetch(`/api/posts/${id}`, { method: "DELETE" }),
    report: (id, reason) => apiFetch(`/api/posts/${id}/report`, { method: "POST", body: { reason: reason || null } }),
  },

  moderation: {
    posts: (params = {}) => apiFetch(`/api/moderation/posts?${new URLSearchParams(params)}`),
    reports: (params = {}) => apiFetch(`/api/moderation/reports?${new URLSearchParams(params)}`),
    /** action: "dismiss" | "remove_post" */
    resolveReport: (reportId, action) => apiFetch(`/api/moderation/reports/${reportId}/resolve?${new URLSearchParams({ action })}`, { method: "POST" }),
  },

  activityLog: {
    list: (params = {}) => apiFetch(`/api/activity-log?${new URLSearchParams(params)}`),
  },

  reports: {
    auditPdfUrl: (auditId) => `${API_BASE}/api/reports/audits/${auditId}.pdf`,
    async downloadAuditPdf(auditId) {
      const res = await fetch(`${API_BASE}/api/reports/audits/${auditId}.pdf`, { credentials: "include" });
      if (!res.ok) throw new ApiError("Could not generate the report.", res.status);
      return res.blob();
    },
    /** method: "cash" | "mpesa" | "bank" | null (null = combined). start/end: Date objects. */
    async downloadStatementPdf({ method, start, end }) {
      const params = new URLSearchParams({ start: start.toISOString(), end: end.toISOString() });
      if (method) params.set("method", method);
      const res = await fetch(`${API_BASE}/api/reports/statements.pdf?${params}`, { credentials: "include" });
      if (!res.ok) throw new ApiError("Could not generate the statement.", res.status);
      return res.blob();
    },
  },
};

// Inside the Android app there is nothing to download, so hide every APK link
if (window.KnowApp) {
  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll('a[href$="app-debug.apk"]').forEach((a) => { a.style.display = "none"; });
  });
}
