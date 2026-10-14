(async function () {
  const session = await initShell("settings", ["staff", "institution_admin", "super_admin"]);

  function roleLabel(role) {
    if (role === "super_admin") return "Super admin";
    if (role === "institution_admin") return "Owner";
    return session.staff_type === "teacher" ? "Staff (Personal)" : "Staff";
  }

  $("#acctName").textContent = session.full_name;
  $("#acctRole").textContent = roleLabel(session.role);

  // Username isn't in the login response — pull it from /api/users if we
  // have access (super_admin or institution_admin); plain staff just see
  // role + institution.
  const usernameEl = $("#acctUsername");
  usernameEl.textContent = "—";
  if (session.role === "super_admin" || session.role === "institution_admin") {
    try {
      const users = await Api.users.list();
      const me = users.find((u) => u.id === session.user_id);
      if (me) usernameEl.textContent = me.username;
    } catch { /* non-fatal */ }
  }

  const institutionRow = $("#acctSchoolRow");
  if (session.role === "super_admin") {
    institutionRow.style.display = "none";
    $("#acctPasswordHint").textContent = "Only another super admin can reset your password.";
  } else {
    try {
      const institution = await Api.institutions.get(session.institution_id);
      $("#acctSchool").textContent = institution.name;
    } catch {
      $("#acctSchool").textContent = "—";
    }
  }

  if (session.role === "institution_admin") {
    $("#institutionIconCard").hidden = false;
    $("#iconFile").addEventListener("change", () => {
      const file = $("#iconFile").files[0];
      $("#iconFileName").textContent = file ? `Selected: ${file.name}` : "No file selected yet.";
    });
    $("#iconForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const file = $("#iconFile").files[0];
      if (!file) return;
      const submitBtn = $("#iconSubmit");
      submitBtn.disabled = true;
      submitBtn.textContent = "Uploading…";
      try {
        await Api.institutions.uploadLogo(session.institution_id, file);
        toast("Portal icon updated.");
        await renderIdentity(session);
      } catch (err) {
        toast(err.message || "Could not upload the icon.");
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Upload";
      }
    });
  }

  if (session.role === "staff") {
    const card = $("#auditSharingCard");
    card.hidden = false;
    const segmented = $("#auditSharingSegmented");

    function paintSharing(shareAudits) {
      $$("button", segmented).forEach((btn) => {
        btn.classList.toggle("is-active", (btn.dataset.share === "on") === shareAudits);
      });
    }

    try {
      const me = await Api.users.me();
      paintSharing(me.share_audits);
    } catch { /* leave at default paint */ }

    $$("button", segmented).forEach((btn) => {
      btn.addEventListener("click", async () => {
        const shareAudits = btn.dataset.share === "on";
        try {
          const updated = await Api.users.updateAuditSharing(shareAudits);
          paintSharing(updated.share_audits);
          toast(shareAudits ? "Your audits are now visible to other staff at your institution." : "Your audits are now private again.");
        } catch (err) {
          toast(err.message || "Could not update audit sharing.");
        }
      });
    });
  }

  // In-app updater — only present inside the Android shell (window.KnowApp)
  if (window.KnowApp) {
    $("#appUpdateCard").hidden = false;
    $("#androidDownloadCard").hidden = true;
    const btn = $("#appUpdateBtn");
    const infoEl = $("#appUpdateInfo");
    let v = {};
    try { v = JSON.parse(window.KnowApp.info()); } catch { /* non-fatal */ }
    const installed = `Installed: v${v.versionName || "?"} (build ${v.versionCode || "?"})`;
    let mode = "check";
    const show = (text, label, disabled, nextMode) => {
      infoEl.textContent = text;
      btn.textContent = label;
      btn.disabled = disabled;
      mode = nextMode;
    };
    show(installed, "Check for updates", false, "check");
    window.onKnowUpdate = (s) => {
      if (s.state === "checking") show("Checking for updates…", "Checking…", true, "check");
      else if (s.state === "uptodate") show(`${installed}. You are on the latest version.`, "Check again", false, "check");
      else if (s.state === "available") show(`${installed}. A newer version is available.`, "Download and install", false, "install");
      else if (s.state === "downloading") show(`Downloading update… ${s.detail}%`, "Downloading…", true, "install");
      else if (s.state === "installing") show("Download complete. Confirm the install on the next screen.", "Install update", false, "install");
      else if (s.state === "need_permission") show("Allow KNOW to install apps in the settings screen that just opened, come back, then tap again.", "Download and install", false, "install");
      else if (s.state === "error") show(`${installed}. ${s.detail}`, "Try again", false, mode);
    };
    btn.addEventListener("click", () => {
      if (mode === "install") window.KnowApp.installUpdate();
      else window.KnowApp.checkForUpdate();
    });
  }

  $("#logoutBtn").addEventListener("click", logout);

  $("#pwForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = $("#pwMsg");
    hideFormMessage(msg);
    const submitBtn = $("#pwSubmit");
    submitBtn.disabled = true;
    submitBtn.textContent = "Updating…";
    try {
      await Api.users.changeMyPassword($("#pwCurrent").value, $("#pwNew").value);
      toast("Password updated. Please log back in.");
      // The server just invalidated this session's token as part of the
      // change, so the cookie/bearer token this page is holding is
      // already dead — send them to log in fresh rather than leaving
      // them on a page that will 401 on the next action.
      await logout();
    } catch (err) {
      showFormMessage(msg, err.message || "Could not update your password.");
      submitBtn.disabled = false;
      submitBtn.textContent = "Update password";
    }
  });

  // Theme segmented control
  $$("#modeSegmented button").forEach((btn) => {
    btn.addEventListener("click", () => setTheme(btn.dataset.mode));
  });
  applyTheme();
})();
