/* CCTV dashboard. No dependencies, no build step.
 *
 * Security notes: every string that comes from the phone (event types, captions, ids,
 * usernames, URLs) is placed into the page with textContent / setAttribute through the
 * el() helper below. innerHTML is never used. Numbers used in URLs are coerced with
 * Number() and ids with encodeURIComponent(). */
(function () {
  'use strict';

  // ---------------------------------------------------------------- helpers
  var $ = function (id) { return document.getElementById(id); };

  function el(tag, props, kids) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) {
        var v = props[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'text') node.textContent = String(v);
        else if (k === 'class') node.className = v;
        else if (k.slice(0, 2) === 'on' && typeof v === 'function') node.addEventListener(k.slice(2), v);
        else node.setAttribute(k, v === true ? '' : String(v));
      });
    }
    (kids || []).forEach(function (kid) {
      if (kid === null || kid === undefined) return;
      node.appendChild(typeof kid === 'string' ? document.createTextNode(kid) : kid);
    });
    return node;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function pad(n) { return n < 10 ? '0' + n : String(n); }
  function fmtClock(ms) { var d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }
  function fmtDay(ms) { return new Date(ms).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }); }
  function fmtStamp(ms) { return fmtDay(ms) + ' ' + fmtClock(ms); }
  function fmtBytes(n) {
    if (!(n >= 0)) return '--';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(1) + ' GB';
  }
  function fmtDur(ms) {
    if (!(ms >= 0)) return '--';
    var s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
    if (d > 0) return d + 'd ' + h + 'h';
    if (h > 0) return h + 'h ' + m + 'm';
    if (m > 0) return m + 'm ' + (s % 60) + 's';
    return s + 's';
  }
  function fmtAgo(ms) {
    var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 5) return 'just now';
    return fmtDur(s * 1000) + ' ago';
  }
  function num(v) { var n = Math.floor(Number(v)); return isFinite(n) ? n : 0; }

  function store(key, value) {
    try {
      if (value === undefined) return window.localStorage.getItem(key);
      window.localStorage.setItem(key, value);
    } catch (e) { /* storage may be blocked; everything still works */ }
    return null;
  }

  var toastTimer = 0;
  function toast(msg, bad) {
    var t = $('toast');
    t.textContent = msg;
    t.className = 'toast show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = 'toast'; }, 2600);
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; }, function () { return false; });
    }
    var ta = el('textarea', { readonly: true, 'aria-hidden': 'true' });
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return Promise.resolve(ok);
  }

  function fetchTimeout(url, opts, ms) {
    var ctl = typeof AbortController === 'function' ? new AbortController() : null;
    var o = opts || {};
    if (ctl) o.signal = ctl.signal;
    var timer = setTimeout(function () { if (ctl) ctl.abort(); }, ms || 8000);
    return fetch(url, o).then(function (r) { clearTimeout(timer); return r; }, function (e) { clearTimeout(timer); throw e; });
  }

  function getJson(url) {
    return fetchTimeout(url, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  function post(path, params, asBody) {
    var qs = new URLSearchParams(params || {}).toString();
    if (asBody) {
      return fetchTimeout(path, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: qs });
    }
    return fetchTimeout(path + (qs ? '?' + qs : ''), { method: 'POST' });
  }

  // ---------------------------------------------------------------- theme
  function applyTheme(name) {
    document.documentElement.setAttribute('data-theme', name);
    var light = name === 'light';
    $('themeLabel').textContent = light ? 'Dark' : 'Light';
    $('themeBtn').setAttribute('aria-label', light ? 'Switch to dark theme' : 'Switch to light theme');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', light ? '#f3f5f8' : '#0a0c0f');
    drawDensity();
  }
  $('themeBtn').addEventListener('click', function () {
    var next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    store('theme', next);
    applyTheme(next);
  });

  // ---------------------------------------------------------------- state
  var status = null;
  var statusOkAt = 0;
  var statusFails = 0;
  var route = '';

  // ---------------------------------------------------------------- settings controls
  var controls = [];
  var pendingUntil = {};

  function optionList(opts) {
    return opts.map(function (o) { return el('option', { value: o[0], text: o[1] }); });
  }

  function buildRow(def) {
    var id = 'ctl-' + def.key + '-' + controls.length;
    var input;
    if (def.type === 'toggle') {
      input = el('input', { type: 'checkbox', id: id, role: 'switch' });
    } else if (def.type === 'select') {
      input = el('select', { id: id }, optionList(def.opts));
    }
    var text = el('div', { class: 'row-text' }, [
      el('label', { class: 'row-label', for: id, text: def.label }),
      def.help ? el('div', { class: 'row-help', text: def.help }) : null
    ]);
    var control = def.type === 'toggle'
      ? el('span', { class: 'switch' }, [input, el('span', { class: 'track' })])
      : input;
    var reg = { def: def, input: input };
    controls.push(reg);
    input.addEventListener('change', function () { applyControl(reg); });
    return el('div', { class: 'row' }, [text, control]);
  }

  function buildInfoRow(label, id) {
    return el('div', { class: 'row' }, [
      el('div', { class: 'row-text' }, [el('div', { class: 'row-label', text: label }), el('div', { class: 'row-help', id: id, text: '--' })])
    ]);
  }

  function fill(containerId, defs) {
    var host = $(containerId);
    defs.forEach(function (d) { host.appendChild(d.type === 'info' ? buildInfoRow(d.label, d.id) : buildRow(d)); });
  }

  var INTERVALS = [['250', '4 per second'], ['500', '2 per second'], ['1000', 'Every second'], ['2000', 'Every 2 s'], ['5000', 'Every 5 s (recommended)']];

  var ESSENTIAL = [
    { type: 'select', key: 'resolution', status: 'resolution', action: 'resolution', label: 'Resolution',
      help: 'Higher resolution costs bandwidth, heat and battery', opts: [['640x480', '480p'], ['1280x720', '720p (recommended)'], ['1920x1080', '1080p'], ['0x0', 'Maximum']] },
    { type: 'select', key: 'video_fps', status: 'videoFps', label: 'Frame rate',
      help: 'Lower rates cut bitrate and heat', opts: [['10', '10 fps'], ['15', '15 fps'], ['20', '20 fps'], ['24', '24 fps'], ['30', '30 fps (recommended)']] },
    { type: 'select', key: 'active_snapshot_interval_ms', status: 'activeSnapshotIntervalMs', label: 'Snapshot interval',
      help: 'How often a still is saved to the phone buffer and checked for motion', opts: INTERVALS },
    { type: 'toggle', key: 'audio_enabled', status: 'audioEnabled', label: 'Audio', help: 'Adds the microphone to the RTSP stream (needs mic permission on the phone)' },
    { type: 'toggle', key: 'flashlight_enabled', status: 'flashlightEnabled', label: 'Torch', help: 'Turns the camera light on' },
    { type: 'toggle', key: 'motion_detection_enabled', status: 'motionDetectionEnabled', label: 'Motion detection', help: 'Creates an event when the scene changes' }
  ];

  var QUICK = [ESSENTIAL[4], ESSENTIAL[5], ESSENTIAL[3]];

  var VIDEO = [
    { type: 'select', key: 'codec', status: 'codec', action: 'codec', label: 'Codec', opts: [['H264', 'H.264'], ['H265', 'H.265'], ['AV1', 'AV1']] },
    { type: 'info', label: 'This device can encode', id: 'codecSupport' },
    { type: 'select', key: 'camera_rotation', status: 'cameraRotation', label: 'Rotation', opts: [['0', '0°'], ['90', '90°'], ['180', '180°'], ['270', '270°']] },
    { type: 'select', key: 'encoder_implementation', status: 'encoderImplementation', label: 'Encoder', opts: [['auto', 'Auto'], ['hardware', 'Hardware'], ['software', 'Software'], ['cbr', 'Constant bitrate']] },
    { type: 'select', key: 'bitrate_kbps', status: 'bitrateKbps', label: 'Bitrate',
      opts: [['0', 'Auto'], ['1000', '1 Mbit/s'], ['2000', '2 Mbit/s'], ['4000', '4 Mbit/s'], ['6000', '6 Mbit/s'], ['8000', '8 Mbit/s'], ['12000', '12 Mbit/s'], ['20000', '20 Mbit/s']] },
    { type: 'select', key: 'keyframe_interval_seconds', status: 'keyframeIntervalSeconds', label: 'Keyframe interval',
      help: 'Shorter starts players faster at a bandwidth cost', opts: [['1', '1 s'], ['2', '2 s'], ['3', '3 s'], ['5', '5 s'], ['10', '10 s']] },
    { type: 'toggle', key: 'adaptive_quality_enabled', status: 'adaptiveQualityEnabled', label: 'Adapt to heat and battery', help: 'Lower the bitrate when the phone overheats or runs low' },
    { type: 'toggle', key: 'show_preview', status: 'showPreview', label: 'Show preview on the phone screen' }
  ];

  var CAPTURE = [
    { type: 'toggle', key: 'detection_enabled', status: 'detectionEnabled', label: 'Detection', help: 'Master switch for events' },
    { type: 'toggle', key: 'object_detection_enabled', status: 'objectDetectionEnabled', label: 'People and animals', help: 'Uses the on-device model' },
    { type: 'info', label: 'Detection model', id: 'detectorStatus' },
    { type: 'toggle', key: 'night_mode_enabled', status: 'nightModeEnabled', label: 'Night mode', help: 'Turns the torch on automatically when dark' },
    { type: 'select', key: 'idle_snapshot_interval_ms', status: 'idleSnapshotIntervalMs', label: 'Idle heartbeat', help: 'Capture rate when nothing needs a frame',
      opts: [['1000', 'Every 1 s'], ['3000', 'Every 3 s'], ['10000', 'Every 10 s'], ['30000', 'Every 30 s']] },
    { type: 'select', key: 'analysis_width', status: 'analysisWidth', label: 'Analysis width', help: 'Frames are shrunk to this before detection',
      opts: [['320', '320 px'], ['480', '480 px'], ['640', '640 px'], ['1280', '1280 px']] },
    { type: 'select', key: 'snapshot_jpeg_quality', status: 'snapshotJpegQuality', label: 'Snapshot quality', help: 'JPEG quality for the buffer, preview and events',
      opts: [['30', '30 (smallest)'], ['50', '50'], ['70', '70'], ['90', '90 (best)']] }
  ];

  var OVERLAY = [
    { type: 'toggle', key: 'show_timestamp', status: 'showTimestamp', label: 'Show time' },
    { type: 'toggle', key: 'show_date', status: 'showDate', label: 'Show date' },
    { type: 'select', key: 'timestamp_position', status: 'timestampPosition', label: 'Position', opts: [['Top Left', 'Top left'], ['Top Right', 'Top right'], ['Bottom Left', 'Bottom left'], ['Bottom Right', 'Bottom right']] },
    { type: 'select', key: 'timestamp_size', status: 'timestampSize', label: 'Text size', opts: [['Small', 'Small'], ['Medium', 'Medium'], ['Large', 'Large']] }
  ];

  var WEBAUTH = [
    { type: 'toggle', key: 'web_auth_enabled', status: 'webAuthEnabled', label: 'Require sign-in for this dashboard', help: 'Uses the credentials below on port 8080' }
  ];

  fill('essentialRows', ESSENTIAL);
  fill('quickRows', QUICK);
  fill('videoRows', VIDEO);
  fill('captureRows', CAPTURE);
  fill('overlayRows', OVERLAY);
  fill('webAuthRows', WEBAUTH);

  function readControl(reg) {
    return reg.def.type === 'toggle' ? String(reg.input.checked) : reg.input.value;
  }

  function sendSetting(def, value) {
    if (def.action === 'resolution') {
      var wh = value.split('x');
      return post('/action/set-resolution', { w: wh[0], h: wh[1] });
    }
    if (def.action === 'codec') return post('/action/set-codec', { codec: value });
    return post('/action/set-setting', { key: def.key, value: value });
  }

  function applyControl(reg) {
    var value = readControl(reg);
    pendingUntil[reg.def.key] = Date.now() + 2500;
    syncSiblings(reg, value);
    sendSetting(reg.def, value).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      toast(reg.def.label + ' updated');
    }).catch(function () {
      toast('Could not change ' + reg.def.label.toLowerCase(), true);
      delete pendingUntil[reg.def.key];
    }).then(function () { setTimeout(pollStatus, 350); });
  }

  // The same setting can appear twice (Quick controls and Essentials); keep both in step.
  function syncSiblings(src, value) {
    controls.forEach(function (c) {
      if (c !== src && c.def.key === src.def.key) setControlValue(c, value);
    });
  }

  function setControlValue(reg, value) {
    if (reg.def.type === 'toggle') { reg.input.checked = (value === true || value === 'true'); return; }
    var v = String(value);
    var has = false;
    for (var i = 0; i < reg.input.options.length; i++) if (reg.input.options[i].value === v) has = true;
    if (!has) reg.input.appendChild(el('option', { value: v, text: v === '0x0' ? 'Maximum' : v }));
    reg.input.value = v;
  }

  function syncControls(s) {
    controls.forEach(function (c) {
      var key = c.def.key;
      if (pendingUntil[key] && pendingUntil[key] > Date.now()) return;
      if (document.activeElement === c.input && c.def.type === 'select') return;
      var v = s[c.def.status];
      if (v === undefined || v === null) return;
      setControlValue(c, v);
    });
  }

  $('resetBtn').addEventListener('click', function () {
    var steps = [
      [ESSENTIAL[0], '1280x720'], [ESSENTIAL[1], '30'], [ESSENTIAL[2], '5000']
    ];
    var chain = Promise.resolve();
    steps.forEach(function (st) { chain = chain.then(function () { return sendSetting(st[0], st[1]); }); });
    chain.then(function () { toast('Recommended settings applied'); }, function () { toast('Could not apply all settings', true); })
      .then(function () { setTimeout(pollStatus, 500); });
  });

  // ---------------------------------------------------------------- status
  var THERMAL = ['Normal', 'Light', 'Moderate', 'Severe', 'Critical', 'Emergency', 'Shutdown'];

  function maskRtsp(url) { return String(url).replace(/(:\/\/[^:\/@]*):[^@]*@/, '$1:••••••@'); }
  var rtspRevealed = false;

  function describeSupport(support) {
    if (!support) return 'Unknown';
    var names = { H264: 'H.264', H265: 'H.265', AV1: 'AV1' };
    return Object.keys(support).map(function (codec) {
      var s = support[codec], how = 'none';
      if (s.hardware && s.software) how = 'hardware + software';
      else if (s.hardware) how = 'hardware';
      else if (s.software) how = 'software';
      return (names[codec] || codec) + ' ' + how;
    }).join(', ');
  }

  function setPill(id, state, text, textId) {
    var p = $(id);
    p.setAttribute('data-state', state);
    $(textId).textContent = text;
  }

  function renderStatus(s) {
    if (s.batteryLevel >= 0) { $('batteryMeter').hidden = false; $('batteryText').textContent = s.batteryLevel + '%'; }
    var on = !!s.streaming;
    setPill('rtspPill', on ? 'live' : 'paused', on ? 'RTSP on' : 'RTSP off', 'rtspPillText');
    var btn = $('streamBtn');
    btn.className = 'btn ' + (on ? 'btn-danger' : 'btn-primary');
    $('streamBtnText').textContent = on ? 'Stop stream' : 'Start stream';
    $('hudRes').textContent = s.resolution === '0x0' ? 'max' : s.resolution;

    var kv = $('liveKv');
    clear(kv);
    [['Codec', s.activeCodec || s.codec], ['Resolution', s.resolution === '0x0' ? 'Maximum' : s.resolution],
     ['Frame rate', s.videoFps + ' fps'],
     ['Bitrate', s.bitrateKbps === 0 ? 'Auto' + (s.activeBitrateKbps ? ' (' + s.activeBitrateKbps + ' kbit/s)' : '') : s.bitrateKbps + ' kbit/s']
    ].forEach(function (p) { kv.appendChild(el('span', { text: p[0] })); kv.appendChild(el('b', { text: String(p[1]) })); });
    if (s.qualityScalePercent < 100) {
      kv.appendChild(el('span', { text: 'Throttled' }));
      kv.appendChild(el('b', { text: String(s.qualityTrigger).toLowerCase() + ', ' + s.qualityScalePercent + '%' }));
    }

    syncControls(s);
    $('codecSupport').textContent = describeSupport(s.codecSupport);
    $('detectorStatus').textContent = s.objectDetectorReady ? 'Loaded' : 'Not installed (assets/detect.tflite is missing)';

    if (!$('authUser').dataset.filled) { $('authUser').value = s.username || ''; $('authUser').dataset.filled = '1'; }
    if (document.activeElement !== $('authEnabled')) $('authEnabled').checked = !!s.authEnabled;

    $('rtspUrl').textContent = rtspRevealed ? s.rtspUrl : maskRtsp(s.rtspUrl);
    $('dashUrl').textContent = location.origin;
  }

  function renderConn() {
    var age = Date.now() - statusOkAt;
    var state, text;
    if (!statusOkAt) { state = 'stale'; text = 'Connecting'; }
    else if (statusFails >= 2 || age > 10000) { state = 'offline'; text = 'Phone unreachable'; }
    else { state = 'live'; text = 'Connected'; }
    $('connPill').setAttribute('data-state', state);
    $('connText').textContent = text;
  }

  function pollStatus() {
    if (document.hidden) return;
    getJson('/status').then(function (s) {
      status = s; statusOkAt = Date.now(); statusFails = 0;
      renderStatus(s);
    }).catch(function () { statusFails++; }).then(renderConn);
  }

  // ---------------------------------------------------------------- live view
  var live = { video: false, videoTimer: 0, videoSeen: 0, paused: false, active: false, running: false, lastOk: 0, frameAge: -1, stamps: [], lat: 0, fails: 0, rate: 250, url: null, blob: null, timer: 0 };

  var storedRate = store('liveRate');
  if (storedRate) { $('rateSel').value = storedRate; live.rate = Number($('rateSel').value) || 250; }
  $('rateSel').addEventListener('change', function () { live.rate = Number(this.value) || 250; store('liveRate', this.value); });

  function liveState() {
    if (live.paused) return 'paused';
    if (!live.lastOk) return live.fails >= 2 ? 'offline' : 'stale';
    var age = Date.now() - live.lastOk;
    var staleAfter = Math.max(3000, live.rate * 2 + 2000);
    if (live.fails >= 3 || age > 10000 + live.rate) return 'offline';
    // The phone says how old its newest capture is; a fresh fetch of an old frame is not live.
    if (live.frameAge > Math.max(15000, live.rate * 3)) return 'stale';
    if (age > staleAfter || live.fails > 0) return 'stale';
    return 'live';
  }

  function renderLive() {
    var st = liveState();
    var labels = { live: 'Live', stale: 'Stale', offline: 'Offline', paused: 'Paused' };
    $('livePill').setAttribute('data-state', st);
    $('livePillText').textContent = labels[st];
    $('stage').classList.toggle('is-stale', st === 'stale' || st === 'offline');
    var n = live.stamps.length, fps = 0;
    if (n > 1) {
      var span = live.stamps[n - 1] - live.stamps[0];
      if (span > 0) fps = (n - 1) * 1000 / span;
    }
    if (st === 'paused') fps = 0;
    $('hudFps').textContent = (fps >= 10 ? fps.toFixed(0) : fps.toFixed(1)) + ' fps';
    $('hudLat').textContent = live.lat ? Math.round(live.lat) + ' ms' : '-- ms';
    var empty = !live.lastOk;
    $('stageEmpty').hidden = !empty;
    if (empty) {
      $('stageEmptyText').textContent = live.fails >= 2
        ? 'No picture from the camera. Check that the app is running on the phone.' : 'Waiting for the first frame…';
    }
    if (live.lastOk) {
      $('stageTime').hidden = false;
      $('stageTime').textContent = live.frameAge >= 0
        ? 'Captured ' + fmtClock(live.lastOk - live.frameAge) + ' · ' + fmtDur(live.frameAge) + ' old'
        : 'Received ' + fmtClock(live.lastOk);
    }
  }

  function liveSchedule(delay) {
    clearTimeout(live.timer);
    live.timer = setTimeout(liveTick, delay);
  }

  function liveTick() {
    if (!live.active || live.paused || document.hidden || live.video) { live.running = false; return; }
    live.running = true;
    var t0 = performance.now();
    fetchTimeout('/shot.jpg?t=' + Date.now(), { cache: 'no-store' }, 8000).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var age = Number(r.headers.get('X-Frame-Age'));
      live.frameAge = r.headers.get('X-Frame-Age') !== null && isFinite(age) ? age : -1;
      return r.blob();
    }).then(function (blob) {
      var t1 = performance.now();
      var prev = live.url;
      live.blob = blob;
      live.url = URL.createObjectURL(blob);
      var img = $('liveImg');
      img.onload = function () {
        if (img.naturalWidth) $('hudRes').textContent = img.naturalWidth + '×' + img.naturalHeight;
        if (prev) URL.revokeObjectURL(prev);
      };
      img.src = live.url;
      live.lat = t1 - t0;
      live.lastOk = Date.now();
      live.fails = 0;
      live.stamps.push(t1);
      while (live.stamps.length > 2 && t1 - live.stamps[0] > 6000) live.stamps.shift();
      renderLive();
      liveSchedule(Math.max(0, live.rate - (performance.now() - t0)));
    }).catch(function () {
      live.fails++;
      live.lat = 0;
      live.stamps = [];
      renderLive();
      liveSchedule(Math.min(5000, 500 * Math.pow(2, Math.min(live.fails, 4))));
    });
  }

  // Live video: the phone serves its H.264 stream as fragmented MP4 at /live.mp4, which a
  // <video> element plays at the camera's real frame rate. If it cannot play (old browser,
  // stream down) the view falls back to polling /shot.jpg and retries the video later.
  var canVideo = !!(window.MediaSource || document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01F"'));

  function videoStop() {
    var v = $('liveVideo');
    clearTimeout(live.videoTimer);
    live.video = false;
    v.pause();
    v.removeAttribute('src');
    v.load();           // drops the HTTP connection so the phone stops sending
    v.hidden = true;
    $('liveImg').hidden = false;
  }

  function videoFallback() {
    if (!live.video && !$('liveVideo').hasAttribute('src')) return;
    videoStop();
    live.stamps = [];
    if (live.active && !live.paused) {
      liveSchedule(0);
      live.videoTimer = setTimeout(function () { if (live.active && !live.paused) videoStart(); }, 20000);
    }
  }

  function videoFrame(now, meta) {
    if (!live.video) return;
    var v = $('liveVideo');
    live.lastOk = Date.now();
    live.fails = 0;
    live.frameAge = 0;
    live.stamps.push(now);
    while (live.stamps.length > 2 && now - live.stamps[0] > 3000) live.stamps.shift();
    if (v.videoWidth) $('hudRes').textContent = v.videoWidth + '×' + v.videoHeight;
    // Stay near the live edge: a browser that buffers ahead would otherwise drift behind.
    if (v.buffered.length) {
      var lag = v.buffered.end(v.buffered.length - 1) - v.currentTime;
      live.lat = Math.max(0, lag * 1000);
      if (lag > 1.5) v.currentTime = v.buffered.end(v.buffered.length - 1) - 0.25;
    }
    v.requestVideoFrameCallback(videoFrame);
  }

  function videoStart() {
    if (!canVideo || live.video || !live.active || live.paused) return;
    var v = $('liveVideo');
    if (!v.requestVideoFrameCallback) { return; }
    clearTimeout(live.videoTimer);
    v.hidden = false;
    $('liveImg').hidden = true;
    v.onplaying = function () {
      live.video = true;
      clearTimeout(live.timer); live.running = false;   // stop snapshot polling
      live.stamps = [];
      v.requestVideoFrameCallback(videoFrame);
    };
    v.onerror = v.onstalled = v.onended = function () { videoFallback(); };
    v.src = '/live.mp4?t=' + Date.now();
    var p = v.play();
    if (p && p.catch) p.catch(function () { videoFallback(); });
    // No picture within a few seconds: use snapshots meanwhile.
    live.videoTimer = setTimeout(function () { if (!live.video) videoFallback(); }, 8000);
  }

  function liveStart() {
    live.active = true;
    if (!live.paused) { videoStart(); if (!live.video && !live.running) liveSchedule(0); }
  }
  function liveStop() { live.active = false; live.running = false; clearTimeout(live.timer); videoStop(); }

  setInterval(function () { if (live.active) renderLive(); renderConn(); }, 500);

  $('pauseBtn').addEventListener('click', function () {
    live.paused = !live.paused;
    $('pauseText').textContent = live.paused ? 'Resume' : 'Pause';
    $('pauseIcon').firstElementChild.setAttribute('d', live.paused ? 'M8 5v14l11-7z' : 'M6 5h4v14H6V5zm8 0h4v14h-4V5z');
    if (live.paused) { live.stamps = []; clearTimeout(live.timer); live.running = false; videoStop(); }
    else { videoStart(); if (!live.video) liveSchedule(0); }
    renderLive();
  });

  $('fsBtn').addEventListener('click', function () {
    var stage = $('stage');
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    } else {
      var req = stage.requestFullscreen || stage.webkitRequestFullscreen;
      if (req) req.call(stage); else toast('Fullscreen is not supported here', true);
    }
  });

  $('saveBtn').addEventListener('click', function () {
    var v = $('liveVideo');
    if (live.video && v.videoWidth) {
      var c = document.createElement('canvas');
      c.width = v.videoWidth; c.height = v.videoHeight;
      c.getContext('2d').drawImage(v, 0, 0);
      c.toBlob(function (b) {
        if (!b) { toast('Could not save frame', true); return; }
        var u = URL.createObjectURL(b);
        var a2 = el('a', { href: u, download: 'cctv-' + Date.now() + '.jpg' });
        document.body.appendChild(a2); a2.click(); document.body.removeChild(a2);
        setTimeout(function () { URL.revokeObjectURL(u); }, 5000);
      }, 'image/jpeg', 0.92);
      return;
    }
    if (!live.blob) { toast('No frame to save yet', true); return; }
    var a = el('a', { href: live.url, download: 'cctv-' + Date.now() + '.jpg' });
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
  });

  $('flipBtn').addEventListener('click', function () {
    post('/action/switch-camera').then(function () { toast('Camera switched'); }, function () { toast('Could not switch camera', true); });
  });

  $('streamBtn').addEventListener('click', function () {
    if (status && status.streaming && !window.confirm('Stop the RTSP stream? NVRs and VLC will lose the picture.')) return;
    post('/action/toggle-stream').then(function (r) { return r.text(); }).then(function (t) {
      toast(t === 'Started' ? 'Stream started' : 'Stream stopped');
    }, function () { toast('Could not toggle stream', true); }).then(function () { setTimeout(pollStatus, 400); });
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { pollStatus(); if (live.active && !live.paused) liveSchedule(0); }
  });

  // ---------------------------------------------------------------- lightbox
  var lb = { items: [], index: 0, opener: null };

  function lbShow() {
    var it = lb.items[lb.index];
    if (!it) return;
    $('lbTitle').textContent = it.title;
    $('lbImg').hidden = false;
    $('lbVideo').hidden = true;
    $('lbVideo').pause();
    $('lbVideo').removeAttribute('src');
    $('lbImg').src = it.src;
    $('lbDownload').href = it.src;
    $('lbDownload').setAttribute('download', it.file);
    var cap = $('lbCap');
    clear(cap);
    if (it.caption) cap.appendChild(el('div', { text: it.caption }));
    if (it.clip) {
      cap.appendChild(el('button', { class: 'btn btn-sm', type: 'button', text: 'Play clip', onclick: function () {
        $('lbImg').hidden = true;
        $('lbVideo').hidden = false;
        $('lbVideo').src = it.clip;
        $('lbVideo').play();
      } }));
    }
    $('lbPrev').hidden = lb.items.length < 2 || lb.index === 0;
    $('lbNext').hidden = lb.items.length < 2 || lb.index === lb.items.length - 1;
  }

  function lbOpen(items, index) {
    lb.items = items; lb.index = index; lb.opener = document.activeElement;
    $('lightbox').hidden = false;
    document.body.style.overflow = 'hidden';
    lbShow();
    $('lbClose').focus();
  }
  function lbClose() {
    $('lightbox').hidden = true;
    $('lbVideo').pause();
    $('lbImg').removeAttribute('src');
    document.body.style.overflow = '';
    if (lb.opener && lb.opener.focus) lb.opener.focus();
  }
  function lbStep(d) {
    var n = lb.index + d;
    if (n < 0 || n >= lb.items.length) return;
    lb.index = n; lbShow();
  }
  $('lbClose').addEventListener('click', lbClose);
  $('lbPrev').addEventListener('click', function () { lbStep(-1); });
  $('lbNext').addEventListener('click', function () { lbStep(1); });
  $('lightbox').addEventListener('click', function (e) { if (e.target === $('lightbox') || e.target.classList.contains('lb-body')) lbClose(); });
  document.addEventListener('keydown', function (e) {
    if ($('lightbox').hidden) return;
    if (e.key === 'Escape') lbClose();
    else if (e.key === 'ArrowLeft') lbStep(-1);
    else if (e.key === 'ArrowRight') lbStep(1);
    else if (e.key === 'Tab') {
      var f = Array.prototype.filter.call($('lightbox').querySelectorAll('button, a[href], video'), function (n) { return !n.hidden && n.offsetParent !== null; });
      if (!f.length) return;
      var first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  });

  // ---------------------------------------------------------------- frames
  var frames = { list: [], loaded: false, shown: 60, tlIndex: 0, tlTs: 0, follow: true, playTimer: 0 };

  function frameUrl(ts, w) { return '/frame?ts=' + num(ts) + (w ? '&w=' + w : ''); }
  function frameItem(f) {
    return { src: frameUrl(f.ts), title: fmtStamp(f.ts), file: 'cctv-' + num(f.ts) + '.jpg', caption: fmtBytes(f.size) + ' · ' + fmtAgo(f.ts) };
  }

  function loadFrames() {
    return getJson('/api/frames?limit=5000').then(function (d) {
      var list = (d && d.frames) || [];
      frames.list = list.map(function (f) { return { ts: num(f.ts), size: num(f.size) }; });
      frames.loaded = true;
      renderFrames();
    }).catch(function () {
      if (!frames.loaded) $('gridNote').textContent = 'Could not load the frame list from the phone.';
    });
  }

  // Timeline index 0 is the oldest frame, so left-to-right on the slider is forwards in time.
  function tlFrame(i) { return frames.list[frames.list.length - 1 - i]; }

  function showTimeline(i) {
    var n = frames.list.length;
    if (!n) return;
    i = Math.max(0, Math.min(n - 1, i));
    frames.tlIndex = i;
    var f = tlFrame(i);
    frames.tlTs = f.ts;
    frames.follow = (i === n - 1);
    $('tlImg').src = frameUrl(f.ts);
    $('tlTime').textContent = fmtStamp(f.ts) + ' · ' + fmtAgo(f.ts);
    $('scrubber').value = String(i);
    $('tlDownload').href = frameUrl(f.ts);
    $('tlDownload').setAttribute('download', 'cctv-' + f.ts + '.jpg');
  }

  function drawDensity() {
    var cv = $('density');
    if (!cv || !frames.list.length) return;
    var w = cv.clientWidth || 600, h = 24, dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(w * dpr); cv.height = h * dpr;
    var ctx = cv.getContext('2d');
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);
    var oldest = tlFrame(0).ts, newest = tlFrame(frames.list.length - 1).ts;
    var span = Math.max(1, newest - oldest);
    var cols = Math.max(1, Math.floor(w / 3)), buckets = new Array(cols), max = 1, i;
    for (i = 0; i < cols; i++) buckets[i] = 0;
    frames.list.forEach(function (f) {
      var b = Math.min(cols - 1, Math.floor((f.ts - oldest) / span * cols));
      buckets[b]++; if (buckets[b] > max) max = buckets[b];
    });
    ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#6aa0ff';
    for (i = 0; i < cols; i++) {
      if (!buckets[i]) continue;
      var bh = Math.max(6, Math.round(h * Math.min(1, buckets[i] / max)));
      ctx.fillRect(i * (w / cols), h - bh, Math.max(1, w / cols - 1), bh);
    }
  }

  function renderFrames() {
    var list = frames.list, n = list.length;
    var sc = $('scrubber');
    $('tlEmpty').hidden = n > 0;
    sc.disabled = n === 0;
    ['tlPrev', 'tlPlay', 'tlNext', 'tlLive'].forEach(function (id) { $(id).disabled = n === 0; });
    if (n) {
      sc.max = String(n - 1);
      $('tlOldest').textContent = fmtStamp(tlFrame(0).ts);
      $('tlNewest').textContent = fmtStamp(tlFrame(n - 1).ts);
      $('tlRange').textContent = n + ' frames · ' + fmtDur(tlFrame(n - 1).ts - tlFrame(0).ts) + ' of footage';
      var idx = n - 1;
      if (!frames.follow && frames.tlTs) {
        for (var k = 0; k < n; k++) if (tlFrame(k).ts === frames.tlTs) { idx = k; break; }
        if (idx === n - 1 && tlFrame(0).ts > frames.tlTs) idx = 0;
      }
      showTimeline(idx);
      drawDensity();
    } else {
      $('tlRange').textContent = 'empty';
      $('tlTime').textContent = '--';
    }
    renderGrid();
  }

  function renderGrid() {
    var g = $('gallery'), list = frames.list;
    clear(g);
    var shown = list.slice(0, frames.shown), lastHour = '';
    shown.forEach(function (f, idx) {
      var d = new Date(f.ts);
      var hourKey = fmtDay(f.ts) + ' ' + pad(d.getHours()) + ':00';
      if (hourKey !== lastHour) { g.appendChild(el('h3', { text: hourKey })); lastHour = hourKey; }
      g.appendChild(el('button', { class: 'thumb', type: 'button', 'aria-label': 'Open frame from ' + fmtStamp(f.ts),
        onclick: function () { lbOpen(shown.map(frameItem), idx); } }, [
        el('img', { src: frameUrl(f.ts, 160), loading: 'lazy', decoding: 'async', alt: '' }),
        el('span', { text: fmtClock(f.ts) })
      ]));
    });
    $('gridNote').textContent = list.length
      ? 'Showing ' + shown.length + ' of ' + list.length + ' buffered frames, newest first.'
      : 'Nothing buffered yet. Frames appear once the phone has taken its first snapshot.';
    $('moreBtn').hidden = shown.length >= list.length;
  }

  $('gridLimit').addEventListener('change', function () { frames.shown = Number(this.value) || 60; renderGrid(); });
  $('moreBtn').addEventListener('click', function () { frames.shown += 60; renderGrid(); });
  $('scrubber').addEventListener('input', function () { stopPlay(); showTimeline(Number(this.value)); });
  $('tlPrev').addEventListener('click', function () { stopPlay(); showTimeline(frames.tlIndex - 1); });
  $('tlNext').addEventListener('click', function () { stopPlay(); showTimeline(frames.tlIndex + 1); });
  $('tlLive').addEventListener('click', function () {
    stopPlay();
    frames.follow = true;
    loadFrames();
  });
  $('tlImg').addEventListener('click', function () {
    if (!frames.list.length) return;
    var items = frames.list.map(frameItem);
    lbOpen(items, frames.list.length - 1 - frames.tlIndex);
  });

  function stopPlay() {
    clearInterval(frames.playTimer); frames.playTimer = 0;
    $('tlPlayText').textContent = 'Play';
    $('tlPlayIcon').firstElementChild.setAttribute('d', 'M8 5v14l11-7z');
  }
  function startPlay() {
    if (!frames.list.length) return;
    if (frames.tlIndex >= frames.list.length - 1) showTimeline(0);
    $('tlPlayText').textContent = 'Pause';
    $('tlPlayIcon').firstElementChild.setAttribute('d', 'M6 5h4v14H6V5zm8 0h4v14h-4V5z');
    frames.playTimer = setInterval(function () {
      if (frames.tlIndex >= frames.list.length - 1) { stopPlay(); return; }
      showTimeline(frames.tlIndex + 1);
    }, Number($('tlSpeed').value) || 200);
  }
  $('tlPlay').addEventListener('click', function () { if (frames.playTimer) stopPlay(); else startPlay(); });
  $('tlSpeed').addEventListener('change', function () { if (frames.playTimer) { stopPlay(); startPlay(); } });
  window.addEventListener('resize', drawDensity);

  // ---------------------------------------------------------------- events
  function eventItem(e) {
    var id = encodeURIComponent(String(e.id));
    return {
      src: '/events/' + id + '/snapshot.jpg',
      clip: e.has_clip ? '/events/' + id + '/clip.mp4' : null,
      title: String(e.type || 'event') + ' · ' + fmtStamp(num(e.start_time)),
      file: 'event-' + id + '.jpg',
      caption: e.caption ? String(e.caption) : ''
    };
  }

  function scoreText(e) {
    if (typeof e.score !== 'number') return '';
    return (e.score <= 1 ? Math.round(e.score * 100) : Math.round(e.score)) + '%';
  }

  function eventCard(e, items, idx) {
    var meta = el('div', { class: 'event-meta' }, [
      el('div', { class: 'event-type' }, [String(e.type || 'event'), scoreText(e) ? el('span', { class: 'tag', text: scoreText(e) }) : null]),
      el('div', { class: 'muted small', text: fmtStamp(num(e.start_time)) + ' · ' + fmtAgo(num(e.start_time)) }),
      e.caption ? el('div', { class: 'small', text: String(e.caption) }) : null
    ]);
    var thumb = e.has_snapshot
      ? el('img', { class: 'ph', src: '/events/' + encodeURIComponent(String(e.id)) + '/snapshot.jpg', loading: 'lazy', alt: '' })
      : el('div', { class: 'ph' });
    return el('button', { class: 'event', type: 'button', onclick: function () {
      if (!e.has_snapshot) { toast('This event has no snapshot'); return; }
      lbOpen(items, idx);
    } }, [thumb, meta]);
  }

  var eventsCache = [];
  function loadEvents() {
    return getJson('/events?limit=100').then(function (d) {
      eventsCache = (d && d.events) || [];
      var withSnap = eventsCache.filter(function (e) { return e.has_snapshot; });
      var items = withSnap.map(eventItem);
      var list = $('eventList');
      clear(list);
      if (!eventsCache.length) list.appendChild(el('div', { class: 'empty', text: 'No events yet. Detections appear here.' }));
      eventsCache.forEach(function (e) {
        list.appendChild(eventCard(e, items, Math.max(0, withSnap.indexOf(e))));
      });
      var strip = $('eventStrip');
      clear(strip);
      if (!withSnap.length) strip.appendChild(el('p', { class: 'muted small', text: 'No events yet.' }));
      withSnap.slice(0, 8).forEach(function (e, i) { strip.appendChild(eventCard(e, items, i)); });
    }).catch(function () { /* keep whatever is on screen */ });
  }

  $('testEventBtn').addEventListener('click', function () {
    post('/action/create-test-event').then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      toast('Test event created');
      return loadEvents();
    }).catch(function () { toast('Could not create test event', true); });
  });

  // ---------------------------------------------------------------- health
  var healthTimer = 0;

  function setBar(id, pct, cls) {
    var b = $(id);
    b.className = 'bar' + (cls ? ' ' + cls : '');
    b.firstElementChild.style.width = Math.max(0, Math.min(100, pct)) + '%';
    b.setAttribute('aria-label', 'Buffer coverage ' + Math.round(pct) + ' percent');
  }

  function renderHealth(h) {
    var b = h.buffer, d = h.device, now = h.now || Date.now();
    $('bufCount').textContent = String(b.count);
    $('bufSize').textContent = fmtBytes(b.totalBytes);
    $('bufOldest').textContent = b.count ? fmtStamp(b.oldest) : '--';
    $('bufNewest').textContent = b.count ? fmtStamp(b.newest) + ' (' + fmtAgo(b.newest) + ')' : '--';
    $('bufRetention').textContent = fmtDur(b.retentionMs);
    $('bufCap').textContent = b.maxFiles + ' frames';
    var low = b.freeBytes < b.minFreeBytes * 2;
    $('bufFree').textContent = fmtBytes(b.freeBytes) + (low ? ' (low)' : '');
    var covered = b.count ? b.newest - b.oldest : 0;
    setBar('covBar', b.coveragePercent, b.count && now - b.newest > 300000 ? 'bad' : (low ? 'warn' : ''));
    $('covText').textContent = b.count
      ? 'Holding ' + fmtDur(covered) + ' of the ' + fmtDur(b.retentionMs) + ' window (' + b.coveragePercent + '%).'
      : 'The buffer is empty.';
    var age = b.count ? now - b.newest : Infinity;
    if (!b.count) { setPill('bufPill', 'offline', 'Empty', 'bufPillText'); }
    else if (age < 60000) { setPill('bufPill', 'live', 'Recording', 'bufPillText'); }
    else if (age < 300000) { setPill('bufPill', 'stale', 'Behind', 'bufPillText'); }
    else { setPill('bufPill', 'offline', 'Not writing', 'bufPillText'); }

    var rtsp = h.ports.rtspActive;
    setPill('rtspPill2', rtsp ? 'live' : 'paused', rtsp ? 'Streaming' : 'Off', 'rtspText2');
    $('portDash').textContent = 'port ' + h.ports.dashboard;
    $('portRtsp').textContent = 'port ' + h.ports.rtsp;
    $('uptime').textContent = fmtDur(h.uptimeMs);

    $('hBattery').textContent = d.batteryLevel >= 0 ? d.batteryLevel + '%' + (d.charging ? ' · charging' : '') : '--';
    $('hTemp').textContent = d.batteryTempTenthsC >= 0 ? (d.batteryTempTenthsC / 10).toFixed(1) + ' °C' : '--';
    $('hThermal').textContent = THERMAL[d.thermalStatus] || String(d.thermalStatus);
    $('hWifi').textContent = d.wifiStrength >= 0 ? d.wifiStrength + '%' : '--';

    renderLink(h.link);

    if (status) {
      $('hVideo').textContent = (status.activeCodec || status.codec) + ' · ' + (status.resolution === '0x0' ? 'max' : status.resolution) + ' · ' + status.videoFps + ' fps';
      $('hDetect').textContent = status.detectionEnabled
        ? [status.motionDetectionEnabled ? 'motion' : null, status.objectDetectionEnabled ? 'objects' : null].filter(Boolean).join(' + ') || 'on'
        : 'off';
      $('hAuth').textContent = (status.webAuthEnabled ? 'dashboard sign-in on' : 'dashboard open') + ', RTSP ' + (status.authEnabled ? 'protected' : 'open');
    }
  }

  function renderLink(l) {
    if (!l) return;
    var tierText = l.jpegOnly ? 'JPEG only' : 'Tier ' + l.tier + ' · ' + l.tierKbps + ' kbit/s · ' + l.tierFps + ' fps';
    $('lTier').textContent = tierText;
    setPill('tierPill', l.jpegOnly ? 'paused' : (l.tier === 0 ? 'live' : 'stale'),
      l.jpegOnly ? 'JPEG only' : (l.tier === 0 ? 'Full quality' : 'Reduced'), 'tierPillText');
    $('lFps').textContent = l.jpegOnly ? 'no encoder'
      : l.encoderFps + ' fps measured · ' + l.activeEncoderFps + ' encoding · ' + l.configuredFps + ' configured';
    $('lWifi').textContent = l.rssiDbm < 0 ? l.rssiDbm + ' dBm · ' + l.linkMbps + ' Mbit/s' : '--';
    $('lTraffic').textContent = 'sent ' + fmtBytes(l.txBytes) + ' · received ' + fmtBytes(l.rxBytes);
    $('lFrameAge').textContent = l.jpegOnly
      ? (l.snapshotAgeMs >= 0 ? 'snapshot ' + fmtDur(l.snapshotAgeMs) + ' old' : '--')
      : (l.encodedFrameAgeMs >= 0 ? fmtDur(l.encodedFrameAgeMs) + ' old' : '--');
    $('lViewers').textContent = l.rtspClients + ' RTSP · ' + l.liveViewers + ' browser' + (l.congested ? ' · sender backed up' : '');
    $('lRestarts').textContent = l.reconnects + ' / ' + l.serviceRestarts + ' (' + l.tierChanges + ' tier changes)';
    $('lError').textContent = l.lastError || 'none';
    $('jpegOnlyChk').checked = !!l.jpegOnly;
  }

  $('jpegOnlyChk').addEventListener('change', function () {
    var on = this.checked;
    post('/action/set-setting', { key: 'jpeg_only', value: String(on) }).then(function () {
      toast(on ? 'JPEG-only mode on' : 'Video mode on');
      setTimeout(loadHealth, 1500);
    }, function () { toast('Could not change mode', true); });
  });
  $('restartBtn').addEventListener('click', function () {
    if (!window.confirm('Restart the camera app? The stream drops for about 15 seconds.')) return;
    post('/action/restart-service').then(function (r) { toast(r.ok ? 'Restarting…' : 'Restart failed', !r.ok); },
      function () { toast('Restarting…'); });
  });
  $('rebootBtn').addEventListener('click', function () {
    if (!window.confirm('Reboot the phone? It will be offline for about a minute or two.')) return;
    post('/action/reboot').then(function (r) { return r.text().then(function (t) { toast(r.ok ? 'Rebooting…' : t, !r.ok); }); },
      function () { toast('Rebooting…'); });
  });

  function loadHealth() {
    return getJson('/api/health').then(renderHealth).catch(function () {
      setPill('bufPill', 'offline', 'Unreachable', 'bufPillText');
    });
  }

  // ---------------------------------------------------------------- auth form
  $('authForm').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var user = $('authUser').value.trim(), pass = $('authPass').value, enabled = $('authEnabled').checked;
    if (enabled && (!user || !pass)) { toast('Enter a username and a new password first', true); return; }
    if (!pass && !window.confirm('The password is empty, so the stored password will be cleared. Continue?')) return;
    post('/action/set-auth', { enabled: String(enabled), username: user, password: pass }, true).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      $('authPass').value = '';
      toast('Credentials saved. You may be asked to sign in again.');
    }).catch(function () { toast('Could not save credentials', true); }).then(function () { setTimeout(pollStatus, 400); });
  });

  $('revealBtn').addEventListener('click', function () {
    rtspRevealed = !rtspRevealed;
    this.textContent = rtspRevealed ? 'Hide' : 'Reveal';
    if (status) $('rtspUrl').textContent = rtspRevealed ? status.rtspUrl : maskRtsp(status.rtspUrl);
  });
  $('copyRtsp').addEventListener('click', function () {
    if (!status) return;
    copyText(status.rtspUrl).then(function (ok) { toast(ok ? 'RTSP address copied' : 'Copy failed', !ok); });
  });
  $('copyDash').addEventListener('click', function () {
    copyText(location.origin).then(function (ok) { toast(ok ? 'Dashboard address copied' : 'Copy failed', !ok); });
  });

  // ---------------------------------------------------------------- router
  var TITLES = { live: 'Live', frames: 'Frames', events: 'Events', health: 'Health', settings: 'Settings' };

  function leave(name) {
    if (name === 'live') liveStop();
    if (name === 'frames') stopPlay();
    if (name === 'health') { clearInterval(healthTimer); healthTimer = 0; }
  }
  function enter(name) {
    if (name === 'live') { liveStart(); loadEvents(); }
    if (name === 'frames') loadFrames();
    if (name === 'events') loadEvents();
    if (name === 'health') { loadHealth(); healthTimer = setInterval(loadHealth, 10000); }
    if (name === 'settings') pollStatus();
  }

  function navigate() {
    var name = (location.hash || '#live').slice(1);
    if (!TITLES[name]) name = 'live';
    if (name === route) return;
    if (route) leave(route);
    route = name;
    Array.prototype.forEach.call(document.querySelectorAll('.view'), function (v) { v.hidden = v.getAttribute('data-view') !== name; });
    Array.prototype.forEach.call(document.querySelectorAll('.nav-link'), function (a) {
      if (a.getAttribute('data-route') === name) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    $('pageTitle').textContent = TITLES[name];
    document.title = TITLES[name] + ' · CCTV Dashboard';
    window.scrollTo(0, 0);
    enter(name);
  }
  window.addEventListener('hashchange', navigate);

  // ---------------------------------------------------------------- boot
  applyTheme(store('theme') === 'light' ? 'light' : 'dark');
  pollStatus();
  setInterval(pollStatus, 3000);
  setInterval(function () { if (route === 'frames' && !frames.playTimer && document.activeElement !== $('scrubber')) loadFrames(); }, 30000);
  navigate();
})();
