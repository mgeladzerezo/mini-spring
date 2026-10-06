// Plain ES module, no build step. Every panel renders a loading, empty, error or data state.

const $ = (id) => document.getElementById(id);
const money = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" });
const ms = (n) => `${Number(n).toFixed(n < 10 ? 2 : 1)} ms`;

async function api(path, options) {
  const response = await fetch(path, options);
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(body?.message ?? `${response.status} ${response.statusText}`);
  }
  return body;
}

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function table(columns, rows) {
  const wrap = el("div", undefined, "scroll");
  const t = el("table");
  const head = el("tr");
  columns.forEach((c) => head.append(el("th", c.label, c.num ? "num" : "")));
  t.append(head);
  rows.forEach((row) => {
    const tr = el("tr");
    columns.forEach((c) => {
      const td = el("td", undefined, c.num ? "num" : "");
      const value = c.render ? c.render(row) : row[c.key];
      if (value instanceof Node) td.append(value); else td.textContent = value;
      tr.append(td);
    });
    t.append(tr);
  });
  wrap.append(t);
  return wrap;
}

function show(target, content) {
  target.replaceChildren(content);
}

function state(target, message, className) {
  show(target, el("p", message, className));
}

function banner(message) {
  const b = $("banner");
  b.hidden = !message;
  b.textContent = message ?? "";
}

// ---- bank tab ---------------------------------------------------------------------------------

let accounts = [];

function renderAccounts() {
  const target = $("accounts");
  if (accounts.length === 0) return state(target, "No accounts yet.", "empty");
  show(target, table([
    { label: "#", key: "id" },
    { label: "Owner", key: "owner" },
    { label: "Balance", num: true, render: (a) => money.format(a.balance) },
  ], accounts));
  for (const select of [$("from"), $("to")]) {
    const previous = select.value;
    select.replaceChildren(...accounts.map((a) => {
      const option = el("option", `${a.id} - ${a.owner}`);
      option.value = a.id;
      return option;
    }));
    if (previous) select.value = previous;
  }
  if ($("to").value === $("from").value && accounts.length > 1) $("to").selectedIndex = 1;
}

function renderTransfers(transfers) {
  const target = $("transfers");
  if (transfers.length === 0) return state(target, "No transfers yet. Send some money above.", "empty");
  show(target, table([
    { label: "#", key: "id" },
    { label: "From", key: "fromId" },
    { label: "To", key: "toId" },
    { label: "Amount", num: true, render: (t) => money.format(t.amount) },
    { label: "When", render: (t) => new Date(t.at).toLocaleTimeString() },
  ], transfers));
}

function renderAudit(entries) {
  const target = $("audit");
  if (entries.length === 0) return state(target, "Nothing audited yet.", "empty");
  show(target, table([
    { label: "When", render: (e) => new Date(e.at).toLocaleTimeString() },
    { label: "Event", key: "message" },
  ], entries));
}

async function refreshBank() {
  try {
    const [acc, transfers, audit] = await Promise.all([
      api("/api/accounts"), api("/api/transfers?limit=10"), api("/api/audit"),
    ]);
    accounts = acc;
    renderAccounts();
    renderTransfers(transfers);
    renderAudit(audit);
    banner(null);
  } catch (error) {
    banner(`Could not load data: ${error.message}`);
    for (const id of ["accounts", "transfers", "audit"]) state($(id), "Unavailable.", "empty");
  }
}

$("transfer-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = $("form-message");
  const amount = Number($("amount").value);
  if (!(amount > 0)) {
    message.className = "message error";
    message.textContent = "Enter an amount greater than zero.";
    return;
  }
  $("submit").disabled = true;
  message.className = "message";
  message.textContent = "Sending...";
  try {
    const done = await api("/api/transfers", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fromId: Number($("from").value), toId: Number($("to").value), amount }),
    });
    message.className = "message ok";
    message.textContent = `Transfer #${done.id} completed.`;
    $("amount").value = "";
  } catch (error) {
    message.className = "message error";
    message.textContent = error.message;
  } finally {
    $("submit").disabled = false;
    refreshBank();
  }
});

// ---- container tab ----------------------------------------------------------------------------

async function refreshSystem() {
  for (const id of ["startup", "beans", "routes", "timings"]) state($(id), "Loading...", "loading");
  try {
    const system = await api("/api/system");
    const kpis = el("div", undefined, "kpis");
    const kpi = (value, label) => {
      const box = el("div", undefined, "kpi");
      box.append(el("b", value), el("span", label));
      return box;
    };
    kpis.append(
      kpi(ms(system.startupMillis), "total startup"),
      kpi(String(system.scannedClasses), "classes scanned"),
      kpi(String(system.beans.length), "beans created"),
      ...Object.entries(system.phases).map(([name, value]) => kpi(ms(value), name)),
    );
    show($("startup"), kpis);
    show($("beans"), table([
      { label: "Bean", render: (b) => el("code", b.name) },
      { label: "Type", key: "type" },
      { label: "Scope", key: "scope" },
      { label: "Depends on", render: (b) => b.dependencies.join(", ") || "-" },
      { label: "Time", num: true, render: (b) => ms(b.millis) },
    ], system.beans));
    show($("routes"), table([{ label: "Mapping", render: (r) => el("code", r) }], system.routes));
    const timings = Object.entries(system.timings).map(([name, t]) => ({ name, ...t }));
    if (timings.length === 0) state($("timings"), "No @Timed method has been called yet. Make a transfer first.", "empty");
    else show($("timings"), table([
      { label: "Method", render: (t) => el("code", t.name) },
      { label: "Calls", num: true, key: "count" },
      { label: "Mean", num: true, render: (t) => ms(t.totalNanos / t.count / 1e6) },
      { label: "Max", num: true, render: (t) => ms(t.maxNanos / 1e6) },
    ], timings));
  } catch (error) {
    banner(`Could not load the container report: ${error.message}`);
    for (const id of ["startup", "beans", "routes", "timings"]) state($(id), "Unavailable.", "empty");
  }
}

// ---- tabs and theme ---------------------------------------------------------------------------

for (const button of document.querySelectorAll("[data-tab]")) {
  button.addEventListener("click", () => {
    for (const other of document.querySelectorAll("[data-tab]")) {
      other.setAttribute("aria-selected", String(other === button));
      $(`tab-${other.dataset.tab}`).hidden = other !== button;
    }
    if (button.dataset.tab === "system") refreshSystem(); else refreshBank();
  });
}

$("theme").addEventListener("click", () => {
  const root = document.documentElement;
  const dark = root.dataset.theme
    ? root.dataset.theme === "dark"
    : matchMedia("(prefers-color-scheme: dark)").matches;
  root.dataset.theme = dark ? "light" : "dark";
  try { localStorage.setItem("theme", root.dataset.theme); } catch { /* storage may be unavailable */ }
});

try {
  const saved = localStorage.getItem("theme");
  if (saved) document.documentElement.dataset.theme = saved;
} catch { /* ignore */ }

state($("accounts"), "Loading...", "loading");
state($("transfers"), "Loading...", "loading");
state($("audit"), "Loading...", "loading");
refreshBank();
