package p25monitor.lan;

/** The browser Monitor: the same glass, active calls, and talkgroup controls as the desktop Monitor page. */
final class LanPage {
    private LanPage() {}

    static final String HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>TrunkDeck</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; background: #0A0E13; color: #DCE6F0;
    font: 14px/1.35 "Segoe UI", sans-serif; }
  button, select, input { font: inherit; }
  button { cursor: pointer; border: 1px solid #243244; background: #18212D; color: #DCE6F0; border-radius: 8px; padding: 7px 12px; }
  button:disabled { opacity: .4; cursor: default; }
  button.go { background: #12382a; color: #B9FFD8; border-color: #1d5c40; font-weight: 700; }
  button.stop { background: #3a1820; color: #FFB3BA; border-color: #6e2a34; font-weight: 700; }
  button.listen { background: #123038; color: #00E5FF; border-color: #1c5560; font-weight: 700; }
  .top { display: flex; align-items: center; gap: 14px; padding: 12px 16px; }
  .brand { font-weight: 800; letter-spacing: .04em; font-size: 17px; }
  .brand span { color: #00E5FF; }
  .brand small { display: block; color: #7D8FA3; font-weight: 600; letter-spacing: .12em; font-size: 10px; }
  .top .grow { flex: 1; }
  .pill { font: 700 12px/1 Consolas, "Cascadia Mono", monospace; color: #7D8FA3; }
  .pill.live { color: #2BFF88; }
  .pill.wait { color: #FFB300; }
  .note { color: #7D8FA3; font-size: 12px; margin: 0 16px 8px; }
  .err { color: #FF4D5E; min-height: 1.1em; margin: 0 16px 8px; font-size: 13px; }
  main { padding: 0 16px 28px; }
  .bezel { background: linear-gradient(#1A2430, #0B1118); border: 1px solid #243244; border-radius: 22px; padding: 14px; }
  .glass { position: relative; overflow: hidden; min-height: 230px; border-radius: 16px; border: 1px solid rgba(0,229,255,.35);
    background: var(--dark, #020608); color: var(--tg, #00E5FF); padding: 18px 18px 14px;
    font-family: Consolas, "Cascadia Mono", ui-monospace, monospace; }
  .glass.call { background: var(--lit, #0A3440); border-color: var(--tg, #00E5FF); }
  .glass::after { content: ""; position: absolute; inset: 0; pointer-events: none; border-radius: 16px;
    background: repeating-linear-gradient(to bottom, rgba(255,255,255,.04) 0 1px, transparent 1px 4px); }
  .grow-top { display: flex; justify-content: space-between; gap: 12px; align-items: flex-end; position: relative; z-index: 1; }
  .sys { color: var(--system, #7D8FA3); font-weight: 700; font-size: 12px; letter-spacing: .04em; }
  .cc { color: var(--system, #7D8FA3); font-weight: 700; font-size: 12px; white-space: nowrap; }
  .sig { display: inline-flex; align-items: flex-end; gap: 6px; margin-left: 12px; }
  .sig i { width: 5px; height: 11px; background: rgba(220,230,240,.28); display: inline-block; }
  .sig i.on { background: var(--scan, #00E5FF); }
  .sig b { color: var(--freq, #DCE6F0); font-size: 12px; font-weight: 700; }
  .big { position: relative; z-index: 1; font-weight: 800; font-size: clamp(28px, 4.6vw, 56px); letter-spacing: .02em;
    margin: 22px 0 6px; min-height: 1.15em; white-space: nowrap; overflow: hidden; }
  .big.scan { color: var(--scan, #00E5FF); }
  .big.idle { color: var(--idle, #2F5A66); }
  .big .run { display: inline-block; padding-left: 8px; animation: tick 7s linear infinite; }
  @keyframes tick { from { transform: translateX(8%); } to { transform: translateX(-55%); } }
  .line2 { position: relative; z-index: 1; min-height: 1.4em; font-weight: 700; font-size: 17px; color: var(--tgid, #FFB300); }
  .line2.dim { color: var(--label, #7D8FA3); }
  .badges { position: relative; z-index: 1; display: flex; gap: 8px; min-height: 8px; margin: 6px 0 14px; }
  .badge { font-size: 11px; font-weight: 700; letter-spacing: .04em; border-radius: 9px; padding: 1px 7px; border: 1px solid; }
  .badge.hot { color: #FF4D5E; border-color: #FF4D5E; }
  .badge.enc { color: #B388FF; border-color: #B388FF; }
  .badge.fav { color: #FFB300; border-color: #FFB300; }
  .badge.mute { color: #7D8FA3; border-color: #7D8FA3; }
  .fields { position: relative; z-index: 1; display: grid; grid-template-columns: 1.3fr 1.6fr .8fr 1.2fr; gap: 8px; }
  .fields label { display: block; color: var(--label, #7D8FA3); font-size: 10px; letter-spacing: .06em; }
  .fields b { display: block; font-size: 15px; font-weight: 700; color: var(--value, #8FA3B5); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .fields b.on { color: var(--freq, #DCE6F0); }
  .fields b.unit.on { color: var(--radio, #DCE6F0); }
  .cols { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 12px; margin-top: 12px; }
  .card { background: #111821; border: 1px solid #243244; border-radius: 12px; padding: 12px 14px; margin: 0 0 12px; }
  .card h2 { margin: 0 0 8px; color: #7D8FA3; font: 700 11px/1.2 Consolas, monospace; letter-spacing: .08em; }
  table { width: 100%; border-collapse: collapse; }
  th { text-align: left; color: #7D8FA3; font: 700 11px/1 Consolas, monospace; padding: 4px 8px; }
  td { padding: 5px 8px; border-top: 1px solid #1c2836; vertical-align: middle; }
  tr.aud { background: #0F2A33; }
  tr.pick { outline: 1px solid #00E5FF; }
  td.nm { font-weight: 700; }
  .speaker-row { display: flex; align-items: center; gap: 8px; }
  .speaker-row input[type=range] { flex: 1; }
  select { background: #0A0E13; color: #DCE6F0; border: 1px solid #243244; border-radius: 8px; padding: 6px 8px; width: 100%; margin-top: 8px; }
  .selname { font-weight: 700; font-size: 15px; color: #00E5FF; }
  .selmeta { color: #FFB300; }
  .rowbtns { display: flex; gap: 8px; margin-top: 8px; }
  ul.tgs, ul.log { list-style: none; margin: 0; padding: 0; max-height: 280px; overflow: auto; }
  ul.tgs li, ul.log li { display: flex; align-items: center; gap: 8px; padding: 7px 2px; border-top: 1px solid #1c2836; }
  ul.tgs li:first-child, ul.log li:first-child { border-top: 0; }
  .tg { flex: 1; min-width: 0; }
  .tg .n { font-weight: 700; }
  .tg .id { color: #7D8FA3; font-size: 12px; }
  li.fav .n { color: #FFB300; }
  li.muted .n { color: #7D8FA3; }
  .when { width: 5.4em; color: #7D8FA3; font-family: Consolas, monospace; }
  #login { max-width: 420px; margin: 40px auto; }
  #login input { width: 100%; margin-top: 6px; padding: 10px 12px; border-radius: 8px; border: 1px solid #243244; background: #0A0E13; color: #DCE6F0; }
  @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } .fields { grid-template-columns: 1fr 1fr; } }
</style>
</head>
<body>
<div class="top">
  <div class="brand">TRUNK<span>DECK</span><small>P25 TRUNKING MONITOR</small></div>
  <div class="grow"></div>
  <button id="listen" class="listen" type="button">Listen</button>
  <button id="start" class="go" type="button">Start</button>
  <button id="stop" class="stop" type="button">Stop</button>
  <span id="pill" class="pill">STOPPED</span>
</div>
<p class="note">Home network only. This is the same Monitor as on the TrunkDeck computer.</p>
<p id="err" class="err"></p>
<main id="app" hidden>
  <div class="bezel" id="bezel">
    <div class="glass" id="glass">
      <div class="grow-top">
        <div id="sys" class="sys">NO SYSTEM</div>
        <div><span id="cc" class="cc"></span><span id="sig" class="sig"></span></div>
      </div>
      <div id="big" class="big idle">STOPPED</div>
      <div id="line2" class="line2"></div>
      <div id="badges" class="badges"></div>
      <div class="fields">
        <div><label id="flab">FREQUENCY</label><b id="freq">-</b></div>
        <div><label>UNIT / RADIO</label><b id="unit" class="unit">-</b></div>
        <div><label id="tlab">CALL TIME</label><b id="ctime">-</b></div>
        <div><label>EVENT</label><b id="event">-</b></div>
      </div>
    </div>
  </div>
  <div class="cols">
    <div>
      <div class="card">
        <h2 id="activeTitle">ACTIVE CALLS</h2>
        <table>
          <thead><tr><th></th><th>Talkgroup</th><th>TG</th><th>Radio</th><th>State</th></tr></thead>
          <tbody id="active"></tbody>
        </table>
      </div>
      <div class="card">
        <h2>LIVE CALL LOG</h2>
        <ul class="log" id="recent"></ul>
      </div>
    </div>
    <div>
      <div class="card">
        <h2>SPEAKER</h2>
        <div class="speaker-row">
          <input id="vol" type="range" min="0" max="100" value="80">
          <span id="volLabel">80 %</span>
          <button id="spkMute" type="button">Mute</button>
        </div>
        <select id="speaker">
          <option value="PRIORITY">One call at a time (priority)</option>
          <option value="MIX">Mix all calls</option>
          <option value="HOLD">Hold one talkgroup</option>
          <option value="QUEUE">Queue all calls (one after another)</option>
        </select>
      </div>
      <div class="card">
        <h2>SELECTED TALKGROUP</h2>
        <div id="selName" class="selname">Click a call or talkgroup</div>
        <div id="selMeta" class="selmeta"></div>
        <div class="rowbtns">
          <button id="favBtn" type="button" disabled>Favorite</button>
          <button id="muteBtn" type="button" disabled>Mute</button>
        </div>
      </div>
      <div class="card">
        <h2>HOT TALKGROUPS</h2>
        <ul class="tgs" id="hot"></ul>
      </div>
      <div class="card">
        <h2>TALKGROUPS</h2>
        <ul class="tgs" id="groups"></ul>
      </div>
    </div>
  </div>
</main>
<section id="login" class="card">
  <h2>PASSWORD</h2>
  <input id="pw" type="password" autocomplete="current-password">
  <div class="rowbtns"><button id="go" class="go" type="button">Sign in</button></div>
  <p id="loginErr" class="err"></p>
</section>
<script>
const $ = (id) => document.getElementById(id);
let timer = 0, listening = false, ctx = null, abort = null, nextAt = 0, spare = new Uint8Array(0);
let state = null, sel = -1, scanTick = 0;

function err(t) { $('err').textContent = t || ''; }

async function api(path, opt) {
  const r = await fetch(path, Object.assign({ credentials: 'same-origin', headers: { 'Content-Type': 'application/json' } }, opt || {}));
  const text = await r.text();
  let body = {};
  try { body = text ? JSON.parse(text) : {}; } catch (e) { body = {}; }
  if (r.status === 401) { showLogin(); throw new Error('sign-in'); }
  if (!r.ok) throw new Error(body.error || 'Something went wrong.');
  return body;
}

function showLogin() {
  $('login').hidden = false;
  $('app').hidden = true;
  if (timer) { clearInterval(timer); timer = 0; }
  stopListen();
}

function cssColors(c) {
  const g = $('bezel');
  const set = (name, value, fallback) => g.style.setProperty(name, value || fallback);
  c = c || {};
  set('--tg', c.talkgroup, '#00E5FF');
  set('--tgid', c.tgid, '#FFB300');
  set('--radio', c.radio, '#DCE6F0');
  set('--freq', c.freq, '#DCE6F0');
  set('--system', c.system, '#7D8FA3');
  set('--scan', c.scan, '#00E5FF');
  set('--dark', c.dark, '#020608');
  set('--lit', c.lit, '#0A3440');
  set('--idle', c.idle, '#2F5A66');
  set('--label', c.label, '#7D8FA3');
}

function bars(n, text, show) {
  const host = $('sig');
  host.replaceChildren();
  if (!show) return;
  for (let i = 0; i < 8; i++) {
    const el = document.createElement('i');
    if (i < (n || 0)) el.className = 'on';
    host.append(el);
  }
  const b = document.createElement('b');
  b.textContent = text || '—';
  host.append(b);
}

function badge(text, cls) {
  const s = document.createElement('span');
  s.className = 'badge ' + cls;
  s.textContent = text;
  return s;
}

function ago(sec) {
  if (sec < 0) return '-';
  if (sec < 60) return sec + 's ago';
  if (sec < 3600) return Math.floor(sec / 60) + 'm ago';
  return Math.floor(sec / 3600) + 'h ago';
}

function clock(ms) {
  const s = Math.max(0, Math.floor((ms || 0) / 1000));
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

function paint(s) {
  state = s;
  cssColors(s.colors);
  const on = !!s.monitoring;
  const call = s.activity === 'call';
  $('glass').className = 'glass' + (call ? ' call' : '');
  const sys = (s.system || 'NO SYSTEM') + (s.site ? '  ·  ' + s.site : '');
  $('sys').textContent = sys.toUpperCase();
  $('cc').textContent = s.control || '';
  const conv = !!s.conventional;
  bars(s.signalBars, s.signalText, on);
  const big = $('big');
  big.className = 'big';
  if (!on) { big.classList.add('idle'); big.textContent = (s.engine || 'STOPPED').toUpperCase(); }
  else if (call) {
    big.style.color = s.emergency ? '#FF4D5E' : s.encrypted ? '#B388FF' : '';
    big.textContent = (s.talkgroup || 'TG ' + s.talkgroupId).toUpperCase();
  } else {
    big.classList.add('scan');
    big.style.color = '';
    big.replaceChildren();
    const run = document.createElement('span');
    run.className = 'run';
    run.textContent = 'SCANNING ...          SCANNING ...          SCANNING ...';
    big.append(run);
  }
  const line = $('line2');
  line.className = 'line2' + (call ? '' : ' dim');
  if (call) line.textContent = (s.talkgroupId > 0 ? 'TG ' + s.talkgroupId : '') + (s.category ? '   ' + s.category : '') + (s.tag ? '  ·  ' + s.tag : '');
  else if (on && s.hang && s.talkgroupId > 0) line.textContent = 'HOLDING TG ' + s.talkgroupId + (s.talkgroup ? '  ' + s.talkgroup.toUpperCase() : '');
  else if (on && s.lastAgo >= 0 && s.talkgroupId > 0) line.textContent = 'LAST  TG ' + s.talkgroupId + (s.talkgroup ? '  ' + s.talkgroup.toUpperCase() : '');
  else line.textContent = '';
  const badges = $('badges');
  badges.replaceChildren();
  if (call && s.emergency) badges.append(badge('EMERGENCY', 'hot'));
  if (call && s.encrypted) badges.append(badge('ENC', 'enc'));
  if (call && s.favorite) badges.append(badge('★ FAV', 'fav'));
  if (call && s.muted) badges.append(badge('MUTED', 'mute'));
  const chans = s.channels || [];
  if (call) {
    $('flab').textContent = 'FREQUENCY';
    $('freq').textContent = s.frequency ? s.frequency + ' MHz' : '-';
    $('tlab').textContent = 'CALL TIME';
    const sec = s.callSeconds < 0 ? 0 : s.callSeconds;
    $('ctime').textContent = Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');
  } else if (on && chans.length) {
    $('flab').textContent = conv ? 'SCAN · CHANNEL' : 'SCAN · CONTROL CH';
    $('freq').textContent = chans[Math.floor(scanTick / 1) % chans.length] + ' MHz';
    $('tlab').textContent = 'LAST CALL';
    $('ctime').textContent = s.lastAgo >= 0 ? ago(s.lastAgo) : '-';
  } else {
    $('flab').textContent = 'FREQUENCY';
    $('freq').textContent = '-';
    $('tlab').textContent = 'LAST CALL';
    $('ctime').textContent = '-';
  }
  $('freq').className = call ? 'on' : '';
  $('unit').textContent = (call || s.hang || s.lastAgo >= 0) && s.radio ? s.radio : '-';
  $('unit').className = 'unit' + (call ? ' on' : '');
  $('event').textContent = (call || s.hang) && s.event ? s.event : '-';
  $('event').className = call ? 'on' : '';
  $('ctime').className = call ? 'on' : '';
  $('pill').textContent = on ? 'LIVE' : 'STOPPED';
  $('pill').className = 'pill' + (on ? ' live' : '');
  $('start').disabled = on;
  $('stop').disabled = !on;
  $('activeTitle').textContent = s.activeTitle || 'ACTIVE CALLS';
  const body = $('active');
  body.replaceChildren();
  (s.active || []).forEach((c) => {
    const tr = document.createElement('tr');
    if (c.audible) tr.classList.add('aud');
    if (c.tg === sel) tr.classList.add('pick');
    tr.addEventListener('click', () => { sel = c.tg; paint(state); });
    const color = c.emergency ? '#FF4D5E' : c.encrypted ? '#B388FF' : c.audible ? '#00E5FF' : (c.state || '').startsWith('WAITING') ? '#FFB300' : '#DCE6F0';
    ['marker', 'name', 'tg', 'radio', 'state'].forEach((k, i) => {
      const td = document.createElement('td');
      td.textContent = k === 'tg' ? String(c.tg) : (c[k] || '');
      if (i === 1) td.className = 'nm';
      td.style.color = i === 0 && c.recording && !c.onSpeaker ? '#FF4D5E' : color;
      tr.append(td);
    });
    body.append(tr);
  });
  if (!body.childElementCount) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 5;
    td.textContent = on ? 'Scanning…' : 'Stopped';
    td.style.color = '#7D8FA3';
    tr.append(td);
    body.append(tr);
  }
  const recent = $('recent');
  recent.replaceChildren();
  (s.recent || []).forEach((c) => {
    const li = document.createElement('li');
    li.addEventListener('click', () => { if (c.id > 0) { sel = c.id; paint(state); } });
    const when = document.createElement('span');
    when.className = 'when';
    when.textContent = c.time || '';
    const tg = document.createElement('div');
    tg.className = 'tg';
    const n = document.createElement('div');
    n.className = 'n';
    n.textContent = c.name || '';
    const id = document.createElement('div');
    id.className = 'id';
    id.textContent = c.radio || '';
    tg.append(n, id);
    li.append(when, tg);
    recent.append(li);
  });
  if (!recent.childElementCount) {
    const li = document.createElement('li');
    li.textContent = 'No calls yet.';
    li.style.color = '#7D8FA3';
    recent.append(li);
  }
  const groups = s.talkgroups || [];
  const picked = groups.find((g) => g.id === sel) || (s.talkgroupId > 0 ? groups.find((g) => g.id === s.talkgroupId) : null);
  if (picked && sel < 0) sel = picked.id;
  $('selName').textContent = picked ? picked.name : 'Click a call or talkgroup';
  $('selMeta').textContent = picked ? ('TG ' + picked.id + (picked.category ? '  ·  ' + picked.category : '') + (picked.muted ? '  ·  muted' : '')) : '';
  $('favBtn').disabled = !picked;
  $('muteBtn').disabled = !picked;
  $('favBtn').textContent = picked && picked.favorite ? 'Unfavorite' : 'Favorite';
  $('muteBtn').textContent = picked && picked.muted ? 'Unmute' : 'Mute';
  fillTgs($('hot'), groups.filter((g) => g.lastHeard > 0).sort((a, b) => b.lastHeard - a.lastHeard).slice(0, 12), true);
  fillTgs($('groups'), groups, false);
  if (document.activeElement !== $('speaker')) $('speaker').value = s.speaker || 'PRIORITY';
  if (document.activeElement !== $('vol')) $('vol').value = String(s.volume == null ? 80 : s.volume);
  $('volLabel').textContent = (s.volume == null ? 80 : s.volume) + ' %';
  $('spkMute').textContent = s.speakerMuted ? 'Unmute' : 'Mute';
  scanTick++;
}

function fillTgs(host, list, hot) {
  host.replaceChildren();
  list.forEach((g) => {
    const li = document.createElement('li');
    if (g.favorite) li.classList.add('fav');
    if (g.muted) li.classList.add('muted');
    if (g.id === sel) li.style.outline = '1px solid #00E5FF';
    li.addEventListener('click', () => { sel = g.id; paint(state); });
    const tg = document.createElement('div');
    tg.className = 'tg';
    const n = document.createElement('div');
    n.className = 'n';
    n.textContent = (g.favorite ? '★ ' : '') + (g.name || ('TG ' + g.id)) + (g.muted ? '  (muted)' : '');
    const id = document.createElement('div');
    id.className = 'id';
    let extra = 'TG ' + g.id;
    if (hot && g.lastHeard) {
      const sec = Math.max(0, Math.floor((Date.now() - g.lastHeard) / 1000));
      extra += '  ·  ' + ago(sec);
    }
    id.textContent = extra;
    tg.append(n, id);
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = g.muted ? 'Unmute' : 'Mute';
    b.addEventListener('click', (e) => { e.stopPropagation(); mute(g.id, !g.muted); });
    li.append(tg, b);
    host.append(li);
  });
  if (!host.childElementCount) {
    const li = document.createElement('li');
    li.textContent = hot ? 'Nothing heard yet.' : 'No talkgroups yet.';
    li.style.color = '#7D8FA3';
    host.append(li);
  }
}

async function refresh() {
  try { paint(await api('/api/state')); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
}

async function signIn() {
  $('loginErr').textContent = '';
  const password = $('pw').value;
  $('pw').value = '';
  if (!password) { $('loginErr').textContent = 'Type the password.'; return; }
  try {
    const r = await fetch('/api/login', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }) });
    if (r.status === 403) { $('loginErr').textContent = 'This page is only for your home network.'; return; }
    if (!r.ok) { $('loginErr').textContent = 'That password is not right.'; return; }
    $('login').hidden = true;
    $('app').hidden = false;
    await refresh();
    if (!timer) timer = setInterval(refresh, 1000);
  } catch (e) { $('loginErr').textContent = 'Cannot reach TrunkDeck.'; }
}

async function mute(id, muted) {
  try { paint(await api('/api/mute', { method: 'POST', body: JSON.stringify({ id, muted }) })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
}

function playPcm(bytes) {
  const merged = new Uint8Array(spare.length + bytes.length);
  merged.set(spare, 0);
  merged.set(bytes, spare.length);
  const n = merged.length - (merged.length % 2);
  const samples = n / 2;
  spare = merged.slice(n);
  if (!samples || !ctx) return;
  const f32 = new Float32Array(samples);
  const view = new DataView(merged.buffer, merged.byteOffset, n);
  for (let i = 0; i < samples; i++) f32[i] = view.getInt16(i * 2, true) / 32768;
  const buf = ctx.createBuffer(1, samples, 8000);
  buf.getChannelData(0).set(f32);
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  const now = ctx.currentTime;
  if (nextAt < now + 0.02) nextAt = now + 0.12;
  if (nextAt - now > 1.2) nextAt = now + 0.12;
  src.start(nextAt);
  nextAt += buf.duration;
}

function stopListen() {
  listening = false;
  if (abort) { try { abort.abort(); } catch (e) {} abort = null; }
  if (ctx) { try { ctx.close(); } catch (e) {} ctx = null; }
  spare = new Uint8Array(0);
  $('listen').textContent = 'Listen';
}

async function listen() {
  if (listening) { stopListen(); return; }
  err('');
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) { err('This browser cannot play the audio.'); return; }
  ctx = new AC();
  const resumed = ctx.resume();
  listening = true;
  $('listen').textContent = 'Stop listening';
  try {
    await resumed;
    abort = new AbortController();
    const res = await fetch('/api/audio', { credentials: 'same-origin', signal: abort.signal });
    if (res.status === 401) { showLogin(); return; }
    if (!res.ok || !res.body) { err('Could not play the audio.'); stopListen(); return; }
    const reader = res.body.getReader();
    while (listening) {
      const step = await reader.read();
      if (step.done) break;
      if (step.value) playPcm(step.value);
    }
  } catch (e) { if (listening) err('The audio stopped.'); }
  if (listening) stopListen();
}

$('go').addEventListener('click', signIn);
$('pw').addEventListener('keydown', (e) => { if (e.key === 'Enter') signIn(); });
$('listen').addEventListener('click', listen);
$('start').addEventListener('click', async () => {
  try { paint(await api('/api/start', { method: 'POST', body: '{}' })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
});
$('stop').addEventListener('click', async () => {
  try { paint(await api('/api/stop', { method: 'POST', body: '{}' })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
});
$('muteBtn').addEventListener('click', () => { if (sel > 0 && state) { const g = (state.talkgroups || []).find((x) => x.id === sel); if (g) mute(sel, !g.muted); } });
$('favBtn').addEventListener('click', async () => {
  if (sel <= 0 || !state) return;
  const g = (state.talkgroups || []).find((x) => x.id === sel);
  if (!g) return;
  try { paint(await api('/api/favorite', { method: 'POST', body: JSON.stringify({ id: sel, favorite: !g.favorite }) })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
});
$('speaker').addEventListener('change', async () => {
  const mode = $('speaker').value;
  const id = sel > 0 ? sel : (state && state.talkgroupId > 0 ? state.talkgroupId : -1);
  try { paint(await api('/api/speaker', { method: 'POST', body: JSON.stringify({ mode, id }) })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); if (state) $('speaker').value = state.speaker || 'PRIORITY'; }
});
$('vol').addEventListener('change', async () => {
  try { paint(await api('/api/volume', { method: 'POST', body: JSON.stringify({ volume: Number($('vol').value) }) })); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
});
$('spkMute').addEventListener('click', async () => {
  const muted = !(state && state.speakerMuted);
  try { paint(await api('/api/speakermute', { method: 'POST', body: JSON.stringify({ muted }) })); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
});
fetch('/api/state', { credentials: 'same-origin' }).then((r) => {
  if (r.ok) { $('login').hidden = true; $('app').hidden = false; refresh(); timer = setInterval(refresh, 1000); }
}).catch(() => {});
</script>
</body>
</html>
""";
}
