/*
 * F1-Race Control —— 界面逻辑（主界面和独立看板共用这一份）。
 *
 * ## 为什么只有这一份
 * 需求是"网页版显示安卓同款 UI"，而且 Windows 客户端就是把看板拉成独立窗口。
 * 如果 Windows 侧另写一套渲染，就有两份真相，迟早不一致。
 * 所以：数据来自 Java 侧的 F1Feed（安卓同一份源码），几何来自 F1Layout
 * （也是安卓同一份算术），颜色直接下发 TrackState 算好的 ARGB —— 这一层
 * 只负责画。
 */
(function () {
  'use strict';

  // ★ 脚本出错必须**看得见**。
  //   这里踩过一次真实的坑：接口全 200、页面顶部也画出来了，但主体一片空白，
  //   而 HTTP 状态码和服务器日志里没有任何异常 —— 因为错在前端。
  //   静默失败是最难查的一类，所以把错误直接糊到页面上、并写进标题。
  window.addEventListener('error', function (ev) {
    var msg = (ev && ev.message) || '未知错误';
    try {
      document.title = 'JS错误: ' + msg;
      if (document.getElementById('jserr')) { return; }
      var box = document.createElement('div');
      box.id = 'jserr';
      box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;background:#7f1d1d;'
        + 'color:#fff;padding:6px 10px;font-size:12px;z-index:99;font-family:monospace';
      box.textContent = '界面脚本出错：' + msg;
      (document.body || document.documentElement).appendChild(box);
    } catch (e) { /* 连报错都失败了，只能算了 */ }
  });

  var state = null;
  var es = null;
  var connected = false;
  var lastPush = 0;
  var errNote = '';

  // `?once=1` —— 静态快照模式：只拉一次 /api/state 画出来，然后**不挂 SSE**。
  // 两个用处：① 无头浏览器截图/DOM 校验时不会因为挂着长连接而卡住
  //         ② 只想看一眼当前状态、不想维持推流（比如手机省电）也可以用
  var ONCE = /(^|[?&])once=1(&|$)/.test(location.search);

  // ---------------------------------------------------------------
  // 工具
  // ---------------------------------------------------------------

  /** Android 的 ARGB int -> CSS 颜色。 */
  function argb(v) {
    if (v === null || v === undefined) { return '#777'; }
    var n = v >>> 0;
    var a = ((n >>> 24) & 255) / 255;
    var r = (n >>> 16) & 255, g = (n >>> 8) & 255, b = n & 255;
    if (a === 0) { a = 1; }
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(3) + ')';
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }

  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  /** 旗语颜色。★ 这**不是**从安卓下发的（只有聚合色下发），属于待对齐项。 */
  var KIND_COLOR = {
    'RED': '#d32f2f', 'RED_FLAG': '#d32f2f',
    'SC': '#ffb300', 'SAFETY_CAR': '#ffb300',
    'VSC': '#ffd54f', 'VIRTUAL_SC': '#ffd54f', 'VIRTUAL_SAFETY_CAR': '#ffd54f',
    'DOUBLE_YELLOW': '#f9a825', 'DYK': '#f9a825',
    'YELLOW': '#fdd835',
    'GREEN': '#43a047', 'CLEAR': '#43a047',
    'CHEQUERED': '#eeeeee', 'CHEQUERED_FLAG': '#eeeeee',
    'BLUE': '#1e88e5', 'BLACK': '#212121',
    'WHITE': '#fafafa', 'BLACK_WHITE': '#9e9e9e'
  };

  function kindColor(k) {
    if (!k) { return '#546e7a'; }
    if (KIND_COLOR[k]) { return KIND_COLOR[k]; }
    var up = String(k).toUpperCase().replace(/\s+/g, '_');
    if (KIND_COLOR[up]) { return KIND_COLOR[up]; }
    return '#546e7a';
  }

  function fmtClock(ms) {
    if (!ms) { return '—'; }
    var d = new Date(ms);
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  /** 相对"多久以前"，比绝对时间有用（数据是 10Hz 推的）。 */
  function ago(ms) {
    if (!ms) { return '—'; }
    var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 2) { return '刚刚'; }
    if (s < 60) { return s + ' 秒前'; }
    return Math.round(s / 60) + ' 分钟前';
  }

  // ---------------------------------------------------------------
  // 各看板渲染
  // ---------------------------------------------------------------

  function renderFlags(box) {
    clear(box);
    var st = state;
    var bar = (st && st.bar) || [];
    var track = (st && st.track) || {};
    var wrap = el('div', 'flags');
    wrap.style.background = argb(track.color);

    if (!bar.length) {
      // ★ 无旗语也要"有东西"：显示一整条绿，写明全畅通和区段范围。
      //   之前这里是"赛道正常（Inactive）"一行字，看板上 2/3 是空白，
      //   看起来像坏了 —— 而且"全部区段都正常"本身就是有用的信息。
      var n = (state && state.ring && state.ring.count) || 0;
      var green = el('div', 'flag-seg');
      green.style.flexGrow = '1';
      green.style.background = kindColor('GREEN');
      green.appendChild(el('div', 'flag-kind', '全赛道畅通'));
      green.appendChild(el('div', 'flag-sectors',
        n ? ('区段 1-' + n + ' 全部正常') : '暂无区段数据'));
      wrap.appendChild(green);
    } else {
      for (var i = 0; i < bar.length; i++) {
        var seg = bar[i];
        var d = el('div', 'flag-seg');
        // 几何来自 F1Layout（千分比），这里只按容器宽度缩放
        d.style.flexGrow = String(seg.width || 1);
        d.style.background = kindColor(seg.kind);
        var k = el('div', 'flag-kind', seg.kind);
        var s = el('div', 'flag-sectors', seg.sectors ? ('区段 ' + seg.sectors) : '');
        d.appendChild(k);
        d.appendChild(s);
        wrap.appendChild(d);
      }
    }
    box.appendChild(wrap);

    var sum = el('div', 'flag-summary');
    sum.textContent = (track.label || '') + (track.detail ? '  ' + track.detail : '');
    sum.style.color = argb(track.textColor);
    box.appendChild(sum);
  }

  function renderRing(box) {
    clear(box);
    var st = state;
    var ring = (st && st.ring) || { count: 0, starts: [], sweep: 0 };
    var track = (st && st.track) || {};
    var cv = el('canvas', 'ring');
    var W = box.clientWidth || 320;
    var H = box.clientHeight || 320;
    var dpr = window.devicePixelRatio || 1;
    cv.width = Math.max(1, Math.floor(W * dpr));
    cv.height = Math.max(1, Math.floor(H * dpr));
    cv.style.width = '100%';
    cv.style.height = '100%';
    box.appendChild(cv);

    var g = cv.getContext('2d');
    g.scale(dpr, dpr);
    var cx = W / 2, cy = H / 2;
    var rOut = Math.min(W, H) * 0.42;
    var rIn = rOut * 0.62;

    var n = ring.count || 0;
    if (!n) {
      g.fillStyle = '#546e7a';
      g.font = '14px system-ui, sans-serif';
      g.textAlign = 'center';
      g.fillText('还没有区段数据', cx, cy);
      return;
    }

    var dy = {}; (track.doubleYellowSectors || []).forEach(function (x) { dy[x] = 1; });
    var ye = {}; (track.yellowSectors || []).forEach(function (x) { ye[x] = 1; });

    for (var i = 0; i < n; i++) {
      // ★ F1Layout 约定：0 度 = 12 点、顺时针。Canvas 的 0 度 = 3 点，
      //   所以这里减 90 —— F1Layout 的注释专门警告过这个 off-by-90。
      var startDeg = (ring.starts[i] !== undefined ? ring.starts[i] : i * (360 / n)) - 90;
      var endDeg = startDeg + (ring.sweep || 360 / n);
      var a0 = startDeg * Math.PI / 180, a1 = endDeg * Math.PI / 180;

      var sec = i + 1;
      var col = '#37474f';
      if (dy[sec]) { col = '#f9a825'; } else if (ye[sec]) { col = '#fdd835'; }
      if (track.level >= 6) { col = '#d32f2f'; }

      g.beginPath();
      g.arc(cx, cy, rOut, a0, a1);
      g.arc(cx, cy, rIn, a1, a0, true);
      g.closePath();
      g.fillStyle = col;
      g.fill();
      g.strokeStyle = '#0d1117';
      g.lineWidth = 1.5;
      g.stroke();

      // 区段号（宽了才画，和 F1Layout.labelFits 一个意思）
      var mid = (a0 + a1) / 2;
      var rl = (rOut + rIn) / 2;
      var lx = cx + Math.cos(mid) * rl, ly = cy + Math.sin(mid) * rl;
      if ((2 * Math.PI * rIn) / n >= 16) {
        g.fillStyle = '#eceff1';
        g.font = '11px system-ui, sans-serif';
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillText(String(sec), lx, ly);
      }
    }

    g.fillStyle = argb(track.color);
    g.beginPath();
    g.arc(cx, cy, rIn * 0.92, 0, Math.PI * 2);
    g.fill();
    g.fillStyle = argb(track.textColor);
    g.font = 'bold 15px system-ui, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(track.label || '', cx, cy - 8);
    g.font = '11px system-ui, sans-serif';
    g.fillText(ago(track.updatedAt), cx, cy + 12);
  }

  function panelTitle(box, text) {
    var t = el('div', 'ptitle', text);
    box.appendChild(t);
  }

  function renderTrack(box) {
    clear(box);
    panelTitle(box, '赛道图（区段）');
    var st = state, track = (st && st.track) || {};
    box.appendChild(el('div', 'big', track.label || '—'));
    box.appendChild(el('div', 'sub', track.detail || ''));
    var kinds = track.kinds || [];
    var list = el('div', 'kinds');
    for (var i = 0; i < kinds.length; i++) {
      var chip = el('span', 'chip', kinds[i]);
      chip.style.background = kindColor(kinds[i]);
      list.appendChild(chip);
    }
    box.appendChild(list);
    var scale = el('div', 'sub');
    scale.textContent = (st && st.race && st.race.trackStatusCode)
      ? 'TrackStatus=' + st.race.trackStatusCode : '';
    box.appendChild(scale);
  }

  function renderTyres(box) {
    clear(box);
    panelTitle(box, '轮胎 / 进站（按赛道位置）');
    var cars = (state && state.cars) || [];
    // ★ 3 列 × 8 行 = 24 格、用到 22 个 —— 这是 F1Layout.capacity/boardColumn/
    //   boardRow 定义的排法，这里照搬同样的行列规则。
    var rows = 8, cols = 3;
    var grid = el('div', 'tyres');
    grid.style.gridTemplateRows = 'repeat(' + rows + ', 1fr)';
    grid.style.gridTemplateColumns = 'repeat(' + cols + ', 1fr)';
    // 列优先填充：先第一列 8 个
    var cells = [];
    for (var i = 0; i < rows * cols; i++) { cells.push(null); }
    for (var k = 0; k < cars.length && k < rows * cols; k++) {
      var col = Math.floor(k / rows), row = k % rows;
      cells[col * rows + row] = cars[k];
    }
    for (var c = 0; c < cols; c++) {
      for (var r = 0; r < rows; r++) {
        var car = cells[c * rows + r];
        var d = el('div', 'tcell' + (car ? '' : ' empty'));
        if (car) {
          d.appendChild(el('span', 'tpos', car.position));
          d.appendChild(el('span', 'ttla', car.tla));
          var cp = el('span', 'ttyre', car.compoundCn || car.compound || '');
          cp.style.borderColor = car.teamColour ? ('#' + car.teamColour.replace('#', '')) : '#555';
          d.appendChild(cp);
          d.appendChild(el('span', 'tlaps', car.tyreLaps ? (car.tyreLaps + '圈') : ''));
          if (car.pitStops) { d.appendChild(el('span', 'tpit', 'P' + car.pitStops)); }
        }
        grid.appendChild(d);
      }
    }
    box.appendChild(grid);
  }

  function renderTiming(box) {
    clear(box);
    panelTitle(box, '成绩榜');
    var cars = (state && state.cars) || [];
    var t = el('table', 'timing');
    var head = el('tr');
    ['名次', '车手', '差距', '间隔', '胎', '圈', '站'].forEach(function (h) {
      head.appendChild(el('th', null, h));
    });
    t.appendChild(head);
    for (var i = 0; i < cars.length; i++) {
      var c = cars[i];
      var tr = el('tr');
      if (c.retired) { tr.className = 'retired'; }
      if (c.inPit) { tr.className += ' inpitt'; }
      tr.appendChild(el('td', 'num', c.position || ''));
      var who = el('td', 'who');
      var bar = el('span', 'teambar');
      bar.style.background = c.teamColour ? ('#' + c.teamColour.replace('#', '')) : '#555';
      who.appendChild(bar);
      who.appendChild(el('span', null, c.tla + ' ' + (c.number || '')));
      tr.appendChild(who);
      tr.appendChild(el('td', null, c.gap || ''));
      tr.appendChild(el('td', null, c.interval || ''));
      tr.appendChild(el('td', null, c.compoundCn || c.compound || ''));
      tr.appendChild(el('td', null, c.tyreLaps || ''));
      tr.appendChild(el('td', null, c.pitStops || ''));
      t.appendChild(tr);
    }
    box.appendChild(t);
  }

  function renderWeather(box) {
    clear(box);
    panelTitle(box, '天气与赛道');
    var w = (state && state.weather) || {};
    var grid = el('div', 'weather');
    var items = [['气温', w.air], ['赛道温', w.track], ['天气', w.text]];
    for (var i = 0; i < items.length; i++) {
      var d = el('div', 'wcell');
      d.appendChild(el('div', 'wlabel', items[i][0]));
      d.appendChild(el('div', 'wvalue', items[i][1] || '—'));
      grid.appendChild(d);
    }
    box.appendChild(grid);
  }

  function renderFastest(box) {
    clear(box);
    panelTitle(box, '个人最快圈');
    var cars = ((state && state.cars) || []).slice();
    var withBest = cars.filter(function (c) { return c.bestLap; });
    if (!withBest.length) {
      box.appendChild(el('div', 'sub', '还没有最快圈数据'));
      return;
    }
    withBest.sort(function (a, b) { return lapMs(a.bestLap) - lapMs(b.bestLap); });
    var t = el('table', 'timing');
    for (var i = 0; i < withBest.length && i < 22; i++) {
      var c = withBest[i];
      var tr = el('tr');
      tr.appendChild(el('td', 'num', (i + 1)));
      tr.appendChild(el('td', 'who', c.tla));
      tr.appendChild(el('td', 'mono', c.bestLap));
      t.appendChild(tr);
    }
    box.appendChild(t);
  }

  /** "1:23.456" -> 毫秒。用来排序。 */
  function lapMs(s) {
    if (!s) { return Infinity; }
    var m = /^(\d+):(\d+\.\d+)$/.exec(String(s).trim());
    if (m) { return parseInt(m[1], 10) * 60000 + parseFloat(m[2]) * 1000; }
    var f = parseFloat(s);
    return isNaN(f) ? Infinity : f * 1000;
  }

  function renderSession(box) {
    clear(box);
    panelTitle(box, '环节');
    var r = (state && state.race) || {};
    var rows = [
      ['会议', r.meeting], ['赛道', r.circuit], ['环节', r.session],
      ['状态', r.status], ['剩余', r.remaining],
      ['圈数', (r.lap || 0) + ' / ' + (r.totalLaps || 0)]
    ];
    var t = el('table', 'kv');
    for (var i = 0; i < rows.length; i++) {
      var tr = el('tr');
      tr.appendChild(el('th', null, rows[i][0]));
      tr.appendChild(el('td', null, rows[i][1] || '—'));
      t.appendChild(tr);
    }
    box.appendChild(t);
    var top = (r.topThree || []);
    if (top.length) {
      box.appendChild(el('div', 'ptitle', '前三名'));
      var l = el('div', 'top3');
      for (var k = 0; k < top.length; k++) {
        l.appendChild(el('div', 'top3row', (k + 1) + '. ' + top[k]));
      }
      box.appendChild(l);
    }
  }

  /**
   * 一条消息该显示成什么。
   * ★ 和安卓同一个约定：Translator 翻出来了用中文，翻不出来就显示英文原文
   *   （Translator.gloss 的契约是"翻不出来返回 null"）。
   *   这里**不能**在中文化失败时显示空白 —— 那就把信息吞了。
   */
  function msgLine(m) {
    var g = m.gloss;
    if (g && /[\u4e00-\u9fff]/.test(g)) {
      return { zh: g, en: m.text, translated: true };
    }
    return { zh: m.text, en: m.text, translated: false };
  }

  /** 消息时间。RaceMessage.time 是 epoch 毫秒，直接显示就是一串 13 位数。 */
  function msgTime(m) {
    if (typeof m.time === 'number' && m.time > 1e11) { return fmtClock(m.time); }
    return m.time || '';
  }

  function renderMessages(box) {
    clear(box);
    panelTitle(box, '赛事通报');
    var msgs = (state && state.messages) || [];
    if (!msgs.length) {
      box.appendChild(el('div', 'sub', '还没有通报'));
      return;
    }
    var list = el('div', 'msgs');
    for (var i = 0; i < msgs.length; i++) {
      var m = msgs[i];
      var line = msgLine(m);
      var d = el('div', 'msg' + (i === 0 ? ' newest' : ''));
      d.appendChild(el('span', 'mtime', msgTime(m)));
      var cat = el('span', 'mcat', m.category || m.flag || '');
      if (m.category || m.flag) { d.appendChild(cat); }
      d.appendChild(el('span', 'mtext', line.zh));
      // 悬停看英文原文和 F1 官方的 UTC 时间戳
      var tip = [];
      if (line.translated && line.en && line.en !== line.zh) { tip.push(line.en); }
      if (m.utc) { tip.push(m.utc); }
      if (tip.length) { d.title = tip.join('\n'); }
      list.appendChild(d);
    }
    box.appendChild(list);
  }

  var RENDER = {
    flags: renderFlags, ring: renderRing, track: renderTrack,
    tyres: renderTyres, timing: renderTiming, weather: renderWeather,
    fastest: renderFastest, session: renderSession, messages: renderMessages
  };

  var TITLES = {
    flags: '顶部旗语栏', ring: '车手圆环', track: '赛道图', tyres: '轮胎进站',
    timing: '成绩榜', weather: '天气', fastest: '最快圈', session: '环节',
    messages: '赛事通报'
  };

  // ---------------------------------------------------------------
  // 主界面：顶部旗语栏 + 左侧圆环 + 右侧 6 屏
  // ---------------------------------------------------------------

  function renderMain(root) {
    clear(root);

    var head = el('div', 'm-head');
    var brand = el('div', 'm-brand');
    var r = (state && state.race) || {};
    brand.appendChild(el('div', 'm-title', (r.meeting || 'F1 Race Control')));
    brand.appendChild(el('div', 'm-sub',
      (r.session || '') + ' · ' + (r.status || '')
      + ' · 第 ' + (r.lap || 0) + '/' + (r.totalLaps || 0) + ' 圈'
      + (r.remaining ? ' · 剩 ' + r.remaining : '')));
    head.appendChild(brand);

    var right = el('div', 'm-status');
    var dot = el('span', 'dot' + (connected ? ' ok' : ' bad'));
    right.appendChild(dot);
    right.appendChild(el('span', null, connected
      ? ('已连接 · ' + ago(lastPush)) : (errNote || '未连接')));
    if (state && state.replay) {
      right.appendChild(el('span', 'badge', '回放 ' + (state.replayName || '')));
    }
    right.appendChild(el('span', 'badge', 'v' + ((state && state.version) || '?')));
    head.appendChild(right);

    root.appendChild(head);

    var flags = el('div', 'm-flags');
    renderFlags(flags);
    root.appendChild(flags);

    // 最新一条通报做成横幅 —— 这是"刚刚发生了什么"最该被一眼看到的地方。
    // 和安卓一样：有中文用中文，没有就用英文原文。
    var msgs = (state && state.messages) || [];
    if (msgs.length) {
      var line = msgLine(msgs[0]);
      var banner = el('div', 'm-banner');
      banner.appendChild(el('span', 'banner-time', msgTime(msgs[0])));
      banner.appendChild(el('span', 'banner-text', line.zh));
      if (line.translated) {
        banner.appendChild(el('span', 'banner-en', line.en));
      }
      root.appendChild(banner);
    }

    var body = el('div', 'm-body');
    var left = el('div', 'm-left');
    var ringBox = el('div', 'm-ring');
    renderRing(ringBox);
    left.appendChild(ringBox);
    var msgBox = el('div', 'panel m-msgs');
    renderMessages(msgBox);
    left.appendChild(msgBox);
    body.appendChild(left);

    var grid = el('div', 'm-grid');
    var ids = ['track', 'tyres', 'timing', 'weather', 'fastest', 'session'];
    for (var i = 0; i < ids.length; i++) {
      var p = el('div', 'panel');
      p.setAttribute('data-board', ids[i]);
      RENDER[ids[i]](p);
      grid.appendChild(p);
    }
    body.appendChild(grid);
    root.appendChild(body);
  }

  function renderBoard(root) {
    clear(root);
    var m = /^\/board\/([a-z]+)/.exec(location.pathname);
    var id = m ? m[1] : (new URLSearchParams(location.search)).get('id');
    if (!id || !RENDER[id]) {
      root.appendChild(el('div', 'big', '未知看板: ' + id));
      return;
    }
    var head = el('div', 'b-head');
    head.appendChild(el('div', 'b-title', TITLES[id] || id));
    var dot = el('span', 'dot' + (connected ? ' ok' : ' bad'));
    head.appendChild(dot);
    head.appendChild(el('span', 'b-sub', connected ? ago(lastPush) : (errNote || '未连接')));
    root.appendChild(head);
    var box = el('div', 'b-box b-' + id);
    RENDER[id](box);
    root.appendChild(box);
  }

  // ---------------------------------------------------------------
  // 连接
  // ---------------------------------------------------------------

  function repaint() {
    var main = document.getElementById('main');
    if (main) { renderMain(main); }
    var board = document.getElementById('board');
    if (board) {
      // 看板不做全量重建：重建会闪、也会丢掉 canvas 的滚动手感。
      // ★ 但骨架必须先存在 —— 第一次 repaint 时 #board 还是空的，
      //   这时候得走一次完整渲染，否则页面永远是白的。
      var box0 = board.querySelector('.b-box');
      if (!box0) { renderBoard(board); return; }
      var m = /^\/board\/([a-z]+)/.exec(location.pathname);
      var id = m ? m[1] : null;
      if (id && RENDER[id]) {
        var head = board.querySelector('.b-head .b-sub');
        if (head) {
          head.textContent = connected ? ago(lastPush) : (errNote || '未连接');
        }
        var dot = board.querySelector('.b-head .dot');
        if (dot) { dot.className = 'dot' + (connected ? ' ok' : ' bad'); }
        var box = board.querySelector('.b-box');
        if (box) { RENDER[id](box); }
      } else {
        renderBoard(board);
      }
    }
  }

  function onState(s) {
    state = s;
    lastPush = s.lastUpdateAt || Date.now();
    repaint();
  }

  function connect() {
    if (es) { es.close(); }
    es = new EventSource('/api/events');
    es.addEventListener('state', function (ev) {
      connected = true;
      errNote = '';
      try { onState(JSON.parse(ev.data)); } catch (e) { errNote = '解析失败'; }
    });
    es.addEventListener('open', function () { connected = true; errNote = ''; });
    es.addEventListener('error', function () {
      connected = false;
      errNote = '连接断了，正在重连……';
      repaint();
    });
  }

  // 每秒刷新"多久以前"，即使没有新数据
  setInterval(function () {
    if (!connected || ONCE) { return; }
    repaint();
  }, 1000);

  window.F1 = {
    connect: connect, RENDER: RENDER, TITLES: TITLES, argb: argb,
    renderMain: renderMain, renderBoard: renderBoard,
    state: function () { return state; }
  };

  document.addEventListener('DOMContentLoaded', function () {
    // ★ 先拉一次快照立刻画，再挂 SSE。
    //   只靠 SSE 的话首屏要等第一条事件才出内容 —— 局域网上是几十毫秒，
    //   但手机刚解锁、页面刚打开时那一下空白很显眼。
    //   （顺带让无头浏览器截图变得确定：load 事件之后页面就已有内容。）
    fetch('/api/state', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (s) { connected = true; onState(s); })
      .catch(function () {
        errNote = '首屏快照没拉到，等推送……';
        repaint();
      })
      .then(function () { if (!ONCE) { connect(); } });
  });
}());