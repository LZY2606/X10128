"use strict";
const $ = (s, r = document) => r.querySelector(s);
const api = async (path, opts = {}) => {
  const res = await fetch(path, {
    method: opts.method || "GET",
    headers: opts.body ? { "Content-Type": "application/json" } : {},
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.message || res.statusText), { data });
  return data;
};
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const today = () => new Date().toISOString().slice(0, 10);

let state = {
  tab: "versions",
  versions: [],
  facts: [],
  selectedVersion: null,
  selectedRun: null,
  editingRules: [],
  revision: 0,
  msg: null,
};

const TABS = [
  ["versions", "规则集版本"],
  ["facts", "事实库"],
  ["run", "运行与解释"],
  ["runs", "历史与重放"],
  ["impact", "版本影响预览"],
  ["exceptions", "并存例外"],
  ["io", "导入 / 导出"],
];

async function refresh() {
  const [v, f] = await Promise.all([api("/api/versions"), api("/api/facts")]);
  state.versions = v.versions;
  state.facts = f.facts;
  if (state.selectedVersion) {
    state.selectedVersion = state.versions.find((x) => x.id === state.selectedVersion.id) || null;
  }
  render();
}

function render() {
  $("#clock").textContent = "今天 " + today();
  $("#nav").innerHTML = TABS.map(([k, label]) =>
    `<button class="${state.tab === k ? "active" : ""}" data-tab="${k}">${label}</button>`).join("");
  $("#nav").querySelectorAll("button").forEach((b) =>
    b.onclick = () => { state.tab = b.dataset.tab; render(); });
  const m = $("#main");
  const banner = state.msg ? `<div class="banner ${state.msg.kind}">${esc(state.msg.text)}</div>` : "";
  m.innerHTML = banner + (TAB_RENDER[state.tab] ? TAB_RENDER[state.tab]() : "");
  (TAB_MOUNT[state.tab] || (() => {}))();
}

function flash(text, kind = "ok") { state.msg = { text, kind }; render(); setTimeout(() => { state.msg = null; render(); }, 4000); }

// ---------- 条件树编辑器 ----------
const LEAF_TYPES = [
  ["numBetween", "数字区间(闭)"], ["dateBetween", "日期区间(闭)"],
  ["strIn", "字符串集合"], ["present", "字段存在"], ["notNull", "字段非空(null)"],
  ["compare", "跨字段比较"],
];

function blankCond(type) {
  if (type === "and" || type === "or") return { type, parts: [] };
  if (type === "not") return { type, inner: blankCond("present") };
  if (type === "numBetween") return { type, field: "", min: 0, max: 100 };
  if (type === "dateBetween") return { type, field: "", from: today(), to: today() };
  if (type === "strIn") return { type, field: "", values: [] };
  if (type === "present" || type === "notNull") return { type, field: "" };
  if (type === "compare") return { type, left: "", op: ">", right: "" };
  return { type, field: "" };
}

function condEditor(cond, onChange, depth = 0) {
  const box = document.createElement("div");
  box.className = "cond-editor";
  const top = document.createElement("div");
  top.className = "row";
  const typeSel = document.createElement("select");
  ["and", "or", "not", ...LEAF_TYPES.map(([v]) => v)].forEach((t) => {
    const o = document.createElement("option");
    o.value = t; o.textContent = t; if (t === cond.type) o.selected = true;
    typeSel.appendChild(o);
  });
  typeSel.onchange = () => onChange(blankCond(typeSel.value));
  top.appendChild(typeSel);

  const fieldInput = (key, label) => {
    const inp = document.createElement("input");
    inp.type = "text"; inp.placeholder = label; inp.value = cond[key] || "";
    inp.oninput = () => onChange({ ...cond, [key]: inp.value });
    top.appendChild(inp);
  };

  if (cond.type === "and" || cond.type === "or") {
    const add = document.createElement("button");
    add.className = "ghost"; add.textContent = "+ 子条件";
    add.onclick = () => onChange({ ...cond, parts: [...cond.parts, blankCond("present")] });
    top.appendChild(add);
    box.appendChild(top);
    cond.parts.forEach((sub, i) => {
      const child = condEditor(sub, (nv) => {
        const parts = cond.parts.slice(); parts[i] = nv; onChange({ ...cond, parts });
      }, depth + 1);
      const wrap = document.createElement("div");
      wrap.className = "row";
      wrap.appendChild(child);
      const del = document.createElement("button");
      del.className = "ghost danger"; del.textContent = "删除";
      del.onclick = () => onChange({ ...cond, parts: cond.parts.filter((_, j) => j !== i) });
      wrap.appendChild(del);
      box.appendChild(wrap);
    });
  } else if (cond.type === "not") {
    box.appendChild(top);
    const wrap = document.createElement("div");
    wrap.appendChild(condEditor(cond.inner, (nv) => onChange({ ...cond, inner: nv }), depth + 1));
    box.appendChild(wrap);
  } else if (cond.type === "compare") {
    fieldInput("left", "左字段名");
    const op = document.createElement("select");
    [">", ">=", "<", "<=", "==", "!="].forEach((x) => {
      const o = document.createElement("option"); o.value = x; o.textContent = x;
      if (x === cond.op) o.selected = true; op.appendChild(o);
    });
    op.onchange = () => onChange({ ...cond, op: op.value });
    const right = document.createElement("input");
    right.type = "text"; right.placeholder = "右字段名"; right.value = cond.right || "";
    right.oninput = () => onChange({ ...cond, op: op.value, right: right.value });
    top.appendChild(op); top.appendChild(right);
    box.appendChild(top);
  } else {
    fieldInput("field", "字段名");
    if (cond.type === "numBetween") {
      ["min", "max"].forEach((k) => {
        const inp = document.createElement("input");
        inp.type = "number"; inp.value = cond[k]; inp.title = k;
        inp.oninput = () => onChange({ ...cond, [k]: Number(inp.value) });
        top.appendChild(inp);
      });
    } else if (cond.type === "dateBetween") {
      ["from", "to"].forEach((k) => {
        const inp = document.createElement("input");
        inp.type = "date"; inp.value = cond[k];
        inp.onchange = () => onChange({ ...cond, [k]: inp.value });
        top.appendChild(inp);
      });
    } else if (cond.type === "strIn") {
      const inp = document.createElement("input");
      inp.placeholder = "逗号分隔的候选值"; inp.value = (cond.values || []).join(",");
      inp.oninput = () => onChange({ ...cond, values: inp.value.split(",").map((x) => x.trim()).filter(Boolean) });
      top.appendChild(inp);
    }
    box.appendChild(top);
  }
  return box;
}

// ---------- 解释树渲染 ----------
function explainTree(node) {
  const cls = node.terminal === "true" ? "t-true" : node.terminal === "false" ? "t-false" : node.terminal === "unknown" ? "t-unknown" : "";
  const mark = node.terminal ? ` <span class="${cls}">[${node.terminal === "true" ? "成立" : node.terminal === "false" ? "不成立" : "未知"}]</span>` : "";
  const li = document.createElement("li");
  li.innerHTML = `<span>${esc(node.label)}${mark}</span>`;
  if (node.children && node.children.length) {
    const ul = document.createElement("ul");
    ul.className = "tree";
    node.children.forEach((c) => ul.appendChild(explainTree(c)));
    li.appendChild(ul);
  }
  return li;
}

// ---------- 版本 Tab ----------
function versionsHtml() {
  const v = state.selectedVersion;
  const list = state.versions.map((x) => `
    <tr style="cursor:pointer;background:${v && v.id === x.id ? "#eff6ff" : ""}" data-vid="${x.id}">
      <td>${esc(x.name)}</td>
      <td class="mono">${esc(x.id)}</td>
      <td><span class="tag ${x.status}">${x.status === "published" ? "已发布" : "草稿"}</span></td>
      <td>${x.rules.length}</td>
      <td class="mono">rev ${x.revision}</td>
      <td class="mono" title="${esc(x.fingerprint || "")}">${x.fingerprint ? esc(x.fingerprint.slice(0, 10)) : "—"}</td>
    </tr>`).join("");
  return `
  <div class="card">
    <h2>版本列表</h2>
    <div class="row">
      <button class="primary" id="newV">${state.versions.length ? "基于已发布版本建草稿" : "创建首个版本"}</button>
    </div>
    <table><thead><tr><th>名称</th><th>ID</th><th>状态</th><th>规则数</th><th>版本</th><th>指纹</th></tr></thead>
    <tbody>${list}</tbody></table>
  </div>
  ${v ? versionDetailHtml(v) : `<div class="card muted">选择一个版本查看与编辑。</div>`}`;
}

function versionDetailHtml(v) {
  const editable = v.status === "draft";
  const rows = state.editingRules.map((r, i) => `
    <details ${i === 0 ? "open" : ""}>
      <summary><b>${esc(r.id)}</b> · 优先级 ${r.priority} · 结论「${esc(r.conclusion)}」 · ${r.effective.from || "−∞"} ~ ${r.effective.to || "+∞"}</summary>
      <div class="card" id="ruleEdit-${i}"></div>
    </details>`).join("");
  return `
  <div class="card">
    <h2>版本：${esc(v.name)} <span class="tag ${v.status}">${v.status === "published" ? "已发布" : "草稿 rev " + v.revision}</span></h2>
    <div class="muted">ID <code>${esc(v.id)}</code>${v.parentId ? ` · 父版本 <code>${esc(v.parentId)}</code>` : ""}${v.fingerprint ? ` · 指纹 <code>${esc(v.fingerprint)}</code>` : ""}</div>
    <div id="conflictPanel"></div>
    ${editable ? `
    <div class="row" style="margin-top:10px">
      <input type="text" id="vName" value="${esc(v.name)}">
      <button class="primary" id="addRule">+ 新规则</button>
      <button class="ghost" id="saveDraft">保存草稿（乐观锁 rev ${v.revision}）</button>
      <button class="primary" id="publish">发布版本</button>
      <button class="ghost" id="checkConflicts">静态冲突检查</button>
    </div>` : `<div class="row"><button class="ghost" id="checkConflicts">查看静态冲突</button></div>`}
    <div id="ruleList">${rows}</div>
  </div>`;
}

function mountVersions() {
  document.querySelectorAll("tr[data-vid]").forEach((tr) => tr.onclick = async () => {
    const id = tr.dataset.vid;
    const full = await api("/api/versions/" + id);
    state.selectedVersion = full;
    state.editingRules = JSON.parse(JSON.stringify(full.rules));
    state.revision = full.revision;
    render();
  });
  const newV = $("#newV");
  if (newV) newV.onclick = async () => {
    const published = state.versions.filter((x) => x.status === "published");
    if (state.versions.length && !published.length) return flash("还没有已发布版本，先发布当前草稿", "warn");
    const parent = published.length ? published[published.length - 1].id : undefined;
    const name = prompt(parent ? "新草稿名称" : "首个版本名称", parent ? "下一版本" : "v1");
    if (name === null) return;
    const created = await api("/api/versions", { method: "POST", body: { parentId: parent, name } });
    await refresh();
    state.selectedVersion = await api("/api/versions/" + created.id);
    state.editingRules = JSON.parse(JSON.stringify(state.selectedVersion.rules));
    state.revision = state.selectedVersion.revision;
    render();
  };
  const addRule = $("#addRule");
  if (addRule) addRule.onclick = () => {
    const id = prompt("规则 ID", "R" + (state.editingRules.length + 1));
    if (!id) return;
    state.editingRules.push({
      id, condition: { type: "and", parts: [{ type: "present", field: "" }] },
      effective: { from: today(), to: null }, priority: 100, conclusion: "", source: "",
    });
    render();
  };
  const save = $("#saveDraft");
  if (save) save.onclick = async () => {
    try {
      const updated = await api(`/api/versions/${state.selectedVersion.id}/save`, {
        method: "POST",
        body: { name: $("#vName").value, rules: state.editingRules, revision: state.revision },
      });
      state.revision = updated.revision;
      await refresh();
      state.selectedVersion = updated; state.editingRules = JSON.parse(JSON.stringify(updated.rules));
      flash("草稿已保存，revision → " + updated.revision);
      render();
    } catch (e) { flash(e.message, "err"); }
  };
  const pub = $("#publish");
  if (pub) pub.onclick = async () => {
    try {
      const published = await api(`/api/versions/${state.selectedVersion.id}/publish`, {
        method: "POST", body: { revision: state.revision },
      });
      await refresh();
      state.selectedVersion = published; state.editingRules = JSON.parse(JSON.stringify(published.rules));
      state.revision = published.revision;
      flash("发布成功，指纹 " + published.fingerprint.slice(0, 16) + "…");
      render();
    } catch (e) {
      if (e.data && e.data.conflicts) renderConflicts(e.data.conflicts, true);
      flash(e.message, "err");
    }
  };
  const chk = $("#checkConflicts");
  if (chk) chk.onclick = async () => {
    const rep = await api(`/api/versions/${state.selectedVersion.id}/conflicts`);
    renderConflicts(rep.pairs, false);
  };
  state.editingRules.forEach((r, i) => mountRuleEditor(r, i));
}

function mountRuleEditor(rule, i) {
  const host = $("#ruleEdit-" + i);
  if (!host) return;
  const field = (label, value, oninput, type = "text") => {
    const wrap = document.createElement("label");
    wrap.textContent = label + " ";
    const inp = document.createElement("input");
    inp.type = type; inp.value = value == null ? "" : value;
    inp.oninput = () => oninput(inp.value);
    wrap.appendChild(inp);
    host.querySelector(".ruleMeta").appendChild(wrap);
  };
  host.innerHTML = `<div class="row ruleMeta"></div><div class="condHost"></div>`;
  const meta = host.querySelector(".ruleMeta");
  const mk = (label, key, type = "text") => {
    const wrap = document.createElement("label");
    wrap.textContent = label + " ";
    const inp = document.createElement("input");
    inp.type = type; inp.value = rule[key] == null ? "" : rule[key];
    inp.oninput = () => {
      state.editingRules[i] = { ...rule, [key]: type === "number" ? Number(inp.value) : inp.value };
    };
    wrap.appendChild(inp); meta.appendChild(wrap);
  };
  mk("规则 ID", "id");
  mk("优先级", "priority", "number");
  mk("结论", "conclusion");
  mk("来源说明", "source");
  const dateRow = document.createElement("label");
  dateRow.textContent = "生效(闭) ";
  const f = document.createElement("input"); f.type = "date"; f.value = rule.effective.from || "";
  const t = document.createElement("input"); t.type = "date"; t.value = rule.effective.to || "";
  f.onchange = () => { state.editingRules[i] = { ...rule, effective: { from: f.value || null, to: t.value || null } }; };
  t.onchange = () => { state.editingRules[i] = { ...rule, effective: { from: f.value || null, to: t.value || null } }; };
  dateRow.append(f, t); meta.appendChild(dateRow);
  const del = document.createElement("button"); del.className = "ghost danger"; del.textContent = "删除规则";
  del.onclick = () => { state.editingRules.splice(i, 1); render(); };
  meta.appendChild(del);
  const updateRule = (patch) => { state.editingRules[i] = { ...state.editingRules[i], ...patch }; };
  host.querySelector(".condHost").appendChild(condEditor(state.editingRules[i].condition, (nv) => {
    updateRule({ condition: nv });
  }));
}

function renderConflicts(pairs, blocked) {
  const panel = $("#conflictPanel");
  if (!panel) return;
  if (!pairs.length) {
    panel.innerHTML = `<div class="banner ok">静态检查通过：不存在可同时命中且结论不同的规则对（同结论并存属正常）。</div>`;
    return;
  }
  panel.innerHTML = `<div class="banner ${blocked ? "err" : "warn"}">发现 ${pairs.length} 个冲突区域${blocked ? "，发布被阻止" : ""}</div>` +
    pairs.map((p) => `
      <div class="card">
        <div><b>${esc(p.ruleA)}</b> ⚔ <b>${esc(p.ruleB)}</b>
          · 生效重叠窗口 <code>${p.overlapWindow.from || "−∞"} ~ ${p.overlapWindow.to || "+∞"}</code>
          ${p.coveredByException ? `<span class="tag co">已被例外覆盖（理由：${esc(p.coveredByException.reason)}${p.coveredByException.expiresOn ? "，失效 " + esc(p.coveredByException.expiresOn) : ""}）</span>` : `<span class="tag over">无有效例外</span>`}
        </div>
        <div class="muted">${esc(p.explanation)}</div>
        ${p.minimalFact ? `<details><summary>最小冲突事实（${p.minimalEffectiveAt} 运行）</summary><pre class="mono">${esc(JSON.stringify({ effectiveAt: p.minimalEffectiveAt, fact: p.minimalFact }, null, 2))}</pre></details>` : ""}
      </div>`).join("");
}

// ---------- 事实 Tab ----------
function factsHtml() {
  const rows = state.facts.map((f) => `
    <tr><td class="mono">${esc(f.id)}</td><td>${esc(f.name)}</td>
    <td><details><summary>${Object.keys(f.fact).length} 个字段</summary><pre class="mono">${esc(JSON.stringify(f.fact, null, 2))}</pre></details></td></tr>`).join("");
  return `
  <div class="card">
    <h2>保存的事实</h2>
    <p class="muted">JSON 中键不存在＝字段缺失；<code>null</code>＝显式 null，二者严格区分。</p>
    <div class="row">
      <input type="text" id="factName" placeholder="事实名称">
      <button class="primary" id="saveFact">保存 JSON 事实</button>
    </div>
    <textarea id="factJson" rows="10">{
  "age": 30,
  "level": "gold",
  "vip": true,
  "remark": null
}</textarea>
    <table style="margin-top:10px"><thead><tr><th>ID</th><th>名称</th><th>内容</th></tr></thead><tbody>${rows}</tbody></table>
  </div>`;
}
function mountFacts() {
  $("#saveFact").onclick = async () => {
    try {
      const fact = JSON.parse($("#factJson").value);
      await api("/api/facts", { method: "POST", body: { name: $("#factName").value || "未命名事实", fact } });
      await refresh(); flash("事实已保存");
    } catch (e) { flash("事实 JSON 非法：" + e.message, "err"); }
  };
}

// ---------- 运行 Tab ----------
function runHtml() {
  const vs = state.versions.map((v) => `<option value="${v.id}">${esc(v.name)} (${v.status === "published" ? "已发布" : "草稿"})</option>`).join("");
  const fs = state.facts.map((f) => `<option value="${f.id}">${esc(f.name)}</option>`).join("");
  return `
  <div class="card">
    <h2>输入事实并运行</h2>
    <div class="row">
      <label>版本 <select id="runVersion">${vs}</select></label>
      <label>已保存事实 <select id="runFact"><option value="">— 使用下方 JSON —</option>${fs}</select></label>
      <label>运行日期(判定生效区间) <input type="date" id="runAt" value="${today()}"></label>
      <button class="primary" id="doRun">运行</button>
    </div>
    <textarea id="runJson" rows="8">{ "age": 30, "level": "gold" }</textarea>
  </div>
  <div id="runResult"></div>`;
}
function mountRun() {
  $("#runFact").onchange = async () => {
    const id = $("#runFact").value;
    if (!id) return;
    const f = state.facts.find((x) => x.id === id);
    $("#runJson").value = JSON.stringify(f.fact, null, 2);
  };
  $("#doRun").onclick = async () => {
    try {
      const fact = JSON.parse($("#runJson").value);
      const fid = $("#runFact").value || null;
      const rr = await api("/api/run", {
        method: "POST",
        body: { versionId: $("#runVersion").value, factId: fid, fact, effectiveAt: $("#runAt").value },
      });
      renderRunResult(rr);
    } catch (e) { flash("运行失败：" + e.message, "err"); }
  };
}

function kindTag(k) {
  if (k === "winner") return `<span class="tag win">胜出</span>`;
  if (k === "cowinner") return `<span class="tag co">同结论并存</span>`;
  if (k === "overridden") return `<span class="tag over">被压过</span>`;
  return `<span class="tag no">无关</span>`;
}

function renderRunResult(rr) {
  const host = $("#runResult");
  const steps = rr.comparisonSteps.map((s) =>
    `<div class="step"><b>决胜：</b> <code>${esc(s.winner)}</code> 胜 <code>${esc(s.loser)}</code><br><span class="muted">[${esc(s.basis)}] ${esc(s.detail)}</span></div>`).join("") || `<div class="muted">没有产生组间决胜（0 或 1 组命中）。</div>`;
  host.innerHTML = `
  <div class="card">
    <h2>最终结论：${rr.finalConclusion ? `「${esc(rr.finalConclusion)}」` : "<span class='muted'>无任何规则命中</span>"}</h2>
    <div class="muted">运行 <code>${esc(rr.runId)}</code> · 版本 <code>${esc(rr.versionId)}</code>${rr.versionFingerprint ? ` · 指纹 <code>${esc(rr.versionFingerprint.slice(0, 12))}</code>` : ""} · 运行日期 ${rr.effectiveAt}</div>
    <div class="muted">胜出规则：${rr.winnerRuleIds.map((x) => `<code>${esc(x)}</code>`).join(" ") || "—"}</div>
  </div>
  <div class="card"><h2>决定胜负的比较步骤</h2>${steps}</div>
  <div class="card">
    <h2>规则逐条解释（命中 / 被压过 / 无关，可展开解释树）</h2>
    <table><thead><tr><th>规则</th><th>处置</th><th>生效情况</th><th>条件解释树</th></tr></thead><tbody>
    ${rr.ruleResults.map((r) => `
      <tr>
        <td><code>${esc(r.ruleId)}</code><br><span class="muted">P${r.priority} · ${esc(r.conclusion)}</span></td>
        <td>${kindTag(r.kind)}<br><span class="muted">${r.matched ? "条件命中" : "条件未命中"}</span></td>
        <td class="muted">${esc(r.effectiveStatus)}</td>
        <td><details><summary>展开解释树</summary><ul class="tree">${explainTree(r.explanation).outerHTML}</ul></details></td>
      </tr>`).join("")}
    </tbody></table>
  </div>`;
}

// ---------- 历史与重放 ----------
function runsHtml() {
  return `
  <div class="card"><h2>历史运行</h2>
    <p class="muted">重放读取当时持久化的完整快照：旧版本即使随后修改/失效，历史结论与解释顺序仍与当时一致。</p>
    <button class="ghost" id="loadRuns">刷新列表</button>
    <div id="runList" style="margin-top:8px"></div>
  </div>`;
}
async function mountRuns() {
  const data = await api("/api/runs");
  $("#runList").innerHTML = data.runs.length ? `<table><thead><tr><th>运行</th><th>版本</th><th>日期</th><th>结论</th><th></th></tr></thead><tbody>
    ${data.runs.map((r) => `<tr><td class="mono">${esc(r.runId)}</td><td class="mono">${esc(r.versionId)}</td>
      <td>${r.effectiveAt}</td><td>${esc(r.finalConclusion || "—")}</td>
      <td><button class="ghost" data-replay="${esc(r.runId)}">按当时版本重放</button></td></tr>`).join("")}
  </tbody></table>` : `<div class="muted">尚无历史运行。</div>`;
  document.querySelectorAll("[data-replay]").forEach((b) => b.onclick = async () => {
    const rr = await api("/api/replay?runId=" + b.dataset.replay);
    state.tab = "run"; render();
    setTimeout(() => renderRunResult(rr), 0);
  });
}

// ---------- 影响预览 ----------
function impactHtml() {
  const opts = state.versions.map((v) => `<option value="${v.id}">${esc(v.name)} (${v.status})</option>`).join("");
  return `
  <div class="card"><h2>两版本影响预览</h2>
    <div class="row">
      <label>旧版本 <select id="impA">${opts}</select></label>
      <label>新版本 <select id="impB">${opts}</select></label>
      <label>运行日期 <input type="date" id="impAt" value="${today()}"></label>
      <button class="primary" id="doImpact">对全部已保存事实预览</button>
    </div>
    <div id="impResult"></div>
  </div>`;
}
function mountImpact() {
  $("#doImpact").onclick = async () => {
    try {
      const ip = await api("/api/impact", {
        method: "POST",
        body: { fromVersionId: $("#impA").value, toVersionId: $("#impB").value, effectiveAt: $("#impAt").value },
      });
      const changed = ip.impacts.filter((x) => x.changed);
      $("#impResult").innerHTML = `
        <div class="banner ${changed.length ? "warn" : "ok"}">${changed.length} / ${ip.impacts.length} 条已保存事实的结论发生变化</div>
        <table><thead><tr><th>事实</th><th>旧结论</th><th>新结论</th><th>旧胜出</th><th>新胜出</th><th>变化归因规则</th></tr></thead><tbody>
        ${ip.impacts.map((x) => `<tr style="background:${x.changed ? "#fffbeb" : ""}">
          <td>${esc(x.factName)}</td>
          <td>${esc(x.beforeConclusion || "—")}</td>
          <td>${esc(x.afterConclusion || "—")}</td>
          <td class="mono">${x.beforeWinner.join(", ")}</td>
          <td class="mono">${x.afterWinner.join(", ")}</td>
          <td>${x.causedByRuleIds.map((r) => `<code>${esc(r)}</code>`).join(" ") || "—"}</td>
        </tr>`).join("")}
        </tbody></table>`;
    } catch (e) { flash(e.message, "err"); }
  };
}

// ---------- 例外 ----------
function exceptionsHtml() {
  return `
  <div class="card">
    <h2>并存例外登记</h2>
    <p class="muted">若两条规则确实允许同时命中并给出不同结论，可登记理由和失效日期（可留空表示长期）。过期后新运行/发布重新暴露冲突，已发布版本仍保留发布时快照，历史重放不受影响。</p>
    <div class="row">
      <input type="text" id="exA" placeholder="规则 ID A">
      <input type="text" id="exB" placeholder="规则 ID B">
      <input type="text" id="exReason" placeholder="并存理由" style="min-width:220px">
      <input type="date" id="exExpires" title="失效日期（当天仍有效）">
      <button class="primary" id="addEx">登记例外</button>
    </div>
    <div id="exList" style="margin-top:8px"></div>
  </div>`;
}
async function mountExceptions() {
  const data = await api("/api/exceptions");
  const t = today();
  $("#exList").innerHTML = data.exceptions.length ? `<table><thead><tr><th>规则对</th><th>理由</th><th>失效日期</th><th>状态</th></tr></thead><tbody>
    ${data.exceptions.map((e) => {
      const alive = !e.expiresOn || e.expiresOn >= t;
      return `<tr><td><code>${esc(e.ruleA)}</code> / <code>${esc(e.ruleB)}</code></td>
      <td>${esc(e.reason)}</td><td>${e.expiresOn || "长期"}</td>
      <td><span class="tag ${alive ? "win" : "over"}">${alive ? "有效" : "已过期"}</span></td></tr>`;
    }).join("")}</tbody></table>` : `<div class="muted">尚无例外。</div>`;
  $("#addEx").onclick = async () => {
    try {
      await api("/api/exceptions", {
        method: "POST",
        body: {
          ruleA: $("#exA").value, ruleB: $("#exB").value,
          reason: $("#exReason").value,
          expiresOn: $("#exExpires").value || null,
        },
      });
      flash("例外已登记"); mountExceptions();
    } catch (e) { flash(e.message, "err"); }
  };
}

// ---------- 导入导出 ----------
function ioHtml() {
  return `
  <div class="card"><h2>导出</h2>
    <p class="muted">导出包含版本、规则、例外、事实、运行结果。已发布版本的内容指纹由规则与例外快照确定性生成，导入时校验。</p>
    <button class="primary" id="doExport">生成导出包</button>
    <textarea id="exportBox" rows="12" style="margin-top:8px" placeholder="导出内容…"></textarea>
  </div>
  <div class="card"><h2>导入（整体替换）</h2>
    <textarea id="importBox" rows="12" placeholder="粘贴导出包 JSON…"></textarea>
    <div class="row" style="margin-top:8px"><button class="primary" id="doImport">导入</button></div>
  </div>`;
}
function mountIo() {
  $("#doExport").onclick = async () => {
    const data = await api("/api/export");
    $("#exportBox").value = JSON.stringify(data, null, 2);
  };
  $("#doImport").onclick = async () => {
    try {
      await api("/api/import", { method: "POST", body: JSON.parse($("#importBox").value) });
      await refresh(); flash("导入成功，版本指纹已校验");
    } catch (e) { flash("导入失败：" + e.message, "err"); }
  };
}

const TAB_RENDER = {
  versions: versionsHtml, facts: factsHtml, run: runHtml, runs: runsHtml,
  impact: impactHtml, exceptions: exceptionsHtml, io: ioHtml,
};
const TAB_MOUNT = {
  versions: mountVersions, facts: mountFacts, run: mountRun, runs: mountRuns,
  impact: mountImpact, exceptions: mountExceptions, io: mountIo,
};

refresh().catch((e) => flash("初始化失败：" + e.message, "err"));
