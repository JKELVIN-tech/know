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

  PasswordRules.attach($("#pwNew"), $("#pwRules"), () => session.username || "");

  try {
    const me = await Api.users.me();
    $("#myEmail").textContent = me.email || "none";
    $("#emailNew").value = me.email || "";
  } catch { /* shown as none */ }
  $("#emailForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = $("#emailMsg");
    hideFormMessage(msg);
    $("#emailSubmit").disabled = true;
    try {
      const me = await Api.users.setMyEmail($("#emailNew").value.trim(), $("#emailPw").value);
      $("#myEmail").textContent = me.email || "none";
      $("#emailPw").value = "";
      showFormMessage(msg, me.email ? "Email saved." : "Email removed.", false);
    } catch (err) {
      showFormMessage(msg, err.message || "Could not save the email.");
    } finally {
      $("#emailSubmit").disabled = false;
    }
  });

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

  /* ---------------- Vault: encrypted backup, locked with the owner's own password ---------------- */

  if (session.role === "institution_admin") {
    $("#vaultCard").hidden = false;
    const ITER = 600000;
    const enc = new TextEncoder();
    const toB64 = (buf) => { let s = ""; const b = new Uint8Array(buf); for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000)); return btoa(s); };
    const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
    const pipe = async (bytes, stream) => new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(stream)).arrayBuffer());

    async function keyFor(password, salt, iter) {
      const base = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveKey"]);
      return crypto.subtle.deriveKey({ name: "PBKDF2", salt, iterations: iter, hash: "SHA-256" }, base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    }

    async function lock(object, password) {
      const salt = crypto.getRandomValues(new Uint8Array(16));
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const packed = await pipe(enc.encode(JSON.stringify(object)), new CompressionStream("gzip"));
      const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: enc.encode("know-vault|1") }, await keyFor(password, salt, ITER), packed);
      return JSON.stringify({ format: "know-vault-file", v: 1, kdf: "PBKDF2-SHA256", iter: ITER, salt: toB64(salt), iv: toB64(iv), data: toB64(data) });
    }

    async function unlock(text, password) {
      const file = JSON.parse(text);
      if (file.format !== "know-vault-file" || file.v !== 1) throw new Error("This is not a KNOW backup file.");
      let packed;
      try {
        packed = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromB64(file.iv), additionalData: enc.encode("know-vault|1") }, await keyFor(password, fromB64(file.salt), file.iter), fromB64(file.data));
      } catch { throw new Error("Wrong password, or the file was changed."); }
      return JSON.parse(new TextDecoder().decode(await pipe(new Uint8Array(packed), new DecompressionStream("gzip"))));
    }

    const say = (text, bad = true) => showFormMessage($("#vaultMsg"), text, bad);

    $("#vaultBackup").addEventListener("click", async () => {
      const password = $("#vaultPass").value;
      if (password.length < 10) { say("Choose a password of at least 10 characters."); return; }
      const btn = $("#vaultBackup");
      btn.disabled = true;
      try {
        const payload = await apiFetch("/api/vault/export");
        const locked = await lock(payload, password);
        const check = await unlock(locked, password);
        if (check.checks.sha256 !== payload.checks.sha256) throw new Error("The backup could not be verified, so it was not saved.");
        const link = document.createElement("a");
        link.href = URL.createObjectURL(new Blob([locked], { type: "application/octet-stream" }));
        link.download = `know-vault-${new Date().toISOString().slice(0, 10)}.knowvault`;
        link.click();
        URL.revokeObjectURL(link.href);
        say(`Backup verified and downloaded: ${payload.checks.counts.transactions} transactions. Keep the file and password somewhere safe.`, false);
      } catch (err) {
        say(err.status === 0 ? "You need a connection to make a backup." : err.message || "Could not make the backup.");
      } finally {
        btn.disabled = false;
      }
    });

    $("#vaultRestore").addEventListener("click", async () => {
      const password = $("#vaultPass").value;
      const file = $("#vaultFile").files[0];
      if (!file) { say("Choose a backup file first."); return; }
      if (!password) { say("Enter the backup password."); return; }
      const btn = $("#vaultRestore");
      btn.disabled = true;
      try {
        const payload = await unlock(await file.text(), password);
        const n = payload.checks.counts;
        if (!confirm(`Restore "${payload.institution_name}" (${n.transactions} transactions, ${n.stock_items} stock items) into this institution? It only works on a new, empty institution and cannot be undone.`)) return;
        const result = await apiFetch("/api/vault/restore", { method: "POST", body: JSON.stringify(payload), isForm: true });
        say(`Restored ${result.restored.transactions} transactions and ${result.staff_accounts} staff accounts. Staff logins were not restored: set a new password for each on the Accounts page.`, false);
      } catch (err) {
        say(err.status === 0 ? "You need a connection to restore." : err.message || "Could not restore.");
      } finally {
        btn.disabled = false;
      }
    });
  }
})();
