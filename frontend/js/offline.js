/* ============================================================
   KNOW — Offline support
   IndexedDB-backed: (1) a "pending_transactions" queue for transactions
   recorded while offline, synced automatically once back online, and
   (2) a generic "cache" store holding the last-known server data (a
   transactions list, an audits list, a specific audit's transactions)
   so pages can still render with real data when the network is down.
   ============================================================ */

const Offline = (() => {
  const DB_NAME = "know_offline_v1";
  const DB_VERSION = 1;
  let dbPromise = null;

  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("pending_transactions")) {
          db.createObjectStore("pending_transactions", { keyPath: "localId", autoIncrement: true });
        }
        if (!db.objectStoreNames.contains("cache")) {
          db.createObjectStore("cache", { keyPath: "key" });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  async function tx(storeName, mode, fn) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const t = db.transaction(storeName, mode);
      const store = t.objectStore(storeName);
      const result = fn(store);
      t.oncomplete = () => resolve(result);
      t.onerror = () => reject(t.error);
    });
  }

  function requestToPromise(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  /* ---------------- generic cache (last-known server data) ---------------- */

  // Cache and queue rows belong to the logged-in user, so a shared device never shows or syncs another person's data
  function currentUserId() {
    return Session.get()?.user_id ?? null;
  }

  function scopedKey(key) {
    return `u${currentUserId()}:${key}`;
  }

  async function cacheSet(key, value) {
    await tx("cache", "readwrite", (store) => store.put({ key: scopedKey(key), value, updatedAt: new Date().toISOString() }));
  }

  async function cacheGet(key) {
    const db = await openDb();
    const store = db.transaction("cache", "readonly").objectStore("cache");
    const row = await requestToPromise(store.get(scopedKey(key)));
    return row ? row.value : null;
  }

  /* ---------------- pending transactions queue ---------------- */

  // fields matches Api.transactions.create()'s argument shape; image, if
  // present, is stored as the actual File/Blob — IndexedDB handles that
  // natively, no base64 encoding needed.
  async function queueTransaction(fields) {
    const record = { ...fields, userId: currentUserId(), createdAt: new Date().toISOString(), status: "pending", errorMessage: null };
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const t = db.transaction("pending_transactions", "readwrite");
      const store = t.objectStore("pending_transactions");
      const req = store.add(record);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function listPending() {
    const db = await openDb();
    const store = db.transaction("pending_transactions", "readonly").objectStore("pending_transactions");
    const all = await requestToPromise(store.getAll());
    // Rows queued before user tagging existed have no userId and stay visible to whoever is logged in
    const rows = all.filter((r) => r.userId === undefined || r.userId === null || r.userId === currentUserId());
    return rows.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
  }

  async function removePending(localId) {
    await tx("pending_transactions", "readwrite", (store) => store.delete(localId));
  }

  async function markPendingFailed(localId, message) {
    const db = await openDb();
    const t = db.transaction("pending_transactions", "readwrite");
    const store = t.objectStore("pending_transactions");
    const row = await requestToPromise(store.get(localId));
    if (row) {
      row.status = "failed";
      row.errorMessage = message;
      store.put(row);
    }
    return new Promise((resolve) => { t.oncomplete = () => resolve(); });
  }

  /**
   * Represents one queued item as a fake "transaction" object shaped like
   * the server's TransactionOut, so it can be merged straight into the
   * same render functions that already handle real transactions.
   */
  function pendingAsTransaction(row) {
    return {
      id: `pending-${row.localId}`,
      type: row.type,
      method: row.method,
      category_type: row.category_type || "OTHER",
      category: row.category || "(uncategorized)",
      description: row.description || null,
      amount: row.amount,
      stock_item_id: row.stock_item_id ?? null,
      quantity: row.quantity ?? null,
      mpesa_code: row.mpesa_code || null,
      mpesa_payer_name: row.mpesa_payer_name || null,
      batch_id: row.batch_id || null,
      transaction_date: row.createdAt,
      image_path: null,
      created_at: row.createdAt,
      _pending: true,
      _pendingLocalId: row.localId,
      _pendingFailed: row.status === "failed",
      _pendingError: row.errorMessage,
    };
  }

  /**
   * Pushes every queued transaction to the server in the order recorded.
   * Stops at the first network failure (still offline) so the remaining
   * queue stays intact for the next attempt; a server-side rejection
   * (validation error, etc.) marks that one item "failed" — visible to
   * the user, not silently retried forever — and moves on to the rest.
   */
  async function syncPendingTransactions() {
    const rows = await listPending();
    let synced = 0, failed = 0;
    for (const row of rows) {
      const form = new FormData();
      form.append("type", row.type);
      form.append("method", row.method);
      if (row.category_type) form.append("category_type", row.category_type);
      if (row.category) form.append("category", row.category);
      if (row.description) form.append("description", row.description);
      form.append("amount", row.amount);
      if (row.stock_item_id !== undefined && row.stock_item_id !== null) form.append("stock_item_id", row.stock_item_id);
      if (row.quantity !== undefined && row.quantity !== null) form.append("quantity", row.quantity);
      if (row.mpesa_code) form.append("mpesa_code", row.mpesa_code);
      if (row.mpesa_payer_name) form.append("mpesa_payer_name", row.mpesa_payer_name);
      if (row.batch_id) form.append("batch_id", row.batch_id);
      if (row.imageBlob) form.append("image", row.imageBlob, row.imageName || "photo.jpg");

      try {
        await apiFetch("/api/transactions", { method: "POST", body: form, isForm: true });
        await removePending(row.localId);
        synced++;
      } catch (err) {
        if (err.status === 0) {
          // Still offline — stop here, leave this and the rest queued.
          break;
        }
        await markPendingFailed(row.localId, err.message || "The server rejected this entry.");
        failed++;
      }
    }
    const remaining = await listPending();
    return { synced, failed, remaining: remaining.length };
  }

  /* ---------------- offline-friendly CSV export ---------------- */

  function csvEscape(value) {
    const str = String(value ?? "");
    return /[",\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
  }

  function csvFromTransactions(transactions, meta = {}) {
    const header = ["Date", "Type", "Category", "Method", "Amount (KES)", "Description", "M-Pesa Code", "M-Pesa Payer"];
    const lines = [header.join(",")];
    transactions.forEach((t) => {
      lines.push([
        t.transaction_date, t.type, t.category, t.method, t.amount,
        t.description || "", t.mpesa_code || "", t.mpesa_payer_name || "",
      ].map(csvEscape).join(","));
    });
    if (meta.title) lines.unshift(`# ${meta.title}${meta.period ? " — " + meta.period : ""}`);
    return new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  return {
    isOnline: () => navigator.onLine,
    cacheSet, cacheGet,
    queueTransaction, listPending, removePending, pendingAsTransaction,
    syncPendingTransactions,
    csvFromTransactions, downloadBlob,
  };
})();
