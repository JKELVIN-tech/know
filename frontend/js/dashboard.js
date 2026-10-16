(async function () {
  await initShell("dashboard", ["super_admin"]);

  try {
    const [o, institutions] = await Promise.all([Api.admin.overview(), Api.admin.institutions()]);
    $("#kpiSchools").textContent = o.institutions;
    $("#kpiActive").textContent = o.accounts_active;
    $("#kpiAccountsSub").textContent = `active of ${o.accounts_total} (${o.staff} staff, ${o.institution_admins} owners)`;
    $("#kpiEntries").textContent = o.entries_7d;
    $("#kpiReports").textContent = o.open_reports;
    $("#reportsCard").classList.add(o.open_reports ? "bad" : "good");
    $("#kpiReportsSub").textContent = o.open_reports ? "need your review" : "nothing waiting";

    const items = [];
    if (o.open_reports) items.push(`<a class="attention-row bad" href="review.html"><span class="ico-wrap"><span class="ico" data-ico="flag"></span></span><span><strong>${o.open_reports} post ${o.open_reports === 1 ? "report" : "reports"} waiting</strong><small>Review and decide</small></span></a>`);
    if (o.accounts_inactive) items.push(`<a class="attention-row" href="accounts.html?status=inactive"><span class="ico-wrap"><span class="ico" data-ico="accounts"></span></span><span><strong>${o.accounts_inactive} deactivated ${o.accounts_inactive === 1 ? "account" : "accounts"}</strong><small>See who is switched off</small></span></a>`);
    o.quiet_institutions.slice(0, 5).forEach((q) => items.push(`<a class="attention-row" href="institutions.html"><span class="ico-wrap"><span class="ico" data-ico="institution"></span></span><span><strong>${escapeHtml(q.name)} has gone quiet</strong><small>Last activity: ${lastSeenLabel(q.last_activity_at)}</small></span></a>`));
    $("#attention").innerHTML = items.length ? items.join("") : `<p class="muted">All clear. Nothing needs you right now.</p>`;

    $("#activityList").innerHTML = o.recent_activity.length ? o.recent_activity.map((a) => {
      const warn = /failed|locked|blocked/.test(a.action);
      return `<div class="activity-row ${warn ? "warn" : ""}"><span class="dot"></span><span>${escapeHtml(describeActivity(a))}</span><span class="when">${lastSeenLabel(a.created_at)}</span></div>`;
    }).join("") : `<p class="muted">No activity yet.</p>`;

    $("#healthBody").innerHTML = institutions.length ? institutions.map((i) => {
      const quiet = daysSinceIso(i.last_activity_at) > 14;
      return `<tr>
        <td><div class="cell-main"><div class="seal">${i.logo_path ? `<img src="${Api.institutions.logoUrl(i)}" alt="">` : `<span class="seal-fallback">${escapeHtml(initials(i.name))}</span>`}</div><div><strong>${escapeHtml(i.name)}</strong><small>${escapeHtml(i.type)}${i.region ? " · " + escapeHtml(i.region) : ""}</small></div></div></td>
        <td class="num">${i.active_accounts}${i.inactive_accounts ? ` <span class="muted">(+${i.inactive_accounts} off)</span>` : ""}</td>
        <td class="num">${i.entries_30d}</td>
        <td><span class="status-dot ${quiet ? "quiet" : ""}"></span>${lastSeenLabel(i.last_activity_at)}</td>
      </tr>`;
    }).join("") : `<tr class="empty-row"><td colspan="4">No institutions yet.</td></tr>`;
  } catch (err) {
    toast(err.message || "Could not load the overview.");
  }

  try {
    const u = await Api.storage();
    const mb = (b) => `${(b / 1048576).toFixed(b < 10485760 ? 1 : 0)} MB`;
    const pct = (used, limit) => `${Math.round((used / limit) * 100)}%`;
    $("#storageDb").textContent = u.database_bytes == null ? "Usage is not available right now." : `Database: ${mb(u.database_bytes)} of ${mb(u.database_limit_bytes)} (${pct(u.database_bytes, u.database_limit_bytes)})`;
    $("#storagePhotos").textContent = u.storage_bytes == null ? "" : `Photos and files: ${mb(u.storage_bytes)} of ${mb(u.storage_limit_bytes)} (${pct(u.storage_bytes, u.storage_limit_bytes)})`;
    $("#storageList").innerHTML = u.institutions.length ? u.institutions.map((r) => `<li class="ex-row"><div><strong>${escapeHtml(r.name)}</strong><small>${r.photo_files} photos</small></div><div>${mb(r.photo_bytes)}</div></li>`).join("") : "";
  } catch { /* the meter is optional */ }
})();
