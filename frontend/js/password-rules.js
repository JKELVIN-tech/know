const PasswordRules = {
  rules: [
    { label: "At least 10 characters", test: (p) => p.length >= 10 },
    { label: "An uppercase letter", test: (p) => /[A-Z]/.test(p) },
    { label: "A lowercase letter", test: (p) => /[a-z]/.test(p) },
    { label: "A number", test: (p) => /[0-9]/.test(p) },
    { label: "A symbol (such as ! # $ %)", test: (p) => /[^A-Za-z0-9\s]/.test(p) },
  ],

  valid(password, username) {
    const p = (password || "").trim();
    const hasName = username && username.length >= 3 && p.toLowerCase().includes(username.toLowerCase());
    return this.rules.every((r) => r.test(p)) && !hasName;
  },

  // Shows a live checklist under the field as the person types
  attach(input, list, usernameFn) {
    const draw = () => {
      const p = input.value.trim();
      const name = usernameFn ? usernameFn() : "";
      const rows = this.rules.map((r) => ({ ok: r.test(p), label: r.label }));
      if (name && name.length >= 3) rows.push({ ok: !p.toLowerCase().includes(name.toLowerCase()), label: "Does not contain the username" });
      list.innerHTML = rows.map((r) => `<li class="${r.ok ? "ok" : ""}">${r.ok ? "\u2713" : "\u25CB"} ${r.label}</li>`).join("");
    };
    input.addEventListener("input", draw);
    draw();
  },

  // A random password that always satisfies every rule
  generate() {
    const pick = (set) => set[crypto.getRandomValues(new Uint32Array(1))[0] % set.length];
    const upper = "ABCDEFGHJKLMNPQRSTUVWXYZ", lower = "abcdefghijkmnopqrstuvwxyz", digits = "23456789", symbols = "!#$%&*?@";
    const all = upper + lower + digits + symbols;
    const chars = [pick(upper), pick(lower), pick(digits), pick(symbols)];
    while (chars.length < 14) chars.push(pick(all));
    for (let i = chars.length - 1; i > 0; i--) {
      const j = crypto.getRandomValues(new Uint32Array(1))[0] % (i + 1);
      [chars[i], chars[j]] = [chars[j], chars[i]];
    }
    return chars.join("");
  },
};
