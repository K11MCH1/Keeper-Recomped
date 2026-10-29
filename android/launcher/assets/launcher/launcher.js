'use strict';
// Keeper Recomped launcher page. Java side: window.KR (LauncherActivity.Bridge);
// Java calls back through kr.onEvent(type, data).

const $ = (id) => document.getElementById(id);
const bridge = window.KR || null;

const kr = {
  st: null,
  progress: { running: false },
  seq: 0,
  pending: {},
  loaded: {},
  activeTab: 'play',

  onEvent(type, data) {
    if (type === 'progress') this.onProgress(data);
    else if (type === 'state') this.applyState(data);
    else if (type === 'update') this.onUpdate(data);
    else if (type === 'toast') toast(data.message);
    else if (type === 'api') {
      const cb = this.pending[data.id];
      delete this.pending[data.id];
      if (cb) data.ok ? cb.resolve(data.body) : cb.reject(new Error(data.error || 'failed'));
    }
  },

  // Back gesture: close the sheet, else return to Play, else let Android close the app.
  back() {
    if (!$('modal').classList.contains('hidden')) { closeModal(); return true; }
    if (this.activeTab !== 'play') { showTab('play'); return true; }
    return false;
  },

  api(endpoint) {
    return this.call((id) => bridge.api(endpoint, id));
  },

  // Async bridge call answered by an "api" event carrying the same id.
  call(start) {
    return new Promise((resolve, reject) => {
      if (!bridge) return reject(new Error('offline preview'));
      const id = 'r' + (++this.seq);
      this.pending[id] = { resolve, reject };
      start(id);
    });
  },

  refresh() {
    if (!bridge) return;
    try { this.applyState(JSON.parse(bridge.state())); } catch (e) { console.log('state: ' + e); }
  },

  applyState(s) {
    this.st = s;
    const state = s.state || {};
    $('appVersion').textContent = s.app_version ? 'v' + s.app_version : '';
    $('folderPath').textContent = s.folder || (state.source_path ? 'Imported: ' + state.source_path : 'No folder chosen');

    const facts = [];
    const fact = (k, v, cls) => facts.push(`<dt>${esc(k)}</dt><dd class="${cls || ''}">${esc(v)}</dd>`);
    if (state.imported_at) fact('Imported', (state.kfx_from_folder ? 'KeeperFX folder ' : 'Dungeon Keeper ') + (state.source_files || '') + ' files');
    const kv = state.kfx_version;
    fact('KeeperFX', kv ? (kv === 'folder' ? 'from your folder' : kv) + (state.alpha_version ? ' + alpha ' + state.alpha_version : '') : 'not installed', kv ? 'ok' : '');
    fact('Channel', s.channel === 'ALPHA' ? 'Alpha patches' : 'Stable');
    const ei = s.engine_info || {};
    const engineName = 'KeeperFX ' + (ei.version || '') + (ei.commit ? ' (' + ei.commit.slice(0, 7) + ')' : '') + ' · ARM64';
    fact('Engine', s.engine ? engineName : 'not in this build yet', s.engine ? 'ok' : 'bad');
    // Alpha patches change data and configs only; the engine is the one compiled into this app.
    if (s.engine && ei.title && state.alpha_title && state.alpha_title !== ei.title) {
      fact('Note', 'Alpha ' + (state.alpha_version || '') + ' data is newer than this app\'s engine build. '
        + 'Features that need engine changes arrive with an app update.', 'warn');
    }
    if (state.last_error) fact('Last error', state.last_error, 'bad');
    $('facts').innerHTML = facts.join('');

    const running = s.running || this.progress.running;
    const ready = s.kfx_ready && state.prepared;
    $('playKfx').disabled = running || !ready || !s.engine;
    $('playOriginal').disabled = running || !ready || !s.engine;
    $('originalCaption').textContent = 'Original campaign only, no mods, own saves — on the KeeperFX engine';
    $('kfxCaption').textContent = ready
      ? 'KeeperFX ' + (kv === 'folder' ? '' : kv || '') + (state.alpha_version ? ' alpha ' + state.alpha_version : '') + ' · campaigns, mods, multiplayer'
      : 'Prepare the game first';
    if (!this.progress.running && !state.prepared && !this.progress.result) {
      setProgress(0, s.folder || state.imported_at ? 'Ready to prepare' : 'Choose your game folder', '');
    } else if (!this.progress.running && state.prepared && !this.progress.result) {
      setProgress(100, 'Prepared ' + (state.prepared_at || '').slice(0, 10), '');
    }
    $('prepare').textContent = running ? 'Cancel' : (state.prepared ? 'Prepare & Build again' : 'Prepare & Build');
    $('prepare').classList.toggle('danger', !!running);
    $('pickFolder').disabled = !!running;

    const dev = [];
    if (s.device) dev.push(`<dt>Device</dt><dd>${esc(s.device)}</dd>`);
    if (s.display) dev.push(`<dt>Display</dt><dd>${esc(s.display)}</dd>`);
    $('deviceFacts').innerHTML = dev.join('');
  },

  onProgress(p) {
    this.progress = p;
    const box = $('progressBox');
    box.classList.toggle('running', !!p.running);
    box.classList.toggle('error', p.result === 'error');
    if (p.running) {
      setProgress(p.percent || 0, p.label || 'Working', p.detail || '');
    } else if (p.result === 'error') {
      setProgress(p.percent || 0, 'Failed', p.error || '');
    } else if (p.result === 'cancelled') {
      setProgress(p.percent || 0, 'Cancelled', '');
    } else if (p.result === 'ok') {
      setProgress(100, p.task === 'workshop' ? p.label : 'Ready to play', '');
      if (p.task === 'workshop') { toast(p.label || 'Installed'); this.loaded.workshop = false; if (this.activeTab === 'workshop') loadWorkshop(); }
      if (p.task === 'update') $('updateBanner').classList.add('hidden');
    }
    if (!p.running) this.refresh();
    else if (this.st && !this.st.running) { this.st.running = true; this.applyState(this.st); }
  },

  onUpdate(u) {
    $('updateText').textContent = u.version + ' is available' + (u.title ? ' — ' + u.title : '');
    $('updateBanner').classList.remove('hidden');
  },
};
window.kr = kr;

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setProgress(pct, label, detail) {
  $('progressFill').style.width = Math.max(0, Math.min(100, pct)) + '%';
  $('progressLabel').textContent = label;
  $('progressPct').textContent = pct > 0 && pct < 100 ? pct + '%' : '';
  $('progressDetail').textContent = detail || '';
}

let toastTimer = 0;
function toast(msg) {
  if (!msg) return;
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 3800);
}

function openModal(title, text) {
  $('modalTitle').textContent = title;
  $('modalBody').textContent = text;
  $('modal').classList.remove('hidden');
}
function closeModal() { $('modal').classList.add('hidden'); }

// Only formatting tags from keeperfx.net articles; links open in the browser.
function safeHtml(html) {
  const doc = new DOMParser().parseFromString('<div>' + (html || '') + '</div>', 'text/html');
  const allowed = new Set(['P', 'B', 'STRONG', 'I', 'EM', 'UL', 'OL', 'LI', 'A', 'BR', 'CODE', 'H1', 'H2', 'H3', 'H4', 'BLOCKQUOTE', 'DIV', 'SPAN']);
  const walk = (node) => {
    for (const child of Array.from(node.children)) {
      if (!allowed.has(child.tagName)) { child.replaceWith(document.createTextNode(child.textContent)); continue; }
      for (const attr of Array.from(child.attributes)) {
        if (!(child.tagName === 'A' && attr.name === 'href')) child.removeAttribute(attr.name);
      }
      if (child.tagName === 'A') {
        const href = child.getAttribute('href') || '';
        child.removeAttribute('href');
        if (/^https?:\/\//.test(href)) child.setAttribute('data-href', href);
      }
      walk(child);
    }
  };
  const root = doc.body.firstChild;
  walk(root);
  return root.innerHTML;
}

document.addEventListener('error', (e) => {
  if (e.target && e.target.tagName === 'IMG') e.target.remove();
}, true);

// ---- tabs ----
function showTab(name) {
  kr.activeTab = name;
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + name));
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  $('main').scrollTop = 0;
  if (name === 'workshop' && !kr.loaded.workshop) loadWorkshop();
  if (name === 'news' && !kr.loaded.news) loadNews();
  if (name === 'credits' && !kr.loaded.credits) loadLicenses();
}

function websiteOff(list) {
  if (kr.st && kr.st.website === false) {
    list.innerHTML = '<p class="empty">Website integration is off (Advanced → Launcher).</p>';
    return true;
  }
  return false;
}

async function loadNews() {
  const list = $('newsList');
  if (websiteOff(list)) return;
  list.innerHTML = '<p class="empty">Loading…</p>';
  try {
    const body = await kr.api('news/latest');
    kr.loaded.news = true;
    const items = body.articles || [];
    if (!items.length) { list.innerHTML = '<p class="empty">No news.</p>'; return; }
    list.innerHTML = (body._offline ? '<p class="hint">Offline — showing saved news.</p>' : '') + items.map((a) => `
      <article class="item news">
        ${a.image ? `<img loading="lazy" src="${esc(a.image)}" alt="">` : ''}
        <div class="body">
          <h4>${esc(a.title)}</h4>
          <div class="meta">${esc(a.created_timestamp || '')}</div>
          <div class="text">${safeHtml(a.excerpt_html || esc(a.excerpt || ''))}</div>
          <div class="actions"><button class="btn small" data-href="${esc(a.url || 'https://keeperfx.net')}">Read on keeperfx.net</button></div>
        </div>
      </article>`).join('');
  } catch (e) {
    list.innerHTML = `<p class="empty">Could not load news: ${esc(e.message)}</p>`;
  }
}

function workshopId(url) {
  const m = /\/workshop\/item\/(\d+)/.exec(url || '');
  return m ? m[1] : null;
}

async function loadWorkshop() {
  const list = $('workshopList');
  if (websiteOff(list)) return;
  list.innerHTML = '<p class="empty">Loading…</p>';
  try {
    const body = await kr.api('workshop/latest');
    kr.loaded.workshop = true;
    const installed = (kr.st && kr.st.state && kr.st.state.workshop) || {};
    const items = body.workshop_items || [];
    if (!items.length) { list.innerHTML = '<p class="empty">No workshop items.</p>'; return; }
    list.innerHTML = (body._offline ? '<p class="hint">Offline — showing saved items.</p>' : '') + items.map((w) => {
      const id = workshopId(w.url);
      const by = w.submitter && w.submitter.username ? ' · by ' + w.submitter.username : '';
      return `
      <article class="item">
        ${w.thumbnail || w.image ? `<img loading="lazy" src="${esc(w.thumbnail || w.image)}" alt="">` : ''}
        <div class="body">
          <h4>${esc(w.name)}</h4>
          <div class="meta">${esc(w.category || '')}${esc(by)} · ${esc(w.created_timestamp || '')}
            ${id && installed[id] ? ' <span class="badge">Installed</span>' : ''}</div>
          <div class="text">${esc(w.description || '')}</div>
          <div class="actions">
            ${id ? `<button class="btn small" data-install="${esc(id)}" data-name="${esc(w.name)}" data-url="${esc(w.url)}">${installed[id] ? 'Reinstall' : 'Install'}</button>` : ''}
            <button class="btn small" data-href="${esc(w.url)}">Details</button>
          </div>
        </div>
      </article>`;
    }).join('');
  } catch (e) {
    list.innerHTML = `<p class="empty">Could not load the workshop: ${esc(e.message)}</p>`;
  }
}

async function installWorkshop(id, name, pageUrl) {
  if (!kr.st || !kr.st.kfx_ready) { toast('Prepare KeeperFX first (Play tab).'); return; }
  try {
    const body = await kr.call((cb) => bridge.workshopFiles(id, pageUrl, cb));
    const files = (body.files || []).filter((f) => /\.(zip|7z)$/i.test(f.filename || ''));
    if (!files.length) {
      toast('No direct download for this item — opening keeperfx.net.');
      bridge && bridge.open(pageUrl);
      return;
    }
    const f = files[0];
    const err = bridge.installWorkshop(id, name, f.url, f.filename, String(f.size || -1));
    if (err) toast(err); else { toast('Installing ' + name + '…'); showTab('play'); }
  } catch (e) {
    toast('Install failed: ' + e.message);
  }
}

function loadLicenses() {
  kr.loaded.credits = true;
  let names = [];
  try { names = JSON.parse(bridge ? bridge.licenses() : '[]'); } catch (e) { names = []; }
  $('licenseList').innerHTML = names.length
    ? names.map((n) => `<button class="btn small" data-license="${esc(n)}">${esc(n.replace(/\.txt$/, ''))}</button>`).join('')
    : '<p class="hint">No licence files in this build.</p>';
}

// ---- settings forms ----
function renderForms() {
  let schema = { settings: [] };
  try { schema = JSON.parse(bridge ? bridge.schema() : '{"settings":[]}'); } catch (e) { console.log('schema: ' + e); }
  const byTab = {};
  for (const d of schema.settings) {
    (byTab[d.tab] = byTab[d.tab] || {});
    (byTab[d.tab][d.section] = byTab[d.tab][d.section] || []).push(d);
  }
  document.querySelectorAll('[data-form]').forEach((form) => {
    const sections = byTab[form.dataset.form] || {};
    form.innerHTML = Object.keys(sections).map((sec) => `
      <h3>${esc(sec)}</h3>
      <div class="card">${sections[sec].map(field).join('')}</div>`).join('');
  });
}

function field(d) {
  const help = d.help ? `<small>${esc(d.help)}</small>` : '';
  let control = '';
  if (d.type === 'bool') {
    control = `<label class="switch"><input type="checkbox" data-key="${d.key}" ${d.value === '1' ? 'checked' : ''}><span></span></label>`;
  } else if (d.type === 'choice') {
    control = `<select data-key="${d.key}">${d.options.map(([v, l]) => `<option value="${esc(v)}" ${v === d.value ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>`;
  } else if (d.type === 'int') {
    control = `<input type="number" inputmode="numeric" data-key="${d.key}" min="${d.min}" max="${d.max}" value="${esc(d.value)}">`;
  } else {
    control = `<input type="text" data-key="${d.key}" value="${esc(d.value)}" spellcheck="false" autocapitalize="off">`;
  }
  return `<div class="field"><div class="lbl">${esc(d.label)}${help}</div>${control}</div>`;
}

function onSettingChange(el) {
  const key = el.dataset.key;
  const value = el.type === 'checkbox' ? (el.checked ? '1' : '0') : el.value;
  const err = bridge ? bridge.set(key, value) : '';
  if (err) { toast(err); renderForms(); return; }
  if (key === 'release_channel' || key === 'website_integration') { kr.loaded = {}; kr.refresh(); }
}

// ---- wiring ----
document.addEventListener('click', (e) => {
  const t = e.target.closest('button, a');
  if (!t) return;
  if (t.dataset.tab) { showTab(t.dataset.tab); return; }
  if (t.dataset.href) { e.preventDefault(); bridge && bridge.open(t.dataset.href); return; }
  if (t.dataset.install) { installWorkshop(t.dataset.install, t.dataset.name, t.dataset.url); return; }
  if (t.dataset.refresh === 'news') { loadNews(); return; }
  if (t.dataset.refresh === 'workshop') { loadWorkshop(); return; }
  if (t.dataset.license) { openModal(t.dataset.license, bridge ? bridge.license(t.dataset.license) : ''); return; }
});
document.addEventListener('change', (e) => { if (e.target.dataset && e.target.dataset.key) onSettingChange(e.target); });

$('pickFolder').onclick = () => bridge && bridge.pickFolder();
$('prepare').onclick = () => {
  if (!bridge) return;
  if (kr.progress.running || (kr.st && kr.st.running)) { bridge.cancel(); return; }
  const err = bridge.prepare();
  if (err) toast(err); else { kr.progress = { running: true }; setProgress(0, 'Starting…', ''); kr.refresh(); }
};
$('playOriginal').onclick = () => { const err = bridge ? bridge.play('original') : 'Preview'; if (err) toast(err); };
$('playKfx').onclick = () => { const err = bridge ? bridge.play('keeperfx') : 'Preview'; if (err) toast(err); };
$('updateNow').onclick = () => { const err = bridge ? bridge.update() : ''; if (err) toast(err); };
$('checkUpdate').onclick = () => { const err = bridge ? bridge.update() : ''; if (err) toast(err); else { toast('Checking for updates…'); showTab('play'); } };
$('resetSettings').onclick = () => { if (bridge) { bridge.resetSettings(); renderForms(); toast('Settings reset to defaults.'); } };
$('modalClose').onclick = closeModal;
$('modal').addEventListener('click', (e) => { if (e.target.id === 'modal') closeModal(); });

renderForms();
kr.refresh();
