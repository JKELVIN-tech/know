# Integration checks

Each script builds an in-memory SQLite copy of the app and exercises the API end to end, printing PASS or FAIL per check.

Run from the `backend` folder with `PYTHONPATH=. python tests/integration/t_sec.py` (replace `t_sec` with `t_vault`, `t_profit`, `t_reset`, `t_shared`, `t_partner` or `t_imports`).

- `t_sec`: sign-in, tenant isolation, passwords, uploads, sessions, rate limits
- `t_vault`: encrypted backup export and restore into a new institution
- `t_profit`: cost tracking and the owner-only daily profit
- `t_reset`: forgot password, reset links, admin reset, email handling
- `t_shared`: stock transfers from shared stock
- `t_partner`: agent program, commission and a sweep of every route as an agent
- `t_imports`: M-Pesa message review queue
