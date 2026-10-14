(async function () {
  const session = await initShell("admin-home", ["institution_admin"]);

  try {
    const [users, stockItems] = await Promise.all([
      Api.users.list(),
      Api.stock.list(),
    ]);
    $("#kpiStaff").textContent = users.filter((u) => u.role === "staff").length;
    $("#kpiActive").textContent = users.filter((u) => u.is_active).length;
    $("#kpiTeacherCount").textContent = users.filter((u) => u.role === "staff" && u.staff_type === "teacher").length;
    $("#kpiStockItems").textContent = stockItems.length;
    const team = users.filter((u) => u.role === "staff").sort((a, b) => a.full_name.localeCompare(b.full_name));
    $("#teamBody").innerHTML = team.length ? team.map((u) => `
      <tr>
        <td><strong>${escapeHtml(u.full_name)}</strong><br><small class="muted">@${escapeHtml(u.username)}</small></td>
        <td>${u.staff_type === "teacher" ? "Personal" : "Staff"}</td>
        <td><span class="badge badge-${u.is_active ? "active" : "inactive"}">${u.is_active ? "Active" : "Deactivated"}</span></td>
        <td class="muted">${lastSeenLabel(u.last_active_at)}</td>
      </tr>`).join("") : `<tr class="empty-row"><td colspan="4">No staff yet. Add some from Accounts.</td></tr>`;
  } catch (err) {
    toast(err.message || "Could not load the overview.");
  }
})();
