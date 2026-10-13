// rota playground: the solver is the Kotlin core compiled to JavaScript (./lib), called in-page.
import * as rota from './lib/rota-core.mjs';

const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const examples = JSON.parse(rota.rotaExamples()).map(([id, title, source]) => ({ id, title, source }));
const state = { plan: null, response: null, focus: null, tab: 'calendar' };

const els = {
  source: $('#source'), hl: $('#hl'), gutter: $('#gutter'), cursor: $('#cursor'), diagnostics: $('#diagnostics'),
  badge: $('#badge'), metrics: $('#metrics'), legend: $('#legend'), whyCount: $('#why-count'),
  example: $('#example'), menu: $('#download-menu'), download: $('#download'), toast: $('#toast'),
};

/* ── colours: golden-angle hues, so neighbours in the list never look alike ── */
const colour = (i) => `oklch(0.72 0.15 ${(i * 137.508 + 40) % 360})`;
const personIndex = () => new Map((state.plan?.people ?? []).map((p, i) => [p.name, i]));

/* ── editor: highlighted <pre> under a transparent <textarea> ─────────────── */
const STATEMENTS = new Set(['rota', 'name', 'period', 'timezone', 'tz', 'skip', 'holiday', 'holidays', 'shift', 'person', 'member', 'max-per-day', 'max-per-week', 'min-rest', 'weight']);
const OPTIONS = new Set(['needs', 'requires', 'skill', 'skills', 'share', 'off', 'away', 'avoid', 'avoids', 'prefer', 'prefers', 'likes', 'history', 'done', 'max', 'can', 'for', 'weeks', 'week', 'days', 'day', 'x', 'fairness', 'spread']);
const DAYWORD = /^(daily|everyday|weekdays|weekends?|((mon|tue|wed|thu|fri|sat|sun)[a-z]*(-(mon|tue|wed|thu|fri|sat|sun)[a-z]*)?,?)+)$/i;
const NUMBERISH = /^[\d.:\-]+h?$|^\d{4}-\d{2}(-\d{2})?(\.\.\d{4}-\d{2}-\d{2})?(,.*)?$/;

function classify(line) {
  const cls = new Array(line.length).fill('');
  let i = 0;
  let first = true;
  while (i < line.length) {
    const c = line[i];
    if (c === '#') { cls.fill('t-com', i); break; }
    if (/\s/.test(c)) { i++; continue; }
    if (c === '"') {
      const end = line.indexOf('"', i + 1);
      const j = end < 0 ? line.length : end + 1;
      cls.fill('t-str', i, j); i = j; first = false; continue;
    }
    let j = i;
    while (j < line.length && !/\s/.test(line[j]) && line[j] !== '#') j++;
    const word = line.slice(i, j);
    const lower = word.toLowerCase();
    let k = '';
    if (first && STATEMENTS.has(lower)) k = 't-kw';
    else if (OPTIONS.has(lower)) k = 't-opt';
    else if (NUMBERISH.test(word)) k = 't-num';
    else if (DAYWORD.test(word)) k = 't-day';
    if (k) cls.fill(k, i, j);
    first = false;
    i = j;
  }
  return cls;
}

function renderEditor(diags = []) {
  const lines = els.source.value.split('\n');
  const byLine = new Map();
  for (const d of diags) {
    if (!byLine.has(d.line)) byLine.set(d.line, []);
    byLine.get(d.line).push(d);
  }
  const html = lines.map((line, n) => {
    const ds = byLine.get(n + 1) ?? [];
    const cls = classify(line);
    const sq = new Array(line.length).fill('');
    for (const d of ds) {
      const from = Math.min(d.column - 1, Math.max(0, line.length - 1));
      const to = Math.min(line.length, from + Math.max(1, d.length));
      for (let k = from; k < to; k++) sq[k] = d.severity === 'error' ? 'squiggle' : 'squiggle w';
    }
    let out = '';
    let k = 0;
    while (k < line.length) {
      let j = k;
      while (j < line.length && cls[j] === cls[k] && sq[j] === sq[k]) j++;
      const c = [cls[k], sq[k]].filter(Boolean).join(' ');
      const text = esc(line.slice(k, j));
      out += c ? `<span class="${c}">${text}</span>` : text;
      k = j;
    }
    const hasErr = ds.some((d) => d.severity === 'error');
    return hasErr ? `<span class="line-err">${out || ' '}</span>` : out;
  }).join('\n');
  els.hl.innerHTML = html + '\n';
  els.gutter.innerHTML = lines.map((_, n) => {
    const ds = byLine.get(n + 1) ?? [];
    const c = ds.some((d) => d.severity === 'error') ? 'err' : ds.length ? 'warn' : '';
    return `<div class="${c}">${n + 1}</div>`;
  }).join('');
  syncScroll();
}

function syncScroll() {
  els.hl.scrollTop = els.source.scrollTop;
  els.hl.scrollLeft = els.source.scrollLeft;
  els.gutter.scrollTop = els.source.scrollTop;
}

function updateCursor() {
  const before = els.source.value.slice(0, els.source.selectionStart).split('\n');
  els.cursor.textContent = `Ln ${before.length}, Col ${before[before.length - 1].length + 1}`;
}

function goTo(line, column) {
  const lines = els.source.value.split('\n');
  let pos = 0;
  for (let i = 0; i < line - 1 && i < lines.length; i++) pos += lines[i].length + 1;
  pos += Math.max(0, column - 1);
  els.source.focus();
  els.source.setSelectionRange(pos, pos);
  els.source.scrollTop = Math.max(0, (line - 4) * 21);
  updateCursor();
}

/* ── solving ──────────────────────────────────────────────────────────────── */
let timer = 0;
function scheduleSolve() {
  clearTimeout(timer);
  timer = setTimeout(solve, 180);
}

function solve() {
  const source = els.source.value;
  let response;
  try {
    response = JSON.parse(rota.solveRota(source));
  } catch (e) {
    response = { ok: false, error: String(e?.message ?? e), diagnostics: [] };
  }
  state.response = response;
  const warnings = response.ok ? parseWarnings(response.plan.warnings) : [];
  const diags = response.ok ? warnings : response.diagnostics;
  renderEditor(diags);
  renderDiagnostics(diags, response.ok ? null : response.error);
  if (response.ok) {
    state.plan = response.plan;
    if (state.focus && !state.plan.people.some((p) => p.name === state.focus)) state.focus = null;
  }
  renderStatus();
  renderAll();
  try { localStorage.setItem('rota:source', source); } catch { /* private mode */ }
}

function parseWarnings(list) {
  return (list ?? []).map((w) => {
    const m = /^(\d+):(\d+): warning: (.*)$/.exec(w);
    return m ? { severity: 'warning', line: +m[1], column: +m[2], length: 1, message: m[3] } : { severity: 'warning', line: 1, column: 1, length: 1, message: w };
  });
}

function renderDiagnostics(diags, error) {
  const items = diags.map((d) => `<li class="${d.severity}" data-line="${d.line}" data-col="${d.column}">
    <span class="where">${d.line}:${d.column}</span><span class="sev">${d.severity}</span><span>${esc(d.message)}</span></li>`);
  if (error && !diags.length) items.push(`<li class="error"><span class="sev">error</span><span>${esc(error)}</span></li>`);
  els.diagnostics.innerHTML = items.join('');
}

function renderStatus() {
  const r = state.response;
  const p = state.plan;
  if (!r.ok) {
    const n = r.diagnostics.filter((d) => d.severity === 'error').length;
    els.badge.className = 'badge err';
    els.badge.textContent = n ? `${n} error${n > 1 ? 's' : ''} in the rota — showing the last good plan` : r.error;
  } else {
    const open = p.gaps.reduce((a, g) => a + g.missing, 0);
    els.badge.className = open ? 'badge bad' : 'badge ok';
    els.badge.textContent = open ? `✗ ${open} shift${open > 1 ? 's' : ''} can't be filled` : '✓ Fully staffed · exact optimum';
  }
  if (p) {
    const s = p.stats;
    els.metrics.innerHTML = `<span><b>${p.people.length}</b> people</span><span><b>${s.units}</b> shift-person units</span>
      <span><b>${s.millis.toFixed(1)}</b> ms</span><span><b>${s.phases}</b> shortest-path phases</span>`;
  }
}

/* ── views ────────────────────────────────────────────────────────────────── */
function renderAll() {
  if (!state.plan) return;
  renderLegend();
  renderCalendar();
  renderPeople();
  renderWhy();
  renderModel();
}

function renderLegend() {
  els.legend.innerHTML = state.plan.people.map((p, i) => `<button style="--p:${colour(i)}" data-person="${esc(p.name)}" class="${state.focus === p.name ? 'on' : ''}"
      title="Highlight ${esc(p.name)}'s shifts"><span class="dot"></span>${esc(p.name)} <small>${p.assigned}</small></button>`).join('');
}

const parseDate = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d)); };
const iso = (d) => d.toISOString().slice(0, 10);
const addDays = (d, n) => new Date(d.getTime() + n * 86400000);

function renderCalendar() {
  const plan = state.plan;
  const idx = personIndex();
  const byDate = new Map();
  for (const s of plan.slots) {
    if (!byDate.has(s.date)) byDate.set(s.date, []);
    byDate.get(s.date).push(s);
  }
  const start = parseDate(plan.start);
  const end = parseDate(plan.end);
  let monday = addDays(start, -((start.getUTCDay() + 6) % 7));
  let html = '';
  let week = 1;
  while (monday <= end) {
    const sunday = addDays(monday, 6);
    const weekSlots = [];
    let cells = '';
    for (let i = 0; i < 7; i++) {
      const d = addDays(monday, i);
      const key = iso(d);
      const out = d < start || d > end;
      const slots = byDate.get(key) ?? [];
      weekSlots.push(...slots);
      const body = slots.map((s) => {
        const chips = s.assigned.map((n) => `<div class="chip" data-p="${esc(n)}" style="--p:${colour(idx.get(n))}" title="${esc(n)} · ${esc(s.shift)} ${s.start}–${s.end}">${esc(n)}</div>`).join('')
          + '<div class="chip open" title="No one can be placed here — see the Why tab">open</div>'.repeat(Math.max(0, s.needs - s.assigned.length));
        return `<div class="slot" title="${esc(s.shift)} ${s.start}–${s.end}"><div class="slot-name">${esc(s.shift)}</div>${chips}</div>`;
      }).join('');
      const cls = ['day', out ? 'out' : '', i >= 5 ? 'weekend' : '', !out && !slots.length ? 'skip' : ''].join(' ');
      cells += `<div class="${cls}"><div class="day-num"><span>${DAYS[i]}</span><b>${d.getUTCDate()}</b></div>${body}</div>`;
    }
    const open = weekSlots.reduce((a, s) => a + s.needs - s.assigned.length, 0);
    const label = `${MONTHS[monday.getUTCMonth()]} ${monday.getUTCDate()} – ${MONTHS[sunday.getUTCMonth()]} ${sunday.getUTCDate()}`;
    html += `<section class="week"><div class="week-head"><h3>Week ${week}</h3><span>${label} · ${weekSlots.length} shifts${open ? ` · <b style="color:var(--bad)">${open} open</b>` : ''}</span></div>
      <div class="days">${cells}</div></section>`;
    monday = addDays(monday, 7);
    week++;
  }
  const panel = $('#tab-calendar');
  panel.innerHTML = html;
  applyFocus();
}

function applyFocus() {
  const panel = $('#tab-calendar');
  panel.classList.toggle('dim', !!state.focus);
  panel.querySelectorAll('.chip[data-p]').forEach((c) => c.classList.toggle('hot', c.dataset.p === state.focus));
  els.legend.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.person === state.focus));
}

function renderPeople() {
  const plan = state.plan;
  const scale = Math.max(1, ...plan.people.map((p) => Math.max(p.assigned, p.target))) * 1.08;
  const shiftOrder = plan.shifts.map((s) => s.name);
  const rows = plan.people.map((p, i) => {
    let left = 0;
    const segs = shiftOrder.filter((s) => p.byShift[s]).map((s, k) => {
      const n = p.byShift[s];
      const w = (n / scale) * 100;
      const alpha = 85 - (shiftOrder.indexOf(s) * 22) % 60;
      const seg = `<div class="seg" style="left:${left}%;width:${w}%;background:color-mix(in oklab, var(--p) ${alpha}%, transparent)" title="${esc(s)}: ${n}">${w > 9 ? `${esc(s)} ${n}` : ''}</div>`;
      left += w;
      return seg;
    }).join('');
    const tags = [
      p.share !== 1 ? `<span class="tag">share ${p.share}</span>` : '',
      p.history ? `<span class="tag" title="Shifts carried over from earlier rotas">history ${p.history}</span>` : '',
      p.daysOff ? `<span class="tag">${p.daysOff} days off</span>` : '',
      p.avoidedHits ? `<span class="tag bad" title="Shifts they asked to avoid but had to take">${p.avoidedHits} avoided</span>` : '',
      p.preferredHits ? `<span class="tag good" title="Shifts they asked for">${p.preferredHits} preferred</span>` : '',
    ].join('');
    const maxWeek = Math.max(1, ...p.perWeek);
    const spark = p.perWeek.map((n, w) => `<i style="height:${(n / maxWeek) * 100}%" title="week ${w + 1}: ${n}"></i>`).join('');
    return `<div class="person" style="--p:${colour(i)}">
      <div class="who"><b>${esc(p.name)}</b><div class="tags">${tags}</div></div>
      <div class="load">${segs}<div class="target" style="left:${(p.target / scale) * 100}%" data-label="fair ${p.target}"></div></div>
      <div class="nums"><span class="big">${p.assigned} <small>shifts</small></span><span class="spark" title="shifts per week">${spark}</span></div>
    </div>`;
  }).join('');
  $('#tab-people').innerHTML = `<p class="fair-note">Bars are each person's shifts split by type; the tick is their <b>fair share</b> — total work × their share, corrected for history. Gaps between bar and tick come from hard limits (time off, skills, max-per-week), never from the solver's choice.</p><div class="people">${rows}</div>`;
}

function renderWhy() {
  const plan = state.plan;
  const idx = personIndex();
  const cards = [];
  for (const g of plan.gaps) {
    const blockers = g.blockers.map((b) => `<li style="--p:${colour(idx.get(b.person))}"><b>${esc(b.person)}</b><span class="muted">${esc(b.reason)}</span></li>`).join('');
    cards.push(`<div class="card bad"><h4>${esc(g.shift)} <span class="when">${g.date}</span> <span style="color:var(--bad)">${g.missing} missing</span></h4>
      <p>${esc(g.reason)}.</p>${blockers ? `<ul>${blockers}</ul>` : ''}</div>`);
  }
  for (const r of plan.restWarnings) {
    cards.push(`<div class="card warn"><h4>Short rest for ${esc(r.person)} <span class="when">${r.restHours.toFixed(1)} h</span></h4>
      <p>${esc(r.from)} → ${esc(r.to)}. Below <code>min-rest</code>; no fairer plan avoids it with these rules.</p></div>`);
  }
  els.whyCount.textContent = plan.gaps.length ? String(plan.gaps.length) : '';
  $('#tab-why').innerHTML = cards.length ? `<div class="cards">${cards.join('')}</div>`
    : `<div class="empty"><span class="big">✓</span>Every shift is staffed and everyone gets their minimum rest.<br>Try the <b>“Holiday week”</b> example to see how rota explains a rota that cannot be filled.</div>`;
}

function renderModel() {
  const p = state.plan;
  const s = p.stats;
  const cols = [
    { x: 20, label: 'source', sub: '', end: true },
    { x: 150, label: `${s.slots} slots`, sub: 'shift × date' },
    { x: 310, label: `${s.personDays} person-days`, sub: 'who could work when' },
    { x: 480, label: `${s.personWeeks} person-weeks`, sub: 'weekly limit' },
    { x: 650, label: `${p.people.length} people`, sub: 'fairness' },
    { x: 790, label: 'sink', sub: '', end: true },
  ];
  const caps = ['needs', '1 each', 'max/day', 'max/week · spread', 'fairness k²'];
  const w = 860, h = 230, y = 80, nh = 54;
  let svg = `<svg class="flow-svg" viewBox="0 0 ${w} ${h}" role="img" aria-label="The rota as a flow network">
    <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="var(--faint)"/></marker></defs>`;
  const widths = cols.map((c) => (c.end ? 60 : 130));
  cols.forEach((c, i) => {
    if (i < cols.length - 1) {
      const x1 = c.x + widths[i], x2 = cols[i + 1].x;
      svg += `<path class="edge" d="M${x1},${y + nh / 2} L${x2 - 2},${y + nh / 2}"/>`;
      svg += `<path class="pulse" d="M${x1},${y + nh / 2} L${x2 - 2},${y + nh / 2}" style="animation-delay:${i * 0.35}s"/>`;
      svg += `<line x1="${(x1 + x2) / 2}" y1="${y + nh / 2 + 4}" x2="${(x1 + x2) / 2}" y2="${y + nh + 12}" stroke="var(--line-2)" stroke-dasharray="2 3"/>`;
      svg += `<text class="cap" x="${(x1 + x2) / 2}" y="${y + nh + 26}" text-anchor="middle">${caps[i]}</text>`;
    }
    svg += `<rect class="node${c.end ? ' end' : ''}" x="${c.x}" y="${y}" width="${widths[i]}" height="${nh}" rx="10"/>`;
    svg += `<text x="${c.x + widths[i] / 2}" y="${y + (c.sub ? 24 : 32)}" text-anchor="middle" font-weight="600">${c.label}</text>`;
    if (c.sub) svg += `<text class="sub" x="${c.x + widths[i] / 2}" y="${y + 41}" text-anchor="middle">${c.sub}</text>`;
  });
  svg += `<path class="edge uncovered" d="M215,${y} C215,20 820,20 820,${y - 2}"/>
    <text class="cap" x="520" y="30" text-anchor="middle" style="fill:var(--bad)">unfilled · cost 10¹³ (only if nothing else fits)</text>
    <text class="sub" x="${w / 2}" y="${h - 16}" text-anchor="middle">${s.nodes} nodes · ${s.arcs} arcs · ${s.augmentations} augmenting paths in ${s.phases} Dijkstra phases · ${s.swaps} balancing swaps · ${s.millis.toFixed(1)} ms</text></svg>`;
  $('#tab-model').innerHTML = svg + `<div class="explain">
    <div class="card"><h4>Rules become capacities</h4><p>Day ⊂ week ⊂ period is a nested (laminar) family, so <code>max-per-day</code>, <code>max-per-week</code> and <code>max</code> are capacities on a chain of nodes — the whole rota stays a network flow.</p></div>
    <div class="card"><h4>Fairness is convex</h4><p>Your <i>k</i>-th shift costs (2k−1)/share: the sum is k²/share, so loading one person is always dearer than spreading. Parallel arcs with rising costs model it exactly.</p></div>
    <div class="card"><h4>Exact, not heuristic</h4><p>Primal-dual shortest paths (Dijkstra with Johnson potentials, then a blocking flow over zero-cost arcs) return a min-cost flow. Tests compare it against brute force on thousands of random rotas, on the JVM and in JS.</p></div>
    <div class="card"><h4>Proofs for “impossible”</h4><p>When a shift stays open, the residual graph shows every person who could take it is at a hard limit — even after reshuffling. That witness is the <i>Why</i> tab.</p></div>
  </div>`;
}

/* ── exports, share, examples, theme ──────────────────────────────────────── */
function download(name, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
const slug = (s) => s.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-|-$/g, '') || 'rota';
const stamp = () => new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');

function buildMenu() {
  const plan = state.plan;
  if (!plan) return;
  const people = plan.people.filter((p) => p.assigned > 0);
  els.menu.innerHTML = `<div class="label">Calendar (.ics)</div>
    <button data-x="ics" role="menuitem">📅 Everyone</button>
    ${people.map((p) => `<button data-x="ics" data-person="${esc(p.name)}" role="menuitem">👤 ${esc(p.name)}</button>`).join('')}
    <div class="sep"></div>
    <button data-x="csv" role="menuitem">▦ Spreadsheet (.csv)</button>
    <button data-x="md" role="menuitem">¶ Markdown table</button>
    <button data-x="rota" role="menuitem">⌘ The .rota file</button>`;
}

els.menu.addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b || !state.response?.ok) return closeMenu();
  const src = els.source.value;
  const name = slug(state.plan.name);
  try {
    if (b.dataset.x === 'ics') download(`${slug(b.dataset.person ?? name)}.ics`, rota.rotaIcs(src, b.dataset.person ?? null, stamp()), 'text/calendar');
    if (b.dataset.x === 'csv') download(`${name}.csv`, rota.rotaCsv(src), 'text/csv');
    if (b.dataset.x === 'md') download(`${name}.md`, rota.rotaMarkdown(src), 'text/markdown');
    if (b.dataset.x === 'rota') download(`${name}.rota`, src, 'text/plain');
  } catch (err) {
    toast(`Export failed: ${err.message ?? err}`);
  }
  closeMenu();
});
function closeMenu() { els.menu.hidden = true; els.download.setAttribute('aria-expanded', 'false'); }
els.download.addEventListener('click', (e) => {
  e.stopPropagation();
  if (!state.response?.ok) return toast('Fix the errors in the rota first');
  buildMenu();
  els.menu.hidden = !els.menu.hidden;
  els.download.setAttribute('aria-expanded', String(!els.menu.hidden));
});
document.addEventListener('click', (e) => { if (!e.target.closest('.menu')) closeMenu(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenu(); });

function toast(text) {
  els.toast.textContent = text;
  els.toast.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => els.toast.classList.remove('show'), 2200);
}

// Share links carry the rota itself (deflate + base64url), so no server is needed.
async function encodeShare(text) {
  const bytes = new TextEncoder().encode(text);
  let data = bytes;
  let prefix = 'r';
  if ('CompressionStream' in window) {
    data = new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer());
    prefix = 'z';
  }
  let bin = '';
  data.forEach((b) => { bin += String.fromCharCode(b); });
  return prefix + btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
async function decodeShare(token) {
  const bin = atob(token.slice(1).replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  if (token[0] !== 'z') return new TextDecoder().decode(bytes);
  const out = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer();
  return new TextDecoder().decode(out);
}

$('#share').addEventListener('click', async () => {
  const url = `${location.origin}${location.pathname}#s=${await encodeShare(els.source.value)}`;
  history.replaceState(null, '', url);
  try { await navigator.clipboard.writeText(url); toast('Link copied — it contains the whole rota'); } catch { toast('Link is in the address bar'); }
});

els.example.innerHTML = examples.map((e) => `<option value="${e.id}">${esc(e.title)}</option>`).join('');
els.example.addEventListener('change', () => {
  const e = examples.find((x) => x.id === els.example.value);
  els.source.value = e.source;
  history.replaceState(null, '', location.pathname);
  state.focus = null;
  solve();
});

const root = document.documentElement;
function setTheme(t) {
  root.dataset.theme = t;
  try { localStorage.setItem('rota:theme', t); } catch { /* ignore */ }
}
$('#theme').addEventListener('click', () => setTheme(root.dataset.theme === 'light' ? 'dark' : 'light'));

/* ── wiring ───────────────────────────────────────────────────────────────── */
els.source.addEventListener('input', () => { renderEditor(state.response?.ok ? [] : state.response?.diagnostics ?? []); scheduleSolve(); updateCursor(); });
els.source.addEventListener('scroll', syncScroll);
els.source.addEventListener('keyup', updateCursor);
els.source.addEventListener('click', updateCursor);
els.source.addEventListener('keydown', (e) => {
  if (e.key === 'Tab' && !e.shiftKey) {
    e.preventDefault();
    document.execCommand('insertText', false, '  ');
  }
});
els.diagnostics.addEventListener('click', (e) => {
  const li = e.target.closest('li[data-line]');
  if (li) goTo(+li.dataset.line, +li.dataset.col);
});
els.legend.addEventListener('click', (e) => {
  const b = e.target.closest('button[data-person]');
  if (!b) return;
  state.focus = state.focus === b.dataset.person ? null : b.dataset.person;
  applyFocus();
});
document.querySelector('.tabs').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-tab]');
  if (!b) return;
  state.tab = b.dataset.tab;
  document.querySelectorAll('.tabs button').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
  document.querySelectorAll('.tab-panel').forEach((p) => { p.hidden = p.id !== `tab-${state.tab}`; });
  els.legend.hidden = state.tab === 'model';
});

async function boot() {
  let theme = null;
  try { theme = localStorage.getItem('rota:theme'); } catch { /* ignore */ }
  setTheme(theme ?? (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'));
  const params = new URLSearchParams(location.hash.slice(1));
  let source = null;
  if (params.get('s')) {
    try { source = await decodeShare(params.get('s')); } catch { toast('That share link is damaged'); }
  }
  const ex = examples.find((e) => e.id === params.get('example'));
  if (ex) { source = ex.source; els.example.value = ex.id; }
  if (params.get('tab')) document.querySelector(`.tabs button[data-tab="${params.get('tab')}"]`)?.click();
  if (source == null) {
    try { source = localStorage.getItem('rota:source'); } catch { /* ignore */ }
  }
  els.source.value = source ?? examples[0].source;
  if (params.get('focus')) state.focus = params.get('focus');
  solve();
  updateCursor();
}
// Links like #example=cafe&tab=why work while the page is open, too.
window.addEventListener('hashchange', () => { if (!location.hash.startsWith('#s=') || location.hash.length > 3) boot(); });
boot();
