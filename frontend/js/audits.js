(async function () {
  await initShell("audits", ["institution_admin"]);
  const list = $("#auditList");

  function when(iso) {
    return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
  }

  async function load() {
    try {
      const audits = await Api.audits.list();
      list.innerHTML = audits.length ? audits.map((a) => `
        <li class="ex-row">
          <div><strong>${escapeHtml(a.title)}</strong><small>${when(a.period_start)} – ${when(a.period_end)} · ${a.status === "finalized" ? "Finalized" : "Draft"}</small></div>
          <div class="head-actions">
            <a class="ghost-btn" href="books.html?audit=${a.id}">Open books</a>
            ${a.status === "draft" ? `<button type="button" class="ghost-btn" data-finalize="${a.id}">Finalize</button><button type="button" class="danger-btn" data-delete="${a.id}">Delete</button>` : ""}
          </div>
        </li>`).join("") : `<li class="subtle">No audits yet. Pick a From and To date on Books and give it a title to save one.</li>`;
    } catch (err) {
      list.innerHTML = `<li class="subtle">${escapeHtml(err.message || "Could not load audits.")}</li>`;
    }
  }

  list.addEventListener("click", async (e) => {
    const fin = e.target.closest("[data-finalize]");
    const del = e.target.closest("[data-delete]");
    try {
      if (fin && confirm("Finalizing locks every entry in this period so it can no longer be edited or deleted. Continue?")) {
        await Api.audits.finalize(fin.dataset.finalize);
        toast("Audit finalized.");
        await load();
      } else if (del && confirm("Delete this draft audit? No entries are touched.")) {
        await Api.audits.remove(del.dataset.delete);
        toast("Audit deleted.");
        await load();
      }
    } catch (err) {
      toast(err.message || "That did not work.");
    }
  });

  await load();
  onFreshData(load);
})();
