(async function () {
  const session = await initShell("accounts", ["super_admin", "institution_admin"]);
  const isSuperAdmin = session.role === "super_admin";

  let institutions = [];
  let users = [];

  await loadAll();

  ["#userSearch", "#roleFilter", "#statusFilter", "#institutionFilter"].forEach((sel) => {
    $(sel).addEventListener(sel === "#userSearch" ? "input" : "change", () => renderUsers());
  });

  async function loadAll() {
    const body = $("#usersBody");
    try {
      [institutions, users] = await Promise.all([Api.institutions.list(), Api.users.list()]);
      setupInstitutionFilter();
      renderUsers();
    } catch (err) {
      body.innerHTML = `<tr class="empty-row"><td colspan="7">${escapeHtml(err.message)}</td></tr>`;
    }
  }

  function roleLabel(role) {
    if (role === "super_admin") return "Super admin";
    if (role === "institution_admin") return "Owner";
    return "Staff";
  }

  const params = new URLSearchParams(location.search);
  if (params.get("status")) $("#statusFilter").value = params.get("status");

  function setupInstitutionFilter() {
    const select = $("#institutionFilter");
    if (!isSuperAdmin) return;
    select.hidden = false;
    select.innerHTML = `<option value="">All institutions</option>` + institutions.map((s) => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join("");
    if (params.get("institution")) select.value = params.get("institution");
  }

  function visibleUsers() {
    const q = $("#userSearch").value.trim().toLowerCase();
    const role = $("#roleFilter").value;
    const status = $("#statusFilter").value;
    const inst = $("#institutionFilter").value;
    return users.filter((u) =>
      (!q || [u.full_name, u.username, u.institution_name || ""].some((v) => v.toLowerCase().includes(q))) &&
      (!role || (role === "admins" ? u.role !== "staff" : u.role === "staff")) &&
      (!status || (status === "active" ? u.is_active : !u.is_active)) &&
      (!inst || String(u.institution_id) === inst)
    ).sort((a, b) => a.full_name.localeCompare(b.full_name));
  }

  function renderUsers() {
    const body = $("#usersBody");
    const shown = visibleUsers();
    $("#userCount").textContent = `${shown.length} ${shown.length === 1 ? "account" : "accounts"}`;
    body.innerHTML = shown.length ? shown.map((u) => `
      <tr>
        <td><strong>${escapeHtml(u.full_name)}</strong></td>
        <td><code>${escapeHtml(u.username)}</code></td>
        <td><span class="badge badge-${u.role}">${roleLabel(u.role)}${u.role === "staff" && u.staff_type === "teacher" ? " · Personal" : ""}</span></td>
        <td>${u.role !== "super_admin" ? escapeHtml(u.institution_name || "—") : "—"}</td>
        <td><span class="badge badge-${u.is_active ? "active" : "inactive"}">${u.is_active ? "Active" : "Deactivated"}</span></td>
        <td class="muted">${lastSeenLabel(u.last_active_at)}</td>
        <td>
          <div class="row-actions">
            <button class="ghost-btn" data-edit="${u.id}">Edit</button>
            <button class="ghost-btn" data-reset="${u.id}">Reset password</button>
            ${u.id === session.user_id ? "" : (u.is_active
              ? `<button class="danger-btn" data-deactivate="${u.id}">Deactivate</button>`
              : `<button class="ghost-btn" data-reactivate="${u.id}">Reactivate</button>`)}
          </div>
        </td>
      </tr>
    `).join("") : `<tr class="empty-row"><td colspan="7">No accounts match.</td></tr>`;

    $$("[data-deactivate]", body).forEach((btn) => btn.addEventListener("click", async () => {
      if (!confirm("Deactivate this account? They will no longer be able to sign in.")) return;
      try { await Api.users.deactivate(btn.dataset.deactivate); await loadAll(); toast("Account deactivated."); }
      catch (err) { toast(err.message); }
    }));
    $$("[data-reactivate]", body).forEach((btn) => btn.addEventListener("click", async () => {
      try { await Api.users.reactivate(btn.dataset.reactivate); await loadAll(); toast("Account reactivated."); }
      catch (err) { toast(err.message); }
    }));
    $$("[data-reset]", body).forEach((btn) => btn.addEventListener("click", () => openResetSheet(btn.dataset.reset)));
    $$("[data-edit]", body).forEach((btn) => btn.addEventListener("click", () => openEditSheet(btn.dataset.edit)));
  }

  /* ---------------- Edit account sheet ---------------- */

  function openEditSheet(userId) {
    const user = users.find((u) => String(u.id) === String(userId));
    if (!user) return;
    if (user.role === "super_admin") {
      // Neither actor type manages super_admin accounts through this form.
      return openReadOnlySheet(user);
    }

    const institutionOptions = institutions.map((s) =>
      `<option value="${s.id}" ${s.id === user.institution_id ? "selected" : ""}>${escapeHtml(s.name)}</option>`
    ).join("");

    Sheet.open("Edit account", `
      <p class="form-error" id="editMsg"></p>
      <form id="editForm">
        <label class="field"><span>Full name</span><input type="text" id="editFullname" value="${escapeHtml(user.full_name)}" required /></label>
        <label class="field"><span>Username</span><input type="text" id="editUsername" value="${escapeHtml(user.username)}" required /></label>
        <label class="field"><span>Account type</span>
          <select id="editRole">
            <option value="staff" ${user.role === "staff" ? "selected" : ""}>Staff</option>
            <option value="institution_admin" ${user.role === "institution_admin" ? "selected" : ""}>Owner</option>
          </select>
        </label>
        <label class="field" id="editStaffTypeField" ${user.role !== "staff" ? "hidden" : ""}>
          <span>Staff type</span>
          <select id="editStaffType">
            <option value="general" ${user.staff_type === "general" ? "selected" : ""}>General (records transactions, can use stock)</option>
            <option value="teacher" ${user.staff_type === "teacher" ? "selected" : ""}>Personal (finances only — no stock)</option>
          </select>
        </label>
        ${isSuperAdmin ? `<label class="field"><span>Institution</span><select id="editSchool">${institutionOptions}</select></label>` : ""}
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="editSubmit">Save changes</button>
        </div>
      </form>
    `);

    $("#editRole").addEventListener("change", () => {
      $("#editStaffTypeField").hidden = $("#editRole").value !== "staff";
    });

    $("#editForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#editMsg");
      hideFormMessage(msg);
      const submitBtn = $("#editSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Saving…";
      try {
        const role = $("#editRole").value;
        const payload = {
          full_name: $("#editFullname").value.trim(),
          username: $("#editUsername").value.trim(),
          role,
        };
        if (role === "staff") payload.staff_type = $("#editStaffType").value;
        if (isSuperAdmin) payload.institution_id = parseInt($("#editSchool").value, 10);
        await Api.users.update(userId, payload);
        Sheet.close();
        toast("Account updated.");
        await loadAll();
      } catch (err) {
        showFormMessage(msg, err.message || "Could not update the account.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Save changes";
      }
    });
  }

  function openReadOnlySheet(user) {
    Sheet.open("Account details", `
      <p><strong>${escapeHtml(user.full_name)}</strong></p>
      <p class="subtle">@${escapeHtml(user.username)} · ${roleLabel(user.role)}</p>
      <p class="file-hint">Super admin accounts aren't managed from this page.</p>
    `);
  }

  /* ---------------- Issue credentials sheet ---------------- */

  $("#newUserBtn").addEventListener("click", () => {
    const institutionOptions = institutions.length
      ? institutions.map((s) => `<option value="${s.id}">${escapeHtml(s.name)} (${escapeHtml(s.type)})</option>`).join("")
      : `<option value="">Add an institution first</option>`;

    const roleOptions = isSuperAdmin
      ? `<option value="staff">Staff</option><option value="institution_admin">Owner</option><option value="super_admin">Super admin</option>`
      : `<option value="staff">Staff</option><option value="institution_admin">Owner</option>`;

    Sheet.open("Issue credentials", `
      <p class="form-error" id="userMsg"></p>
      <form id="userForm">
        <label class="field"><span>Account type</span>
          <select id="userRole">${roleOptions}</select>
        </label>
        <p class="file-hint" id="roleHint"></p>
        <label class="field" id="userStaffTypeField">
          <span>Staff type</span>
          <select id="userStaffType">
            <option value="general">General (records transactions, can use stock)</option>
            <option value="teacher">Personal (finances only — no stock)</option>
          </select>
        </label>
        <label class="field"><span>Full name</span><input type="text" id="userFullname" required /></label>
        <label class="field"><span>Username</span><input type="text" id="userUsername" required /></label>
        <label class="field"><span>Temporary password</span><input type="text" id="userPassword" required minlength="8" /></label>
        <p class="file-hint">Share this with them directly. A new one can be issued anytime from Reset password.</p>
        ${isSuperAdmin ? `<label class="field" id="userSchoolField"><span>Institution</span><select id="userSchool">${institutionOptions}</select></label>` : ""}
      </form>
      <div class="form-actions">
        <button type="submit" form="userForm" class="primary-btn" id="userSubmit">Create account</button>
      </div>
    `);

    function applyRoleFieldVisibility() {
      const role = $("#userRole").value;
      const hint = $("#roleHint");
      const staffTypeField = $("#userStaffTypeField");
      const institutionField = $("#userSchoolField");

      staffTypeField.hidden = role !== "staff";

      if (role === "super_admin") {
        if (institutionField) institutionField.style.display = "none";
        hint.textContent = "Full access: manages every institution, its portal icon, and all account credentials.";
      } else if (role === "institution_admin") {
        if (institutionField) institutionField.style.display = isSuperAdmin ? "" : "none";
        hint.textContent = isSuperAdmin
          ? "Onboards staff and manages stock/portal icon for the institution you pick below — nowhere else."
          : "Onboards staff and manages stock/portal icon for your own institution.";
      } else {
        if (institutionField) institutionField.style.display = isSuperAdmin ? "" : "none";
        hint.textContent = isSuperAdmin
          ? "Scoped to the institution below — records transactions, submits audits, posts news."
          : "Scoped to your institution — records transactions, submits audits, posts news.";
      }
    }
    $("#userRole").addEventListener("change", applyRoleFieldVisibility);
    applyRoleFieldVisibility();

    $("#userForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#userMsg");
      hideFormMessage(msg);
      const submitBtn = $("#userSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Creating…";

      try {
        const role = $("#userRole").value;
        const payload = {
          full_name: $("#userFullname").value.trim(),
          username: $("#userUsername").value.trim(),
          password: $("#userPassword").value,
          role,
        };
        if (role === "staff") payload.staff_type = $("#userStaffType").value;
        if (role !== "super_admin" && isSuperAdmin) payload.institution_id = parseInt($("#userSchool").value, 10);
        // institution_admin actors never send institution_id — the backend
        // pins it to their own institution regardless of what's sent.
        await Api.users.create(payload);
        Sheet.close();
        toast("Account created.");
        await loadAll();
      } catch (err) {
        showFormMessage(msg, err.message || "Could not create the account.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Create account";
      }
    });
  });

  /* ---------------- Reset password sheet ---------------- */

  function openResetSheet(userId) {
    Sheet.open("Reset password", `
      <p class="form-error" id="resetMsg"></p>
      <form id="resetForm">
        <label class="field"><span>New password</span><input type="text" id="resetPassword" required minlength="8" /></label>
        <div class="form-actions">
          <button type="submit" class="primary-btn" id="resetSubmit">Set new password</button>
        </div>
      </form>
    `);

    $("#resetForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const msg = $("#resetMsg");
      hideFormMessage(msg);
      const submitBtn = $("#resetSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Saving…";
      try {
        await Api.users.resetPassword(userId, $("#resetPassword").value);
        Sheet.close();
        toast("Password updated. Share the new password with them directly.");
      } catch (err) {
        showFormMessage(msg, err.message || "Could not reset the password.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Set new password";
      }
    });
  }
})();
