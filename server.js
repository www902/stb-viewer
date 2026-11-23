// STB LAN viewer — scrcpy H264 -> fragmented MP4 (MSE), remote control, app launcher
// Zero npm dependencies. Node >= 14.
const http = require('http');
const https = require('https');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { execFile, spawn } = require('child_process');

const ADB = 'C:\\ProgramData\\chocolatey\\bin\\adb.exe';
const DEV = '192.168.1.3:5555';
const SERVER_JAR_LOCAL = 'C:\\ProgramData\\chocolatey\\lib\\scrcpy\\tools\\scrcpy-server';
const SERVER_JAR_DEV = '/dev/scrcpy-server.jar';
const SCRCRY_PORT = 2710;
const PORT = 8000;
const TS = 90000;

function adb(args, bin) {
  return new Promise((res, rej) => {
    execFile(ADB, ['-s', DEV, ...args], { maxBuffer: 64 * 1024 * 1024, encoding: bin ? null : 'utf8' }, (err, so, se) => {
      if (bin) return err ? rej(err) : res(so);
      if (err) return rej(err);
      res(so);
    });
  });
}
function adbShell(cmd) { return adb(['shell', cmd]); }
function adbRaw(args) {
  return new Promise(res => { execFile(ADB, args, { timeout: 8000 }, (e, so, se) => res(String(so || se || ''))); });
}

// ---------------- MP4 muxing ----------------
function box(type, ...parts) {
  const bufs = parts.map(p => Buffer.isBuffer(p) ? p : Buffer.from(p));
  const size = 8 + bufs.reduce((a, b) => a + b.length, 0);
  const h = Buffer.alloc(8);
  h.writeUInt32BE(size, 0); h.write(type, 4, 'ascii');
  return Buffer.concat([h, ...bufs]);
}
const u32 = n => { const b = Buffer.alloc(4); b.writeUInt32BE(n >>> 0, 0); return b; };
const u64 = n => { const b = Buffer.alloc(8); b.writeUInt32BE(Math.floor(n / 0x100000000), 0); b.writeUInt32BE(n >>> 0, 4); return b; };
const u16 = n => { const b = Buffer.alloc(2); b.writeUInt16BE(n & 0xffff, 0); return b; };

function buildFtyp() {
  const b = Buffer.alloc(24);
  b.write('isom', 0); b.writeUInt32BE(0x200, 4); b.write('isomiso6avc1mp41', 8);
  return box('ftyp', b);
}
function buildMoov(sps, pps, W, H) {
  const avcC = box('avcC', Buffer.concat([
    Buffer.from([1, sps[1], sps[2], sps[3], 0xFF, 0xE1]),
    u16(sps.length), sps,
    Buffer.from([1]),
    u16(pps.length), pps,
  ]));
  const compName = Buffer.alloc(32); compName.write('H.264');
  const stsdEntry = Buffer.concat([
    Buffer.alloc(6), u16(1),
    Buffer.alloc(16), // pre_defined(2) + reserved(2) + pre_defined[3x32](12)
    u16(W), u16(H),
    Buffer.from([0x00, 0x48, 0x00, 0x00, 0x00, 0x48, 0x00, 0x00]),
    u32(0), u16(1), compName, u16(0x0018), Buffer.from([0xFF, 0xFE]),
  ]);
  const MATRIX = Buffer.from([0,1,0,0, 0,0,0,0, 0,0,0,0,  0,0,0,0, 0,1,0,0, 0,0,0,0,  0,0,0,0, 0,0,0,0, 0,1,0,0]); // 36-byte identity
  const mvhd = box('mvhd', Buffer.concat([
    Buffer.alloc(4), u32(0), u32(0), u32(TS), u32(0),   // ver/flags, ctime, mtime, timescale, duration
    Buffer.from([0,1,0,0]), Buffer.from([1,0]),          // rate=1.0, volume=1.0
    Buffer.alloc(10),                                    // reserved
    MATRIX,                                              // matrix
    Buffer.alloc(24), u32(2),                            // pre_defined, next_track_ID
  ]));
  const tkhd = box('tkhd', Buffer.concat([
    Buffer.from([0,0,0,3]), u32(0), u32(0), u32(1), u32(0), u32(0),  // ver/flags, ctime, mtime, trackID=1, reserved, duration
    Buffer.alloc(8),                                                   // reserved
    u16(0), u16(0), u16(0x0100), u16(0),                               // layer, altgroup, volume, reserved
    MATRIX, u32(W << 16), u32(H << 16),
  ]));
  const hdlr = box('hdlr', Buffer.concat([Buffer.alloc(8), Buffer.from('vide'), Buffer.alloc(12), Buffer.from('VideoHandle\0')]));
  const mdhd = box('mdhd', Buffer.concat([Buffer.alloc(4), u32(0), u32(0), u32(TS), u32(0), Buffer.from([0x55, 0xC4, 0, 0])]));
  const vmhd = box('vmhd', Buffer.concat([Buffer.from([0, 0, 0, 1]), Buffer.alloc(8)]));
  const dref = box('dref', Buffer.concat([Buffer.alloc(4), u32(1), box('url ', Buffer.from([0, 0, 0, 1]))]));
  const dinf = box('dinf', dref);
  const stbl = box('stbl',
    box('stsd', Buffer.concat([Buffer.alloc(4), u32(1), box('avc1', stsdEntry, avcC)])),
    box('stts', Buffer.alloc(8)),
    box('stsc', Buffer.alloc(8)),
    box('stco', Buffer.alloc(8)),
    box('stsz', Buffer.concat([Buffer.alloc(8), u32(0)])),
  );
  const minf = box('minf', vmhd, dinf, stbl);
  const mdia = box('mdia', mdhd, hdlr, minf);
  const trak = box('trak', tkhd, mdia);
  const trex = box('trex', Buffer.concat([Buffer.alloc(4), u32(1), u32(0), u32(0), u32(0), u32(0)]));
  const mvexMoof = box('moof', box('mfhd', Buffer.concat([Buffer.alloc(4), u32(0)])));
  const mvex = box('mvex', trex, mvexMoof);
  return box('moov', mvhd, trak, mvex);
}
let seq = 1;
function buildSegment(sample, baseTime) {
  // sample: {payload, dur, key}
  const sampleFlags = sample.key ? 0x02000000 : 0x01000000;
  // Build children with known sizes, then set trun dataOffset from actual moof size.
  const tfhd = box('tfhd', Buffer.concat([Buffer.alloc(4), u32(1)])); // ver/flags(4) + trackID(4) = 16
  const tfdt = box('tfdt', Buffer.concat([Buffer.from([1, 0, 0, 0]), u64(baseTime)])); // v1: 8+4+8 = 20
  // trun flags: dataOffset(0x1) + sampleDuration(0x100) + sampleSize(0x200) + sampleFlags(0x400)
  const trunBody = Buffer.concat([
    Buffer.from([0, 0, 0x07, 0x01]), // version=0, flags=0x701
    u32(1),                    // sampleCount
    u32(0),                    // dataOffset placeholder
    u32(sample.dur), u32(sample.payload.length), u32(sampleFlags),
  ]);
  const trun = box('trun', trunBody);
  const traf = box('traf', tfhd, tfdt, trun);
  const mfhd = box('mfhd', Buffer.concat([Buffer.alloc(4), u32(seq++)]));
  const moof = box('moof', mfhd, traf);
  // dataOffset (from start of moof) = moof total size + mdat header (8)
  // trun body layout: ver/flags(4) | sampleCount(4) | dataOffset(4) | dur | size | flags
  // trun body begins at (moof.length - trunBody.length); its dataOffset field is +8 into that body.
  moof.writeUInt32BE(moof.length + 8, (moof.length - trunBody.length) + 8);
  return Buffer.concat([moof, box('mdat', sample.payload)]);
}

// ---------------- H264 -> AVCC samples ----------------
function nalType(b) { return b[0] & 0x1f; }
function toAvcc(nals) {
  const parts = [];
  for (const n of nals) { parts.push(u32(n.length)); parts.push(n); }
  return Buffer.concat(parts);
}

// ---------------- stream supervisor ----------------
const stream = {
  clients: [], raw: [], sps: null, pps: null, initSeg: null, codec: null, ready: false,
  lastFrame: 0, baseTime: 0, started: false, restarting: false, scid: Math.random().toString(16).slice(2, 6),
};

function resetStreamState() {
  stream.sps = stream.pps = stream.initSeg = stream.codec = null;
  stream.ready = false; stream.baseTime = 0; stream.lastFrame = Date.now(); stream._lastPtsUs = 0;
}

function broadcast(buf) {
  for (const c of stream.clients) {
    if (c.writable) {
      if (c._q > 200) { c._q = 0; continue; } // drop on slow client
      c.write(buf);
      c._q++;
    }
  }
}

function handleNals(nals, pts) {
  const samples = [];
  let pending = [];
  for (const n of nals) {
    const t = nalType(n);
    if (t === 7) stream.sps = n;
    else if (t === 8) stream.pps = n;
    else if (t === 5 || t === 1) {
      pending.push(n);
      samples.push({ payload: toAvcc(pending), key: t === 5 });
      pending = [];
    }
    // others (SEI/AUD/SPS/PPS mid-stream) collected into pending handled above
    else if (t === 6 || t === 9) pending.push(n);
  }
  if (pending.length) { /* trailing non-slice, keep for next batch */ }
  if (!stream.ready && stream.sps && stream.pps) {
    const sps = stream.sps, pps = stream.pps;
    stream.sps = sps; stream.pps = pps;
    const hex = [sps[1], sps[2], sps[3]].map(x => x.toString(16).padStart(2, '0')).join('');
    stream.codec = 'avc1.' + hex;
    stream.initSeg = Buffer.concat([buildFtyp(), buildMoov(sps, pps, stream.encW || 1280, stream.encH || 720)]);
    stream.ready = true;
    console.log('[stream] ready codec=' + stream.codec + ' ' + (stream.encW || 1280) + 'x' + (stream.encH || 720));
    for (const c of stream.clients) if (c.writable && !c._init) { c._init = true; c.write(stream.initSeg); }
  }
  if (!stream.ready) return;
  for (const s of samples) {
    let durUs = 33000;
    if (pts) { if (stream._lastPtsUs) durUs = pts - stream._lastPtsUs; stream._lastPtsUs = pts; }
    if (durUs < 5000) durUs = 5000; if (durUs > 300000) durUs = 100000;
    const durTs = Math.round(durUs * TS / 1000000);
    broadcast(buildSegment({ payload: s.payload, dur: durTs, key: s.key }, stream.baseTime));
    stream.baseTime += durTs;
    stream.lastFrame = Date.now();
  }
}

function parseAnnexB(state, chunk) {
  state.buf = Buffer.concat([state.buf, chunk]);
  const out = [];
  const b = state.buf;
  let i = 0, start = 0; // state.buf always begins with a pending NAL body (start code already consumed)
  while (i < b.length - 2) {
    let scLen = 0;
    if (b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 1) scLen = 3;
    else if (i < b.length - 3 && b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 0 && b[i + 3] === 1) scLen = 4;
    if (scLen) {
      if (start >= 0 && i > start) out.push(b.slice(start, i));
      i += scLen; start = i; continue;
    }
    i++;
  }
  if (start >= 0) state.buf = b.slice(start);
  else state.buf = Buffer.alloc(0);
  return out;
}

async function startStreamCycle() {
  if (stream.restarting) return;
  stream.restarting = true;
  try {
    const cs = await adbRaw(['connect', DEV]);
    if (!/connected|already/.test(cs)) console.log('[stream] adb connect: ' + cs.trim());
    // ensure jar present
    let sz = '';
    try { sz = (await adbShell('wc -c < ' + SERVER_JAR_DEV)).toString(); } catch (e) { }
    const localSize = fs.statSync(SERVER_JAR_LOCAL).size;
    if (!sz.includes(String(localSize))) { console.log('[stream] pushing scrcpy-server'); await adb(['push', SERVER_JAR_LOCAL, SERVER_JAR_DEV]); }
    try { await adb(['forward', '--remove-all']); } catch (e) { }
    try { await adbShell('pkill -9 -f com.genymobile.scrcpy'); } catch (e) { }
    await new Promise(r => setTimeout(r, 300));
    try { await adb(['forward', 'tcp:' + SCRCRY_PORT, 'localabstract:scrcpy']); } catch (e) { console.log('[stream] forward fail: ' + e.message); }
    resetStreamState();
    const proc = spawn(ADB, ['-s', DEV, 'shell',
      `CLASSPATH=${SERVER_JAR_DEV} app_process / com.genymobile.scrcpy.Server 4.1 log_level=info audio=false control=false tunnel_forward=true max_size=1280 max_fps=30 video_bit_rate=4000000 video_codec_options=i-frame-interval=1`],
      { stdio: ['ignore', 'ignore', 'pipe'] });
    proc.stderr.on('data', d => { const s = d.toString().trim(); if (s) console.log('[scrcpy-server] ' + s); });
    stream.proc = proc;
    // wait until the device-side server is actually listening on @scrcpy
    let listening = false;
    for (let i = 0; i < 25 && !listening; i++) {
      try { listening = /scrcpy/.test((await adbShell('cat /proc/net/unix')).toString()); } catch (e) { }
      if (!listening) await new Promise(r => setTimeout(r, 400));
    }
    let sock = null, firstChunk = null;
    for (let i = 0; i < 8 && !sock; i++) {
      try {
        const r = await new Promise((res, rej) => {
          const s = net.connect(SCRCRY_PORT, '127.0.0.1');
          let done = false, acc = Buffer.alloc(0);
          const onData = d => {
            if (done) return;
            acc = Buffer.concat([acc, d]);
            if (acc.length >= 69) { done = true; res({ s, d: acc }); } // 1 dummy + 64 name + >=4 more
          };
          const bad = () => { if (!done) { done = true; try { s.destroy(); } catch (e0) { } rej(new Error('no handshake')); } };
          s.on('data', onData);
          s.once('close', bad);
          s.once('error', bad);
          setTimeout(bad, 3000);
        });
        sock = r.s; firstChunk = r.d;
      } catch (e) {
        sock = null;
        await new Promise(r => setTimeout(r, 600));
      }
    }
    if (!sock) {
      try { proc.kill(); } catch (e2) { }
      throw new Error('tunnel never established');
    }
    // header: dummy(1) + name(64) + optional codecID(4: 'h264'/'h265'/'av01'/'opus')
    let skip = 65;
    const tag = firstChunk.slice(65, 69).toString('latin1');
    if (tag === 'h264' || tag === 'h265' || tag === 'av01' || tag === 'opus') skip = 69;
    console.log('[stream] handshake ok, codecID=' + (skip === 69 ? tag : 'absent'));
    const fr = { skip, need: 0, pts: 0, buf: firstChunk, annex: { buf: Buffer.alloc(0) } };
    stream.fr = fr; stream.bytes = (stream.bytes || 0); stream.frames = 0;
    stream.sock = sock; // active socket: only its close/error ends the cycle
    sock.on('data', d => {
      stream.bytes += d.length;
      fr.buf = Buffer.concat([fr.buf, d]);
      try {
        for (;;) {
          if (fr.need === 0) {
            if (fr.skip) { if (fr.buf.length < fr.skip) break; fr.buf = fr.buf.slice(fr.skip); fr.skip = 0; continue; }
            if (fr.buf.length < 12) break;
            const q = fr.buf.readBigUInt64BE(0);
            if ((q >> 63n) & 1n) { // session meta: flags u32 | width u32 | height u32
              stream.encW = fr.buf.readUInt32BE(4); stream.encH = fr.buf.readUInt32BE(8);
              fr.buf = fr.buf.slice(12);
              continue;
            }
            fr.config = ((q >> 62n) & 1n) === 1n;
            fr.key = ((q >> 61n) & 1n) === 1n;
            fr.q = q;
            fr.pts = Number(q & 0x1fffffffffffffffn); // microseconds (bit61 = key frame)
            fr.need = fr.buf.readUInt32BE(8);
            fr.buf = fr.buf.slice(12);
            if (!fr.need) continue;
          }
          if (fr.buf.length < fr.need) break;
          const pay = fr.buf.slice(0, fr.need); fr.buf = fr.buf.slice(fr.need); fr.need = 0;
          if (stream.raw.length) {
            const hdr = Buffer.alloc(12); hdr.writeBigUInt64BE(fr.q); hdr.writeUInt32BE(pay.length, 8);
            for (const c of stream.raw) { if (c.writable) { c.write(hdr); c.write(pay); } }
          }
          const nals = parseAnnexB(fr.annex, pay);
          stream.frames++;
          if (nals.length) handleNals(nals, fr.config ? 0 : fr.pts);
        }
      } catch (e) { console.log('[stream] parse err ' + e.message); }
    });
    sock.on('close', () => { console.log('[stream] socket closed'); cleanupCycle(); });
    sock.on('error', () => cleanupCycle());
    stream.sock = sock;
    stream.restarting = false;
  } catch (e) {
    console.log('[stream] cycle failed: ' + e.message);
    cleanupCycle();
  }
}
function cleanupCycle() {
  try { if (stream.sock) stream.sock.destroy(); } catch (e) { }
  try { if (stream.proc) stream.proc.kill(); } catch (e) { }
  try { if (stream.listener) stream.listener.close(); } catch (e) { }
  adbShell('pkill -f com.genymobile.scrcpy').catch(() => { });
  stream.sock = stream.proc = stream.listener = null;
  stream.restarting = false;
  if (!stream.started) return;
  setTimeout(startStreamCycle, 1500);
}
setInterval(() => {
  if (stream.started && !stream.restarting) {
    if (Date.now() - stream.lastFrame > 10000) {
      console.log('[stream] stalled -> restart');
      if (stream.sock) { try { stream.sock.destroy(); } catch (e) { } }
      else cleanupCycle();
    }
  }
}, 4000);

// ---------------- audio (root REMOTE_SUBMIX loopback) ----------------
const REC_DEX_LOCAL = path.join(__dirname, 'audio', 'out', 'rec.dex');
const REC_DEX_DEV = '/data/local/tmp/rec.dex';
const AUDIO_PORT = 2711;
const audio = { sock: null, proc: null, clients: [], lastData: 0, started: false, restarting: false };

const audioServer = net.createServer(sock => {
  if (audio.sock && audio.sock !== sock) { try { audio.sock.destroy(); } catch (e) { } }
  audio.sock = sock;
  console.log('[audio] Rec connected');
  sock.on('data', d => {
    audio.lastData = Date.now();
    for (const c of audio.clients) {
      if (c.writable) {
        if (c._q > 400) { c._q = 0; continue; } // drop on slow client
        c.write(d); c._q++;
      }
    }
  });
  sock.on('close', () => { console.log('[audio] Rec closed'); if (audio.sock === sock) { audio.sock = null; cleanupAudio(); } });
  sock.on('error', () => { try { sock.destroy(); } catch (e) { } });
});
audioServer.on('error', e => console.log('[audio] sink error: ' + e.message));
audioServer.listen(AUDIO_PORT, '127.0.0.1', () => console.log('[audio] pcm sink on 127.0.0.1:' + AUDIO_PORT));

async function startAudioCycle() {
  if (audio.restarting) return;
  audio.restarting = true;
  try {
    if (!fs.existsSync(REC_DEX_LOCAL)) { console.log('[audio] rec.dex missing, audio off'); return; }
    let sz = '';
    try { sz = (await adbShell('wc -c < ' + REC_DEX_DEV)).toString(); } catch (e) { }
    if (!sz.includes(String(fs.statSync(REC_DEX_LOCAL).size))) { console.log('[audio] pushing rec.dex'); await adb(['push', REC_DEX_LOCAL, REC_DEX_DEV]); }
    await adbRaw(['-s', DEV, 'reverse', 'tcp:' + AUDIO_PORT, 'tcp:' + AUDIO_PORT]);
    try { await adbShell('pkill -f "app_process / Rec"'); } catch (e) { }
    await new Promise(r => setTimeout(r, 400));
    const proc = spawn(ADB, ['-s', DEV, 'shell', 'CLASSPATH=' + REC_DEX_DEV + ' app_process / Rec ' + AUDIO_PORT + ' 8'],
      { stdio: ['ignore', 'pipe', 'pipe'] });
    proc.stdout.on('data', d => { const s = d.toString().trim(); if (s) console.log('[rec] ' + s); });
    proc.stderr.on('data', d => { const s = d.toString().trim(); if (s) console.log('[rec!] ' + s); });
    proc.on('exit', () => { if (audio.proc === proc) { console.log('[audio] Rec proc exit'); audio.proc = null; cleanupAudio(); } });
    audio.proc = proc;
    audio.restarting = false;
  } catch (e) {
    console.log('[audio] cycle failed: ' + e.message);
    audio.restarting = false;
    setTimeout(startAudioCycle, 3000);
  }
}
function cleanupAudio() {
  try { if (audio.proc) audio.proc.kill(); } catch (e) { }
  try { if (audio.sock) audio.sock.destroy(); } catch (e) { }
  audio.proc = null; audio.sock = null; audio.restarting = false;
  if (!audio.started || !audio.clients.length) return;
  setTimeout(startAudioCycle, 2000);
}
setInterval(() => {
  if (audio.started && audio.clients.length && !audio.restarting && Date.now() - audio.lastData > 8000) {
    console.log('[audio] stalled -> restart');
    if (audio.sock) { try { audio.sock.destroy(); } catch (e) { } }
    else cleanupAudio();
  }
}, 5000);

// ---------------- apps list ----------------
let appsCache = { t: 0, list: [] };
async function getApps() {
  if (appsCache.list.length && Date.now() - appsCache.t < 300000) return appsCache.list;
  const out = [];
  const third = (await adbShell('pm list packages -3')).toString();
  const sysRaw = (await adbShell('pm list packages -s')).toString();
  const kw = /launcher|home|tv|video|magi|wiki|cmhi|itv|pplive|cntv|youtube|mobile|cmcc|skworth|skyworth|cetc|aijia/i;
  const pkgs = [];
  for (const line of third.split('\n')) { const m = line.match(/package:(.+)\r?/); if (m) pkgs.push(m[1]); }
  for (const line of sysRaw.split('\n')) {
    const m = line.match(/package:(.+)\r?/);
    if (m && kw.test(m[1])) pkgs.push(m[1]);
  }
  for (const p of pkgs) {
    try {
      const r = (await adbShell(`cmd package resolve-activity --brief ${p}`)).toString().trim().split('\n').pop();
      if (r && r.includes('/') && !r.includes('null')) out.push({ pkg: p, act: r });
    } catch (e) { }
  }
  appsCache = { t: Date.now(), list: out };
  return out;
}

// ---------------- http ----------------
const KEYS = { back: 4, home: 3, up: 19, down: 20, left: 21, right: 22, ok: 23, volup: 24, voldn: 25, menu: 82, power: 26, info: 165, live: 172 };
function readBody(req) {
  return new Promise(res => { let b = ''; req.on('data', d => b += d); req.on('end', () => { try { res(JSON.parse(b || '{}')); } catch (e) { res({}); } }); });
}
const handler = async (req, res) => {
  const u = req.url.split('?')[0];
  try {
    if (u === '/' || u === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(fs.readFileSync(path.join(__dirname, 'public', 'index.html')));
      return;
    }
    if (/^\/[a-z0-9._-]+\.(mp4|jpg|png|css|js)$/.test(u)) {
      const f = path.join(__dirname, 'public', u.slice(1));
      if (fs.existsSync(f)) {
        const mime = { '.mp4': 'video/mp4', '.jpg': 'image/jpeg', '.png': 'image/png', '.css': 'text/css', '.js': 'text/javascript' }[path.extname(f)];
        res.writeHead(200, { 'Content-Type': mime });
        fs.createReadStream(f).pipe(res);
        return;
      }
    }
    if (u === '/info') {
      if (!stream.started) { stream.started = true; startStreamCycle(); }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ready: stream.ready, codec: stream.codec, w: stream.encW || 1280, h: stream.encH || 720 }));
      return;
    }
    if (u === '/video') {
      if (!stream.started) { stream.started = true; startStreamCycle(); }
      res.writeHead(200, { 'Content-Type': 'video/mp4', 'Cache-Control': 'no-cache', 'Connection': 'close' });
      res._q = 0; stream.clients.push(res);
      res.on('close', () => { stream.clients = stream.clients.filter(c => c !== res); });
      if (stream.ready && stream.initSeg) { res._init = true; res.write(stream.initSeg); }
      return;
    }
    if (u === '/h264') {
      if (!stream.started) { stream.started = true; startStreamCycle(); }
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-cache', 'Connection': 'close' });
      if (stream.sps && stream.pps) {
        const payload = Buffer.concat([Buffer.from([0, 0, 0, 1]), stream.sps, Buffer.from([0, 0, 0, 1]), stream.pps]);
        const hdr = Buffer.alloc(12); hdr.writeBigUInt64BE(1n << 62n); hdr.writeUInt32BE(payload.length, 8);
        res.write(hdr); res.write(payload);
      }
      stream.raw.push(res);
      res.on('close', () => { stream.raw = stream.raw.filter(c => c !== res); });
      return;
    }
    if (u === '/audio') {
      if (!audio.started) { audio.started = true; startAudioCycle(); }
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-cache', 'Connection': 'close' });
      res._q = 0; audio.clients.push(res);
      res.on('close', () => { audio.clients = audio.clients.filter(c => c !== res); });
      return;
    }
    if (u === '/shot') {
      const buf = await adb(['exec-out', 'screencap', '-p'], true);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
      res.end(buf);
      return;
    }
    if (u === '/apps') {
      const list = await getApps();
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(list));
      return;
    }
    if (u === '/key' && req.method === 'POST') {
      const b = await readBody(req);
      const code = KEYS[b.key] !== undefined ? KEYS[b.key] : (parseInt(b.code) | 0);
      if (code) { await adbShell('input keyevent ' + code); res.end('ok'); }
      else { res.writeHead(400); res.end('bad key'); }
      return;
    }
    if (u === '/tap' && req.method === 'POST') {
      const b = await readBody(req);
      const x = Math.max(0, Math.min(1920, Math.round(b.x))), y = Math.max(0, Math.min(1080, Math.round(b.y)));
      await adbShell(`input tap ${x} ${y}`);
      res.end('ok'); return;
    }
    if (u === '/swipe' && req.method === 'POST') {
      const b = await readBody(req);
      await adbShell(`input swipe ${b.x1|0} ${b.y1|0} ${b.x2|0} ${b.y2|0} ${b.dur|300}`);
      res.end('ok'); return;
    }
    if (u === '/text' && req.method === 'POST') {
      const b = await readBody(req);
      const s = String(b.text || '').replace(/[^A-Za-z0-9 .:@\/_\-]/g, '').replace(/ /g, '%20');
      if (s) await adbShell('input text ' + JSON.stringify(s));
      res.end('ok'); return;
    }
    if (u === '/launch' && req.method === 'POST') {
      const b = await readBody(req);
      await adbShell(`am start -n ${b.act}`);
      res.end('ok'); return;
    }
    res.writeHead(404); res.end('not found');
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('err: ' + e.message);
  }
};

const server = http.createServer(handler);
server.listen(PORT, () => console.log('STB viewer on http://0.0.0.0:' + PORT));
try {
  const pfx = fs.readFileSync(path.join(__dirname, 'certs', 'stb.pfx'));
  https.createServer({ pfx, passphrase: 'stbpass123' }, handler).listen(8443, () => console.log('STB viewer (secure) on https://0.0.0.0:8443'));
} catch (e) { console.log('[https] disabled: ' + e.message); }
