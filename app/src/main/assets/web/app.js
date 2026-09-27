/*
 * §7.3–7.7 the WebShare site.
 *
 * Rules this file exists to honour, each of which is an acceptance criterion:
 *  - nothing is fetched before the phone accepts (§7.1, A5);
 *  - every call after /api/hello carries the token (§17.3);
 *  - per-item selection on EVERY page with a working select-all, plain
 *    [⇓ Download] beside [⇊ Download as zip] (A21);
 *  - no filename text under photo/video tiles (A12) and a mandatory lightbox
 *    (A27);
 *  - the audio bar survives page changes and can really be closed (A20);
 *  - folders are selectable objects with a real recursive size (A21);
 *  - exactly one upload progress UI (INV-6, A12).
 */
'use strict';

const state = {
  token: null,
  page: 'home',
  items: [],
  folder: null,
  selection: new Map(),   // key -> { path, name, size, dir }
  counts: {},
  info: {},
  audio: null,            // the single <audio> element, created on demand
  queue: [],
  queueIndex: 0,
  lightboxIndex: 0,
  uploads: new Map(),
};

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

async function api(path, options) {
  const opts = options || {};
  opts.headers = Object.assign({}, opts.headers, { 'X-Morsecode-Token': state.token || '' });
  const response = await fetch(path, opts);
  if (response.status === 401) {
    // The token died with the session or the server (§7.1): back to the gate.
    state.token = null;
    showGate('This session was ended on the phone.', 'Ask again to reconnect.');
    throw new Error('unauthorised');
  }
  return response;
}

const json = async (path) => (await api(path)).json();

function showGate(title, body) {
  $('gate').hidden = false;
  $('shell').hidden = true;
  $('gate-title').textContent = title;
  $('gate-body').textContent = body;
}

async function handshake() {
  const response = await fetch('/api/hello', { headers: { 'X-Morsecode-Token': state.token || '' } });
  const data = await response.json();
  if (data.state === 'accepted') {
    state.token = data.token;
    $('gate').hidden = true;
    $('shell').hidden = false;
    await boot();
    return;
  }
  if (data.state === 'rejected') {
    showGate('Request declined', 'The phone declined this browser.');
    return;
  }
  setTimeout(handshake, 1200);
}

// ---------------------------------------------------------------------------
// Boot and chrome
// ---------------------------------------------------------------------------

async function boot() {
  state.info = await json('/api/info');
  document.body.dataset.theme = state.info.dark ? 'dark' : 'light';
  // §7.3: the site follows the phone's accent.
  const accents = { sunflower: '#FACC15', leaf: '#84CC16', ember: '#EA580C', violet: '#8B5CF6', sky: '#0EA5E9' };
  if (accents[state.info.accent]) {
    document.documentElement.style.setProperty('--accent', accents[state.info.accent]);
  }
  $('device-name').textContent = state.info.device || 'Phone';
  $('device-address').textContent = location.host + ' · WebShare session';
  await refreshCounts();
  listenForOffers();
  go('home');
}

async function refreshCounts() {
  state.counts = await json('/api/counts');
  const map = { photos: 'photos', videos: 'videos', music: 'music', docs: 'documents', apps: 'apps' };
  for (const [key, field] of Object.entries(map)) {
    const el = $('count-' + key);
    if (el) el.textContent = state.counts[field] || 0;
  }
}

function go(page) {
  state.page = page;
  state.folder = null;
  for (const item of document.querySelectorAll('.rail-item')) {
    item.setAttribute('aria-current', item.dataset.page === page ? 'page' : 'false');
  }
  render();
}

// ---------------------------------------------------------------------------
// Selection — one basket for every page (§7.5, A21)
// ---------------------------------------------------------------------------

const keyOf = (entry) => entry.path || entry.name;

/*
 * A31: "Scroll a long list to the middle, tick an item: the view does not
 * move." Ticking therefore PATCHES the affected nodes and the bar; it never
 * re-renders the page, because re-rendering resets scrollTop and loses the
 * place someone was reading.
 */
function toggle(entry) {
  const key = keyOf(entry);
  if (state.selection.has(key)) state.selection.delete(key);
  else state.selection.set(key, entry);
  patchSelection();
}

function selectAll(entries) {
  const all = entries.every((entry) => state.selection.has(keyOf(entry)));
  for (const entry of entries) {
    if (all) state.selection.delete(keyOf(entry));
    else state.selection.set(keyOf(entry), entry);
  }
  patchSelection();
}

/** Repaints only what selection changed: the ticks, the rows and the bar. */
function patchSelection() {
  for (const tile of document.querySelectorAll('.tile[data-key]')) {
    const selected = state.selection.has(tile.dataset.key);
    tile.setAttribute('aria-selected', selected);
    const check = tile.querySelector('[data-check]');
    if (check) check.setAttribute('aria-checked', selected);
  }
  for (const box of document.querySelectorAll('input[data-check], input[data-fcheck]')) {
    const key = box.dataset.check || box.dataset.fcheck;
    const selected = state.selection.has(key);
    box.checked = selected;
    const row = box.closest('tr');
    if (row) row.setAttribute('aria-selected', selected);
  }
  for (const label of document.querySelectorAll('.selected-count')) {
    label.textContent = `${state.selection.size} selected`;
  }
  for (const button of document.querySelectorAll('[data-selectall]')) {
    const group = (state.groups || []).find((g) => g.title === button.dataset.selectall);
    if (group) {
      button.textContent = group.items.every((i) => state.selection.has(keyOf(i)))
        ? '✓ Selected' : 'Select all';
    }
  }
  paintSelectionBar();
}

/** The bar is created once and updated in place, never re-inserted. */
function paintSelectionBar() {
  let bar = document.getElementById('selbar');
  if (state.selection.size === 0) {
    if (bar) bar.remove();
    return;
  }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'selbar';
    bar.className = 'selbar';
    $('page').appendChild(bar);
  }
  const total = [...state.selection.values()].reduce((sum, entry) => sum + (entry.size || 0), 0);
  bar.innerHTML = `<strong>${state.selection.size} selected</strong>
    <span class="mono">${fmtSize(total)}</span>
    <button class="btn-outline" data-act="download">⇓ Download</button>
    <button class="btn-accent" data-act="zip">⇊ Download as zip</button>
    <button class="btn-outline" data-act="clear">Clear</button>`;
  wireBar(bar);
}

/** Rendered once per page build; afterwards [paintSelectionBar] patches it. */
function selectionBar() {
  return '';
}

/** A21: [Download] fetches each file directly; one plain download for one file. */
function downloadSelection() {
  for (const entry of state.selection.values()) {
    if (entry.dir) {
      window.location = `/download-folder?path=${encodeURIComponent(entry.path)}&token=${state.token}`;
    } else {
      window.location = `/download?path=${encodeURIComponent(entry.path)}&token=${state.token}`;
    }
  }
}

/** A21: [⇊ Download as zip] streams ONE ZIP64 archive. */
function downloadZip() {
  const paths = [...state.selection.values()].map((entry) => entry.path).join('\n');
  const form = document.createElement('form');
  form.method = 'GET';
  form.action = '/download-zip';
  form.innerHTML = `<input name="paths" value="${paths.replace(/"/g, '&quot;')}">
                    <input name="token" value="${state.token}">`;
  document.body.appendChild(form);
  form.submit();
  form.remove();
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

async function render() {
  const page = $('page');
  if (state.page === 'home') return renderHome(page);
  if (state.page === 'files') return renderFiles(page);
  if (state.page === 'photos' || state.page === 'videos') return renderGallery(page, state.page);
  if (state.page === 'music') return renderMusic(page);
  return renderTable(page, state.page);
}

function renderHome(root) {
  const c = state.counts;
  const used = c.storageUsed || 0;
  const total = c.storageTotal || 1;
  root.innerHTML = `
    <div class="cards">
      ${statCard(c.photos, 'PHOTOS', 'photos')}
      ${statCard(c.videos, 'VIDEOS', 'videos')}
      ${statCard(c.music, 'MUSIC', 'music')}
      ${statCard(c.documents, 'DOCS', 'docs')}
      ${statCard(c.apps, 'APPS', 'apps')}
    </div>
    <div class="card">
      <div style="display:flex;justify-content:space-between;align-items:center">
        <strong>Storage</strong><span class="mono">${fmtSize(used)} of ${fmtSize(total)} used</span>
      </div>
      <div class="storage-bar" style="margin-top:10px">
        <div class="storage-fill" style="width:${Math.min(100, (used / total) * 100)}%"></div>
      </div>
    </div>
    <div class="dropzone" id="dropzone">
      <div class="drop-glyph">⇪</div>
      <strong>Upload files to phone</strong>
      <span class="mono">Drag files here or click to send to phone</span>
      <button class="btn-accent" id="choose-files">Choose files</button>
    </div>`;
  $('choose-files').onclick = () => $('file-input').click();
  const zone = $('dropzone');
  zone.ondragover = (event) => { event.preventDefault(); zone.classList.add('hot'); };
  zone.ondragleave = () => zone.classList.remove('hot');
  zone.ondrop = (event) => {
    event.preventDefault();
    zone.classList.remove('hot');
    uploadFiles([...event.dataTransfer.files]);
  };
  for (const card of root.querySelectorAll('[data-page]')) {
    card.onclick = () => go(card.dataset.page);
  }
}

const statCard = (value, label, page) =>
  `<button class="stat-card" data-page="${page}">
     <div class="stat-value">${value || 0}</div><div class="stat-label">${label}</div>
   </button>`;

/** §7.5 Photos and Videos: folder panel ALWAYS present, day-grouped grid. */
async function renderGallery(root, page) {
  const data = await json(`/api/files?category=${page === 'photos' ? 'PHOTOS' : 'VIDEOS'}&pageSize=400`);
  state.items = data.items;

  const folders = new Map();
  for (const item of state.items) {
    const name = item.folder || 'Internal storage';
    if (!folders.has(name)) folders.set(name, { count: 0, newest: item });
    folders.get(name).count += 1;
  }
  const shown = state.folder ? state.items.filter((i) => i.folder === state.folder) : state.items;
  const groups = groupByDay(shown);
  state.groups = groups;

  root.innerHTML = `
    <div class="toolbar">
      <input type="search" id="search" placeholder="Search this category" />
      <select id="sort"><option value="new">Newest first</option><option value="old">Oldest first</option>
        <option value="size">Largest first</option><option value="name">Name</option></select>
      <span class="selected-count">${state.selection.size} selected</span>
    </div>
    <div class="split">
      <aside class="folders" aria-label="Folders">
        ${folderRow(page === 'photos' ? 'All photos' : 'All videos', shown.length, null, state.items[0])}
        ${[...folders.entries()].map(([name, info]) => folderRow(name, info.count, name, info.newest)).join('')}
      </aside>
      <div>
        ${groups.map((group) => `
          <div class="group-head">
            <span class="group-title">${group.title} · ${group.items.length} items</span>
            <button class="row-btn" data-selectall="${group.title}">
              ${group.items.every((i) => state.selection.has(keyOf(i))) ? '✓ Selected' : 'Select all'}
            </button>
          </div>
          <div class="grid">${group.items.map(tile).join('')}</div>`).join('') ||
          '<p class="mono">No files here</p>'}
      </div>
    </div>
    ${selectionBar()}`;

  wireSelection(root, shown, groups);
  paintSelectionBar();
  for (const row of root.querySelectorAll('[data-folder]')) {
    row.onclick = () => { state.folder = row.dataset.folder || null; render(); };
  }
  $('search').oninput = (event) => {
    const query = event.target.value.toLowerCase();
    for (const el of root.querySelectorAll('.tile')) {
      el.style.display = el.dataset.name.toLowerCase().includes(query) ? '' : 'none';
    }
  };
}

const folderRow = (name, count, value, newest) => `
  <button class="folder-row" data-folder="${value || ''}" aria-current="${(value || null) === state.folder}">
    <img class="folder-thumb" alt="" src="${newest ? thumbUrl(newest) : ''}" />
    <span>${name}</span><span class="folder-count">${count}</span>
  </button>`;

/** A12: NO filename text under photo or video tiles. */
const tile = (item) => `
  <button class="tile" data-key="${keyOf(item)}" data-name="${item.name}"
          aria-selected="${state.selection.has(keyOf(item))}" aria-label="${item.name}">
    <img loading="lazy" alt="" src="${thumbUrl(item)}" />
    ${item.type === 'video' ? '<span class="play" aria-hidden="true">▶</span>' : ''}
    ${item.duration ? `<span class="dur">${fmtDuration(item.duration)}</span>` : ''}
    <span class="check" data-check="${keyOf(item)}" role="checkbox"
          aria-checked="${state.selection.has(keyOf(item))}">✓</span>
  </button>`;

function wireSelection(root, items, groups) {
  for (const check of root.querySelectorAll('[data-check]')) {
    check.onclick = (event) => {
      event.stopPropagation();
      toggle(items.find((i) => keyOf(i) === check.dataset.check));
    };
  }
  for (const el of root.querySelectorAll('.tile')) {
    el.onclick = () => {
      const item = items.find((i) => keyOf(i) === el.dataset.key);
      // A21: clicking a video tile streams it; it NEVER starts playback from
      // the checkbox, and A27 makes the photo click open the lightbox.
      if (item.type === 'video') openVideo(item);
      else openLightbox(items.indexOf(item), items);
    };
  }
  for (const button of root.querySelectorAll('[data-selectall]')) {
    button.onclick = () => {
      const group = groups.find((g) => g.title === button.dataset.selectall);
      selectAll(group.items);
    };
  }
  wireBar(root);
}

function wireBar(root) {
  for (const button of (root || document).querySelectorAll('[data-act]')) {
    button.onclick = () => {
      if (button.dataset.act === 'download') downloadSelection();
      if (button.dataset.act === 'zip') downloadZip();
      if (button.dataset.act === 'clear') { state.selection.clear(); patchSelection(); }
    };
  }
}

/** §7.5 MUSIC: a table with a real control per row and the player bar. */
async function renderMusic(root) {
  const data = await json('/api/files?category=MUSIC&pageSize=500');
  state.items = data.items;
  root.innerHTML = `
    <div class="toolbar"><input type="search" id="search" placeholder="Search this category" />
      <span class="selected-count">${state.selection.size} selected</span></div>
    <table><thead><tr><th></th><th>Title</th><th>Artist</th><th>Duration</th><th>Size</th><th></th></tr></thead>
    <tbody>${state.items.map((item) => `
      <tr aria-selected="${state.selection.has(keyOf(item))}">
        <td><input type="checkbox" data-check="${keyOf(item)}"
             ${state.selection.has(keyOf(item)) ? 'checked' : ''} aria-label="Select ${item.name}"></td>
        <td><button class="row-btn" data-play="${keyOf(item)}">${item.name}</button></td>
        <td>${item.artist || 'Unknown artist'}</td>
        <td class="mono">${fmtDuration(item.duration)}</td>
        <td class="mono">${fmtSize(item.size)}</td>
        <td><button class="row-btn" data-dl="${keyOf(item)}">⇓</button></td>
      </tr>`).join('')}</tbody></table>
    ${selectionBar()}`;

  for (const check of root.querySelectorAll('[data-check]')) {
    check.onchange = () => toggle(state.items.find((i) => keyOf(i) === check.dataset.check));
  }
  for (const button of root.querySelectorAll('[data-play]')) {
    button.onclick = () => playTrack(state.items.findIndex((i) => keyOf(i) === button.dataset.play));
  }
  for (const button of root.querySelectorAll('[data-dl]')) {
    button.onclick = () => {
      window.location = `/download?path=${encodeURIComponent(button.dataset.dl)}&token=${state.token}`;
    };
  }
  wireBar(root);
}

/** Docs and Apps: a table and a grid, both with per-item selection (A21). */
async function renderTable(root, page) {
  const category = page === 'docs' ? 'DOCUMENTS' : 'APPS';
  const data = await json(`/api/files?category=${category}&pageSize=500`);
  state.items = data.items;
  root.innerHTML = `
    <div class="toolbar"><input type="search" id="search" placeholder="Search this category" />
      <button class="row-btn" id="select-all-page">Select all</button>
      <span class="selected-count">${state.selection.size} selected</span></div>
    <table><thead><tr><th></th><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead>
    <tbody>${state.items.map((item) => `
      <tr aria-selected="${state.selection.has(keyOf(item))}">
        <td><input type="checkbox" data-check="${keyOf(item)}"
             ${state.selection.has(keyOf(item)) ? 'checked' : ''} aria-label="Select ${item.name}"></td>
        <td>${item.name}</td>
        <td class="mono">${fmtSize(item.size)}</td>
        <td class="mono">${new Date(item.date).toLocaleDateString()}</td>
        <td><button class="row-btn" data-dl="${keyOf(item)}">
          ${page === 'apps' ? 'Download APK' : '⇓'}</button></td>
      </tr>`).join('')}</tbody></table>
    ${selectionBar()}`;

  $('select-all-page').onclick = () => selectAll(state.items);
  for (const check of root.querySelectorAll('[data-check]')) {
    check.onchange = () => toggle(state.items.find((i) => keyOf(i) === check.dataset.check));
  }
  for (const button of root.querySelectorAll('[data-dl]')) {
    button.onclick = () => {
      window.location = `/download?path=${encodeURIComponent(button.dataset.dl)}&token=${state.token}`;
    };
  }
  wireBar(root);
}

/** §7.5 FILES: a quick-folder panel beside a real listing, folders selectable. */
async function renderFiles(root, path) {
  const data = await json('/api/fs' + (path ? `?path=${encodeURIComponent(path)}` : ''));
  const quick = ['Download', 'DCIM', 'Pictures', 'Movies', 'Music', 'Documents', 'WhatsApp', 'Telegram', 'Morsecode'];
  root.innerHTML = `
    <nav class="toolbar" aria-label="Breadcrumb">
      ${data.breadcrumb.map((crumb) =>
        `<button class="row-btn" data-path="${crumb.path}">${crumb.label}</button>`).join(' › ')}
    </nav>
    <div class="split">
      <aside class="folders">
        ${quick.map((name) => `<button class="folder-row" data-quick="${name}"><span>${name}</span></button>`).join('')}
      </aside>
      <div>
        ${data.denied
          ? '<p class="card">Access needed to view this folder — grant it on the phone.</p>'
          : `<table><thead><tr><th></th><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead>
             <tbody>${data.entries.map((entry) => `
               <tr aria-selected="${state.selection.has(entry.path)}">
                 <td><input type="checkbox" data-fcheck="${entry.path}"
                      ${state.selection.has(entry.path) ? 'checked' : ''} aria-label="Select ${entry.name}"></td>
                 <td>${entry.dir
                      ? `<button class="row-btn" data-open="${entry.path}">📁 ${entry.name}</button>`
                      : entry.name}</td>
                 <td class="mono">${fmtSize(entry.size)}</td>
                 <td class="mono">${new Date(entry.modified).toLocaleDateString()}</td>
                 <td>${entry.dir
                      ? `<button class="row-btn" data-zip="${entry.path}">⇊ zip</button>`
                      : `<button class="row-btn" data-dl="${entry.path}">⇓</button>`}</td>
               </tr>`).join('')}</tbody></table>`}
      </div>
    </div>
    ${selectionBar()}`;

  const rows = data.entries.map((entry) => ({
    path: entry.path, name: entry.name, size: entry.size, dir: entry.dir,
  }));
  for (const check of root.querySelectorAll('[data-fcheck]')) {
    check.onchange = () => toggle(rows.find((r) => r.path === check.dataset.fcheck));
  }
  for (const button of root.querySelectorAll('[data-open]')) {
    button.onclick = () => renderFiles(root, button.dataset.open);
  }
  for (const button of root.querySelectorAll('[data-path]')) {
    button.onclick = () => renderFiles(root, button.dataset.path);
  }
  for (const button of root.querySelectorAll('[data-quick]')) {
    button.onclick = () => renderFiles(root, `/storage/emulated/0/${button.dataset.quick}`);
  }
  for (const button of root.querySelectorAll('[data-zip]')) {
    button.onclick = () => {
      window.location = `/download-folder?path=${encodeURIComponent(button.dataset.zip)}&token=${state.token}`;
    };
  }
  for (const button of root.querySelectorAll('[data-dl]')) {
    button.onclick = () => {
      window.location = `/download?path=${encodeURIComponent(button.dataset.dl)}&token=${state.token}`;
    };
  }
  wireBar(root);
}

// ---------------------------------------------------------------------------
// Lightbox (A27) — mandatory, not optional
// ---------------------------------------------------------------------------

function openLightbox(index, items) {
  state.lightboxIndex = index;
  state.lightboxItems = items;
  const box = $('lightbox');
  box.hidden = false;
  paintLightbox();
  $('lightbox-close').focus();
}

function paintLightbox() {
  const item = state.lightboxItems[state.lightboxIndex];
  $('lightbox-image').src = thumbUrl(item);
  $('lightbox-name').textContent = item.name;
  $('lightbox-info').textContent =
    `${state.lightboxIndex + 1} of ${state.lightboxItems.length} · ${fmtSize(item.size)}`;
  $('lightbox-select').textContent = state.selection.has(keyOf(item)) ? '✓ Selected' : 'Select';
}

function stepLightbox(delta) {
  const count = state.lightboxItems.length;
  state.lightboxIndex = (state.lightboxIndex + delta + count) % count;
  paintLightbox();
}

function closeLightbox() { $('lightbox').hidden = true; }

// ---------------------------------------------------------------------------
// Audio (A20) — survives page changes, and can really be closed
// ---------------------------------------------------------------------------

function playTrack(index) {
  state.queue = state.items.filter((i) => i.type === 'audio' || i.mime.startsWith('audio'));
  state.queueIndex = Math.max(0, state.queue.findIndex((i) => keyOf(i) === keyOf(state.items[index])));
  const track = state.queue[state.queueIndex] || state.items[index];
  if (!state.audio) {
    state.audio = new Audio();
    state.audio.addEventListener('timeupdate', paintPlayer);
    state.audio.addEventListener('ended', () => nextTrack(1));
  }
  state.audio.src = `/download?path=${encodeURIComponent(keyOf(track))}&token=${state.token}`;
  state.audio.volume = $('player-volume').value / 100;
  state.audio.play();
  $('player').hidden = false;
  $('player-title').textContent = track.name;
  $('player-artist').textContent = track.artist || 'Unknown artist';
  paintPlayer();
}

function nextTrack(delta) {
  if (state.queue.length === 0) return;
  state.queueIndex = (state.queueIndex + delta + state.queue.length) % state.queue.length;
  playTrack(state.items.indexOf(state.queue[state.queueIndex]));
}

function paintPlayer() {
  const audio = state.audio;
  if (!audio || !audio.duration) return;
  if (!state.scrubbing) {
    $('player-range').value = Math.round((audio.currentTime / audio.duration) * 1000);
  }
  $('player-elapsed').textContent = fmtClock(audio.currentTime);
  $('player-total').textContent = fmtClock(audio.duration);
}

/** A20: ✕ stops playback, RELEASES the element and dismisses the bar. */
function closePlayer() {
  if (state.audio) {
    state.audio.pause();
    state.audio.removeAttribute('src');
    state.audio.load();
    state.audio = null;
  }
  $('player').hidden = true;
}

/**
 * §7.6 THE IN-BROWSER VIDEO PLAYER. Route /video/<name>, the rail kept, a top
 * bar with back / name / mono meta / download, a 16:9 stage, and the SAME seek
 * contract as every other player (A32): tap-to-seek, a draggable knob, labels
 * that follow the thumb, the clock yielding during a drag, and a commit that
 * sets currentTime — which issues a fresh Range request answered with 206.
 *
 * Closing REMOVES the element from the DOM. A hidden <video> keeps playing
 * audio with nothing on screen to stop it (§7.6).
 */
function openVideo(item) {
  history.replaceState(null, '', `/video/${encodeURIComponent(item.name)}`);
  const root = $('page');
  root.innerHTML = `
    <div class="toolbar">
      <button class="row-btn" id="video-back" aria-label="Back">‹</button>
      <strong>${item.name}</strong>
      <span class="mono">${fmtSize(item.size)} · ${fmtDuration(item.duration)}</span>
      <button class="btn-outline" id="video-download" style="margin-left:auto">⇓ Download</button>
    </div>
    <div class="stage">
      <video id="video" playsinline preload="metadata"
             src="/stream?path=${encodeURIComponent(keyOf(item))}&token=${state.token}"></video>
    </div>
    <div class="player-seek">
      <input id="video-range" class="seek" type="range" min="0" max="1000" value="0"
             aria-label="Playback position" />
      <div class="player-clocks mono"><span id="video-elapsed">0:00</span><span id="video-total">0:00</span></div>
    </div>
    <div class="toolbar" role="group" aria-label="Playback controls">
      <button class="icon-btn" id="video-back10" aria-label="Back 10 seconds">↺10</button>
      <button class="btn-accent" id="video-play" aria-label="Play">▶</button>
      <button class="icon-btn" id="video-fwd10" aria-label="Forward 10 seconds">10↻</button>
      <label class="volume"><span aria-hidden="true">🔊</span>
        <input id="video-volume" type="range" min="0" max="100" value="100" aria-label="Volume" /></label>
      <button class="icon-btn" id="video-cc" aria-label="Subtitles" disabled
              title="No subtitles">CC</button>
    </div>`;

  const video = $('video');
  const range = $('video-range');
  let scrubbing = false;

  const paint = () => {
    if (!video.duration) return;
    if (!scrubbing) range.value = Math.round((video.currentTime / video.duration) * 1000);
    $('video-elapsed').textContent = fmtClock(video.currentTime);
    $('video-total').textContent = fmtClock(video.duration);
  };
  const seekTo = (seconds) => {
    // Clamped at both ends; ±10 never wraps around (§6.11, A32).
    video.currentTime = Math.min(Math.max(0, seconds), video.duration || 0);
    paint();
  };

  video.addEventListener('loadedmetadata', paint);
  video.addEventListener('timeupdate', paint);
  video.addEventListener('play', () => { $('video-play').textContent = '❚❚'; });
  video.addEventListener('pause', () => { $('video-play').textContent = '▶'; });

  range.oninput = () => {
    // The clock stops driving the bar while a finger is down.
    scrubbing = true;
    if (video.duration) {
      $('video-elapsed').textContent = fmtClock((range.value / 1000) * video.duration);
    }
  };
  range.onchange = () => {
    scrubbing = false;
    if (video.duration) seekTo((range.value / 1000) * video.duration);
  };

  $('video-play').onclick = () => (video.paused ? video.play() : video.pause());
  $('video-back10').onclick = () => seekTo(video.currentTime - 10);
  $('video-fwd10').onclick = () => seekTo(video.currentTime + 10);
  // A real volume control bound to .volume, never a bare mute button (§6.11).
  $('video-volume').oninput = (event) => {
    video.volume = event.target.value / 100;
    video.muted = video.volume === 0;
  };
  $('video-download').onclick = () => {
    window.location = `/download?path=${encodeURIComponent(keyOf(item))}&token=${state.token}`;
  };
  $('video-back').onclick = () => {
    video.pause();
    video.removeAttribute('src');
    video.load();
    video.remove();
    history.replaceState(null, '', '/');
    render();
  };
  document.addEventListener('keydown', videoKeys);
  function videoKeys(event) {
    if (!document.body.contains(video)) {
      document.removeEventListener('keydown', videoKeys);
      return;
    }
    if (event.key === 'ArrowLeft') seekTo(video.currentTime - 5);
    if (event.key === 'ArrowRight') seekTo(video.currentTime + 5);
    if (event.key === 'Home') seekTo(0);
    if (event.key === 'End') seekTo(video.duration);
  }
}

// ---------------------------------------------------------------------------
// Uploads (§7.7) — 4 MB chunks, resumable, ONE progress UI (INV-6)
// ---------------------------------------------------------------------------

const CHUNK = 4 * 1024 * 1024;

async function uploadFiles(files) {
  // §7.7: multiple concurrent uploads are allowed; INV-6 keeps ONE progress UI.
  await Promise.all([...files].map((file) => uploadOne(file)));
  await refreshCounts();
}

async function uploadOne(file) {
  const id = `${file.name}-${file.size}-${file.lastModified}`;
  const chunks = Math.max(1, Math.ceil(file.size / CHUNK));
  // Resume: ask for the last index rather than restarting from zero (§7.7).
  let start = 0;
  try {
    const status = await json(`/api/upload-status?id=${encodeURIComponent(id)}`);
    start = status.nextIndex || 0;
  } catch (error) { start = 0; }

  const entry = state.uploads.get(id) || { name: file.name, sent: start * CHUNK, total: file.size };
  entry.paused = false;
  entry.cancelled = false;
  entry.file = file;
  entry.id = id;
  state.uploads.set(id, entry);
  paintTray();

  for (let index = start; index < chunks; index++) {
    if (entry.cancelled) { state.uploads.delete(id); paintTray(); return; }
    // Pause means "stop sending chunks" — the bytes already accepted stay,
    // and resume asks the server where to continue (§7.7).
    while (entry.paused) await new Promise((resolve) => setTimeout(resolve, 200));
    const blob = file.slice(index * CHUNK, Math.min(file.size, (index + 1) * CHUNK));
    const body = new FormData();
    body.append('content', blob, file.name);
    const query = `id=${encodeURIComponent(id)}&name=${encodeURIComponent(file.name)}` +
                  `&index=${index}&last=${index === chunks - 1}`;
    const response = await api(`/upload?${query}`, { method: 'POST', body });
    const result = await response.json();
    // The server reports the ACTUAL bytes written, never a guess (§7.2, A14).
    entry.sent = result.bytes;
    paintTray();
  }
  state.uploads.delete(id);
  paintTray();
}

/**
 * INV-6 / §7.7: EXACTLY ONE upload progress UI — the transfer tray, listing
 * every in-flight upload and every incoming push offer, each with its own
 * controls. There is no second progress bar anywhere in the site.
 */
function paintTray() {
  const tray = $('tray');
  const uploads = [...state.uploads.values()];
  const offers = state.offers || [];
  tray.hidden = uploads.length === 0 && offers.length === 0;
  $('tray-items').innerHTML =
    offers.map((offer) => `
      <div class="tray-item">
        <div>⇩ ${offer.name}</div>
        <div class="mono">${fmtSize(offer.size)} · from the phone</div>
        <div>
          <button class="row-btn" data-offer-accept="${offer.id}">Download</button>
          <button class="row-btn" data-offer-dismiss="${offer.id}">Dismiss</button>
        </div>
      </div>`).join('') +
    uploads.map((item) => `
      <div class="tray-item">
        <div>${item.name}</div>
        <div class="bar"><i style="width:${Math.min(100, (item.sent / item.total) * 100)}%"></i></div>
        <div class="mono">${fmtSize(item.sent)} / ${fmtSize(item.total)}</div>
        <div>
          <button class="row-btn" data-upload-pause="${item.id}">
            ${item.paused ? 'Resume' : 'Pause'}</button>
          <button class="row-btn" data-upload-cancel="${item.id}">Cancel</button>
        </div>
      </div>`).join('');

  for (const button of $('tray-items').querySelectorAll('[data-upload-pause]')) {
    button.onclick = () => {
      const item = state.uploads.get(button.dataset.uploadPause);
      if (item) { item.paused = !item.paused; paintTray(); }
    };
  }
  for (const button of $('tray-items').querySelectorAll('[data-upload-cancel]')) {
    button.onclick = () => {
      const item = state.uploads.get(button.dataset.uploadCancel);
      if (item) item.cancelled = true;
    };
  }
  for (const button of $('tray-items').querySelectorAll('[data-offer-accept]')) {
    button.onclick = () => acceptOffer(button.dataset.offerAccept);
  }
  for (const button of $('tray-items').querySelectorAll('[data-offer-dismiss]')) {
    button.onclick = () => dismissOffer(button.dataset.offerDismiss);
  }
}

// ---------------------------------------------------------------------------
// §7.8 push (phone → browser): an SSE offer becomes a card in the same tray
// ---------------------------------------------------------------------------

function listenForOffers() {
  if (!window.EventSource) return;
  const source = new EventSource(`/api/events?token=${state.token}`);
  source.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      state.offers = data.offers || [];
      paintTray();
    } catch (error) { /* a malformed frame must not break the page */ }
  };
  source.onerror = () => { source.close(); setTimeout(listenForOffers, 3000); };
}

async function acceptOffer(id) {
  const response = await api(`/api/push-accept?offer=${encodeURIComponent(id)}`);
  const data = await response.json();
  // The same Range-capable endpoint every other download uses (§7.8).
  window.location = `${data.url}&token=${state.token}`;
  state.offers = (state.offers || []).filter((offer) => offer.id !== id);
  paintTray();
}

async function dismissOffer(id) {
  await api(`/api/push-dismiss?offer=${encodeURIComponent(id)}`);
  state.offers = (state.offers || []).filter((offer) => offer.id !== id);
  paintTray();
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const thumbUrl = (item) => `/thumbnail?path=${encodeURIComponent(keyOf(item))}&token=${state.token}`;

function fmtSize(bytes) {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
}

function fmtDuration(ms) { return fmtClock((ms || 0) / 1000); }

function fmtClock(seconds) {
  if (!seconds || isNaN(seconds)) return '0:00';
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`;
}

/** §7.10: group on the real date, which the server already resolved. */
function groupByDay(items) {
  const groups = [];
  let current = null;
  for (const item of [...items].sort((a, b) => b.date - a.date)) {
    const day = new Date(item.date).toDateString();
    if (!current || current.day !== day) {
      current = { day, title: dayTitle(item.date), items: [] };
      groups.push(current);
    }
    current.items.push(item);
  }
  return groups;
}

function dayTitle(millis) {
  const date = new Date(millis);
  const today = new Date();
  const yesterday = new Date(today.getTime() - 86400000);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

for (const item of document.querySelectorAll('.rail-item')) {
  item.onclick = () => go(item.dataset.page);
}
$('send-files').onclick = () => $('file-input').click();
$('file-input').onchange = (event) => uploadFiles([...event.target.files]);
$('end-session').onclick = () => { state.token = null; showGate('Session ended', 'Reload to ask again.'); };
$('lightbox-close').onclick = closeLightbox;
$('lightbox-prev').onclick = () => stepLightbox(-1);
$('lightbox-next').onclick = () => stepLightbox(1);
$('lightbox-download').onclick = () => {
  const item = state.lightboxItems[state.lightboxIndex];
  window.location = `/download?path=${encodeURIComponent(keyOf(item))}&token=${state.token}`;
};
$('lightbox-select').onclick = () => {
  toggle(state.lightboxItems[state.lightboxIndex]);
  paintLightbox();
};
$('lightbox').onclick = (event) => { if (event.target.id === 'lightbox') closeLightbox(); };
document.addEventListener('keydown', (event) => {
  if ($('lightbox').hidden) return;
  if (event.key === 'Escape') closeLightbox();
  if (event.key === 'ArrowLeft') stepLightbox(-1);
  if (event.key === 'ArrowRight') stepLightbox(1);
});

$('player-close').onclick = closePlayer;
$('player-play').onclick = () => {
  if (!state.audio) return;
  if (state.audio.paused) { state.audio.play(); $('player-play').textContent = '❚❚'; }
  else { state.audio.pause(); $('player-play').textContent = '▶'; }
};
$('player-prev').onclick = () => nextTrack(-1);
$('player-next').onclick = () => nextTrack(1);
$('player-volume').oninput = (event) => { if (state.audio) state.audio.volume = event.target.value / 100; };
// §6.11's seek contract, browser side: the clock yields while dragging and the
// commit sets currentTime, which issues a fresh Range request (A32).
$('player-range').oninput = () => { state.scrubbing = true; };
$('player-range').onchange = (event) => {
  state.scrubbing = false;
  if (state.audio && state.audio.duration) {
    state.audio.currentTime = (event.target.value / 1000) * state.audio.duration;
  }
};
$('tray-toggle').onclick = () => {
  const items = $('tray-items');
  items.hidden = !items.hidden;
};

handshake();
