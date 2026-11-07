package p25monitor.lan;

/** The browser page. One file, no extra downloads. The scanner data is fetched after the password. */
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
  body { margin: 0; background: #0A0E13; color: #DCE6F0; font: 16px/1.4 Segoe UI, sans-serif; }
  main { max-width: 720px; margin: 0 auto; padding: 20px 16px 40px; }
  h1 { font-size: 15px; letter-spacing: .14em; font-weight: 700; margin: 0 0 14px; color: #7D8FA3; }
  h1 b { color: #DCE6F0; font-weight: 700; }
  h1 b span { color: #00E5FF; }
  .note { color: #7D8FA3; font-size: 14px; margin: 0 0 16px; }
  .card { background: #111821; border: 1px solid #243244; border-radius: 10px; padding: 16px; margin: 0 0 14px; }
  label { display: block; color: #7D8FA3; font-size: 13px; margin-bottom: 6px; }
  input[type=password] { width: 100%; font: 16px Segoe UI, sans-serif; padding: 10px 12px; border-radius: 8px;
    border: 1px solid #243244; background: #0A0E13; color: #DCE6F0; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
  button { font: 600 15px Segoe UI, sans-serif; border: 0; border-radius: 8px; padding: 10px 16px; cursor: pointer; color: #0A0E13; background: #00E5FF; }
  button.stop { background: #FF4D5E; color: #fff; }
  button.ghost { background: #18212D; color: #DCE6F0; }
  button:disabled { opacity: .45; cursor: default; }
  .err { color: #FF4D5E; min-height: 1.2em; margin: 8px 0 0; font-size: 14px; }
  .activity { color: #7D8FA3; font-size: 13px; letter-spacing: .08em; text-transform: uppercase; }
  .name { font-size: 32px; font-weight: 700; margin: 4px 0; word-wrap: break-word; }
  .radio { color: #FFB300; font-size: 18px; min-height: 1.3em; }
  .badges { margin-top: 8px; display: flex; gap: 8px; }
  .badge { font-size: 12px; font-weight: 700; letter-spacing: .06em; padding: 2px 8px; border-radius: 99px; background: #18212D; }
  .badge.on { color: #2BFF88; }
  .badge.warn { color: #FFB300; }
  .badge.hot { color: #FF4D5E; }
  ul { list-style: none; margin: 0; padding: 0; }
  li { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid #243244; }
  li:first-child { border-top: 0; }
  .tg { flex: 1; min-width: 0; }
  .tg .n { font-weight: 650; }
  .tg .id { color: #7D8FA3; font-size: 13px; }
  li.fav .n { color: #FFB300; }
  li.muted .n { color: #7D8FA3; }
  .when { color: #7D8FA3; font-variant-numeric: tabular-nums; width: 5.2em; }
  h2 { font-size: 13px; letter-spacing: .08em; text-transform: uppercase; color: #7D8FA3; margin: 0 0 8px; }
</style>
</head>
<body>
<main>
  <h1><b>TRUNK<span>DECK</span></b> &nbsp;HOME NETWORK</h1>
  <p class="note">This page only works for computers on your home network. It is not reachable from the internet.</p>

  <section id="login" class="card">
    <label for="pw">Password</label>
    <input id="pw" type="password" autocomplete="current-password" placeholder="The password you chose in TrunkDeck">
    <div class="row"><button id="go" type="button">Sign in</button></div>
    <p id="loginErr" class="err"></p>
  </section>

  <section id="app" hidden>
    <div class="card">
      <div id="activity" class="activity">Stopped</div>
      <div id="name" class="name">Stopped</div>
      <div id="radio" class="radio"></div>
      <div class="badges">
        <span id="mon" class="badge">Stopped</span>
        <span id="enc" class="badge warn" hidden>Encrypted</span>
        <span id="emg" class="badge hot" hidden>Emergency</span>
      </div>
      <div class="row">
        <button id="listen" type="button">Listen</button>
        <button id="start" type="button">Start</button>
        <button id="stop" class="stop" type="button">Stop</button>
      </div>
      <p class="note" style="margin:12px 0 0">Press Listen to hear the call TrunkDeck is playing. The browser will not start the sound on its own. Muting the speaker on the TrunkDeck computer does not silence this page.</p>
      <p id="err" class="err"></p>
    </div>
    <div class="card">
      <h2>Talkgroups</h2>
      <ul id="groups"></ul>
    </div>
    <div class="card">
      <h2>Recent calls</h2>
      <ul id="recent"></ul>
    </div>
  </section>
</main>
<script>
const $ = (id) => document.getElementById(id);
let timer = 0, listening = false, ctx = null, abort = null, nextAt = 0, spare = new Uint8Array(0);

function err(t) { $('err').textContent = t || ''; }
function loginErr(t) { $('loginErr').textContent = t || ''; }

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

function activityText(s) {
  if (!s.monitoring) return 'Stopped';
  if (s.activity === 'call') return 'On a call';
  return 'Scanning';
}

function paint(s) {
  const act = activityText(s);
  $('activity').textContent = act;
  $('name').textContent = s.activity === 'call' && s.talkgroup ? s.talkgroup : act;
  $('radio').textContent = s.activity === 'call' && s.radio ? s.radio : '';
  $('mon').textContent = s.monitoring ? 'Monitoring' : 'Stopped';
  $('mon').className = 'badge' + (s.monitoring ? ' on' : '');
  $('enc').hidden = !(s.activity === 'call' && s.encrypted);
  $('emg').hidden = !(s.activity === 'call' && s.emergency);
  $('start').disabled = !!s.monitoring;
  $('stop').disabled = !s.monitoring;
  const groups = $('groups');
  groups.replaceChildren();
  (s.talkgroups || []).forEach((g) => {
    const li = document.createElement('li');
    if (g.favorite) li.classList.add('fav');
    if (g.muted) li.classList.add('muted');
    const tg = document.createElement('div');
    tg.className = 'tg';
    const n = document.createElement('div');
    n.className = 'n';
    n.textContent = (g.favorite ? '★ ' : '') + (g.name || ('TG ' + g.id));
    const id = document.createElement('div');
    id.className = 'id';
    id.textContent = 'TG ' + g.id + (g.muted ? '  muted' : '');
    tg.append(n, id);
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'ghost';
    b.textContent = g.muted ? 'Unmute' : 'Mute';
    b.addEventListener('click', () => mute(g.id, !g.muted));
    li.append(tg, b);
    groups.append(li);
  });
  if (!groups.childElementCount) {
    const li = document.createElement('li');
    li.textContent = 'No talkgroups yet.';
    groups.append(li);
  }
  const recent = $('recent');
  recent.replaceChildren();
  (s.recent || []).forEach((c) => {
    const li = document.createElement('li');
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
    recent.append(li);
  }
}

async function refresh() {
  try { paint(await api('/api/state')); err(''); }
  catch (e) { if (e.message !== 'sign-in') err(e.message); }
}

async function signIn() {
  loginErr('');
  const password = $('pw').value;
  $('pw').value = '';
  if (!password) { loginErr('Type the password.'); return; }
  try {
    const r = await fetch('/api/login', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password })
    });
    if (r.status === 403) { loginErr('This page is only for your home network.'); return; }
    if (!r.ok) { loginErr('That password is not right.'); return; }
    $('login').hidden = true;
    $('app').hidden = false;
    await refresh();
    if (!timer) timer = setInterval(refresh, 1000);
  } catch (e) { loginErr('Cannot reach TrunkDeck.'); }
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
  } catch (e) {
    if (listening) err('The audio stopped.');
  }
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

fetch('/api/state', { credentials: 'same-origin' }).then((r) => {
  if (r.ok) { $('login').hidden = true; $('app').hidden = false; refresh(); timer = setInterval(refresh, 1000); }
}).catch(() => {});
</script>
</body>
</html>
""";
}
