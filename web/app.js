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

  // 哪些看板已经拉成了独立小窗。★ 以服务器为准，前端不自己记账：
  // 用户可能直接用窗口的 X 关掉，前端根本收不到那个事件。
  var popped = {};

  // 这个页面所在的设备能不能让服务器开/关窗口（服务器按来源是不是回环判断）。
  // 手机上为 false —— 那时显示"收回全部"是在骗人：那些窗口不在这台设备上。
  var canNative = false;

  // 圆环量不到尺寸时允许补几次重绘（见 renderRing）。
  var refitLeft = 3;

  // 手机横屏时右栏显示哪一屏（安卓默认是"赛道图"，也就是圆环）。
  var mobilePanel = 'ring';
  // 手机状态行上的一句临时提示（比如"全屏没成功"）—— 手机版没有头部，
  // 消息只能挂在这一行上。几秒后自己消失。
  var phoneNotice = '';

  // 手机右栏那条窄框图标 —— 和安卓 RightPanelView 的 MODE_ICONS/MODE_NAMES 一一对应：
  //   0 ◎ 赛道图(圆环)  1 ◍ 轮胎  2 ≡ 成绩  3 ☂ 天气  4 ⏱ 最快圈  5 ⓘ 环节
  // 用单字符是为了不依赖任何字体资源（安卓那边也是这个理由）。
  var PHONE_MODES = [
    ['ring', '◎', '赛道图（圆环）'],
    ['tyres', '◍', '轮胎 / 进站'],
    ['timing', '≡', '成绩榜'],
    ['weather', '☂', '天气'],
    ['fastest', '⏱', '最快圈'],
    ['session', 'ⓘ', '环节']
  ];

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
  // 弹出 / 收回独立小窗
  // ---------------------------------------------------------------

  /** 现在渲染的是主界面（而不是某个独立看板页）。 */
  function inMain() { return !!document.getElementById('main'); }

  /**
   * 问服务器：每块看板现在弹出来了没有。
   *
   * 为什么要问：用户可以直接用窗口的 X 关掉小窗，前端收不到通知。
   * 所以按钮上的"已弹出"不能靠前端自己记，只能问出来。
   */
  function refreshPopped() {
    fetch('/api/boards', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var next = {};
        (d.boards || []).forEach(function (b) { if (b.popped) { next[b.id] = true; } });
        var cn = !!d.canNative;
        if (JSON.stringify(next) !== JSON.stringify(popped) || cn !== canNative) {
          popped = next;
          canNative = cn;
          repaint();
        }
      })
      .then(null, function () { /* 问不到就维持现状，别因此报错 */ });
  }

  /**
   * 点一下 → 弹出 / 收回。
   *
   * 是"开原生窗口"还是"开新标签页"由**服务器**决定，前端不猜：
   *   - 本机：服务器能开窗口，就开原生小窗
   *   - 手机 / 局域网设备：服务器没法在手机上开窗口，返回 mode=tab，
   *     这里再 window.open 一个新标签页
   * 猜错的后果是"点了没反应"，那比多一次往返难受得多。
   */
  function popout(id) {
    fetch('/api/popout/' + id, { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d && d.mode === 'tab' && d.url) {
          var w = window.open(d.url, '_blank');
          if (!w) {
            errNote = '浏览器拦住了弹出窗口，请允许后重试';
            repaint();
          }
          return;
        }
        if (d && d.reason) { errNote = d.reason; }
        popped[id] = !!(d && d.popped);
        repaint();
      })
      .then(null, function (e) {
        errNote = '弹出失败：' + ((e && e.message) || e);
        repaint();
      });
  }

  /** 标题栏右侧的"弹出 / 收回"按钮。 */
  function popButton(id) {
    var on = !!popped[id];
    var b = el('button', 'p-pop' + (on ? ' on' : ''));
    b.type = 'button';
    b.textContent = on ? '收回' : '弹出';
    b.title = on
      ? '收回：关掉这块看板的独立窗口'
      : '把这块看板拉成独立小窗（手机上是新标签页）';
    b.addEventListener('click', function (ev) {
      ev.stopPropagation();   // 免得标题栏再触发一次
      popout(id);
    });
    return b;
  }

  /**
   * 一键收回所有弹出去的小窗。
   *
   * 只在本机显示这个按钮（canNative），因为那些窗口在**服务器这台电脑**上：
   * 手机上是关不掉的，摆个按钮只会让人以为点了没用。
   */
  function closeAllPoppedAction() {
    fetch('/api/popout/all', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d && d.error) { errNote = d.error; }
        popped = {};
        repaint();
      })
      .then(null, function (e) {
        errNote = '收回失败：' + ((e && e.message) || e);
        repaint();
      });
  }

  /** 「收回全部 (N)」按钮；没有小窗或不在本机时返回 null。 */
  function popAllButton() {
    var n = 0;
    for (var k in popped) { if (popped[k]) { n++; } }
    if (!n || !canNative) { return null; }
    var b = el('button', 'p-pop p-pop-all');
    b.type = 'button';
    b.textContent = '收回全部 (' + n + ')';
    b.title = '一次关掉所有弹出去的小窗（主界面会留着）';
    b.addEventListener('click', closeAllPoppedAction);
    return b;
  }

  /** 一条面板标题栏。带 data-board 且在主界面时，右侧就有弹出按钮。 */
  function titleRow(text, id) {
    var t = el('div', 'ptitle');
    t.appendChild(el('span', 'ptitle-text', text));
    if (id && inMain()) {
      t.appendChild(popButton(id));
      t.classList.add('p-click');
      t.title = '点标题栏也能把这块看板拉成独立小窗';
      t.addEventListener('click', function (ev) {
        if (ev.target && ev.target.classList
            && ev.target.classList.contains('p-pop')) {
          return;   // 按钮自己处理
        }
        popout(id);
      });
    }
    return t;
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

    // ★ 画布必须是正方形，而且尺寸要用 px 写死。
    //
    //   踩过的坑：原来是 cv.style.width/height = 100%，像素尺寸却取自
    //   box.clientWidth/clientHeight。两者只要不等（盒子有 padding、或者首次
    //   渲染时还没布局、clientWidth 取到 0 走了 320 的回退、又或者用户把窗口
    //   拉大后没有重新测量），CSS 就会把画布**拉伸** —— 圆变成椭圆，
    //   而且拉伸一定糊。
    //   现在：短边 = 边长，画布 px 尺寸 == 像素尺寸/dpr，任何时候都不会被拉伸。
    var avail = Math.min(box.clientWidth || 0, box.clientHeight || 0);
    if (!avail) {
      avail = 320;   // 还没布局就先按 320 画，下一次重绘会纠正过来
      // 但如果这是 ?once=1 快照模式、或者容器还是隐藏的，就没有"下一次"了，
      // 所以主动补一次重绘（有次数上限，避免量不到时无限刷帧）。
      if (refitLeft > 0) {
        refitLeft--;
        if (window.requestAnimationFrame) {
          window.requestAnimationFrame(function () { repaint(); });
        } else {
          setTimeout(function () { repaint(); }, 16);
        }
      }
    } else {
      refitLeft = 3;
    }
    var side = Math.max(80, Math.round(avail));
    var dpr = window.devicePixelRatio || 1;

    var cv = el('canvas', 'ring');
    cv.setAttribute('data-fit', box.clientWidth + 'x' + box.clientHeight);
    cv.style.width = side + 'px';
    cv.style.height = side + 'px';
    cv.width = Math.max(1, Math.round(side * dpr));
    cv.height = Math.max(1, Math.round(side * dpr));
    box.appendChild(cv);

    var g = cv.getContext('2d');
    g.scale(dpr, dpr);
    var cx = side / 2, cy = side / 2;
    var rOut = side * 0.42;
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

  /** 面板标题。看板页里没有 data-board，自然就不会出现弹出按钮。 */
  function panelTitle(box, text) {
    box.appendChild(titleRow(text, box.getAttribute('data-board')));
  }

  function renderTrack(box) {
    clear(box);
    panelTitle(box, '赛道状态');
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
      // ★ 车手名带上车队颜色（和成绩榜一致）—— 用户要的：
      //   "个人最快圈加上车队颜色"。色条用的是同一套 teamColour。
      var who = el('td', 'who');
      var bar = el('span', 'teambar');
      bar.style.background = c.teamColour
        ? ('#' + c.teamColour.replace('#', '')) : '#555';
      who.appendChild(bar);
      who.appendChild(el('span', null, c.tla));
      tr.appendChild(who);
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
      // ★ 别把大奖赛叫"会议" —— meeting 是 F1 的说法（分站/大奖赛），
      //   中文"会议"完全是另一回事（用户指出来了）。
      ['大奖赛', r.meeting], ['赛道', r.circuit], ['环节', r.session],
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
      // ★ 译文和**原文**都要看得见 —— 安卓的 RowAdapter 就是这么两行：
      //   body = 译文（14sp 白），orig = 原文（10sp 半透明白，可见）。
      //   原来我只把原文塞进 title 悬停提示里：手机上根本没有悬停，
      //   等于原文完全看不到（用户一眼就看出来"怎么只剩翻译了"）。
      var body = el('div', 'mbody');
      body.appendChild(el('span', 'mtext', line.zh));
      if (line.translated && line.en && line.en !== line.zh) {
        body.appendChild(el('span', 'morig', line.en));
      }
      d.appendChild(body);
      // 悬停仍然给 F1 官方的 UTC 时间戳（原文已经显示出来了，不再重复）
      if (m.utc) { d.title = m.utc; }
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
    // ★ 圆环就叫「赛道图」—— 它画的就是赛道（一圈区段），和安卓一致
    //   （安卓 MODE_NAMES 第一项就是"赛道图"）。以前这里写"车手圆环"，
    //   用户直接指出来了。下面那个 track 板其实是**文字**状态汇总，不是地图，
    //   所以改名"赛道状态"，免得两块板同名。
    flags: '顶部旗语栏', ring: '赛道图', track: '赛道状态', tyres: '轮胎进站',
    timing: '成绩榜', weather: '天气', fastest: '最快圈', session: '环节',
    messages: '赛事通报'
  };

  // ---------------------------------------------------------------
  // 手机版主界面（和安卓同款：只显示该显的）
  // ---------------------------------------------------------------

  /**
   * 这是不是手机屏；是的话该按哪种排布。
   *
   * 判据用**短边**：手机不管横竖，短边都在 400 上下；平板和桌面窗口的短边
   * 明显更大。只看宽度会在横屏时把手机错判成桌面（844 宽的横屏手机）。
   *
   * 返回 'portrait' / 'landscape' / null（null = 按桌面版渲染）。
   */
  function phoneMode() {
    var w = window.innerWidth, h = window.innerHeight;
    if (Math.min(w, h) > 560) { return null; }
    return h >= w ? 'portrait' : 'landscape';
  }

  /** 手机横屏右栏那条窄框图标；点谁切谁（对应安卓的 buildIconStrip）。 */
  function phoneIconStrip() {
    var strip = el('div', 'm-strip');
    for (var i = 0; i < PHONE_MODES.length; i++) {
      var b = el('button', 'strip-btn'
        + (PHONE_MODES[i][0] === mobilePanel ? ' on' : ''));
      b.type = 'button';
      b.textContent = PHONE_MODES[i][1];
      b.title = PHONE_MODES[i][2];
      b.setAttribute('data-mode', PHONE_MODES[i][0]);
      // ★ 用闭包把 id 定住。循环变量直接进回调的话，点哪个都是最后一屏。
      b.addEventListener('click', (function (id) {
        return function () {
          mobilePanel = id;
          repaint();
        };
      })(PHONE_MODES[i][0]));
      strip.appendChild(b);
    }
    return strip;
  }

  /**
   * 全屏按钮（手机用）。
   *
   * 手机浏览器地址栏吃掉的高度很值这一下（安卓那边没这个按钮，但网页版是在
   * 浏览器里跑，比不了）。
   *
   * 按钮**总是**摆出来 —— 但要是这台浏览器压根不支持对任意元素全屏
   * （iOS 的 Safari 只给 video 元素全屏），点下去会如实说一句"该怎么办"
   * （iPhone 那条路是「分享 → 添加到主屏幕」），而不是装作没这回事。
   */
  function fullscreenButton() {
    var doc = document.documentElement;
    var req = doc.requestFullscreen || doc.webkitRequestFullscreen;
    var exit = document.exitFullscreen || document.webkitExitFullscreen;
    var on = !!(document.fullscreenElement || document.webkitFullscreenElement);
    var b = el('button', 'phone-btn phone-btn-fs' + (on ? ' on' : ''),
      on ? '退出全屏' : '全屏');
    b.type = 'button';
    b.title = on ? '退出全屏' : '全屏（藏起浏览器的地址栏）';
    b.addEventListener('click', function () {
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        if (exit) { exit.call(document); }
        return;
      }
      if (!req) {
        notice('这台浏览器不支持网页全屏；iPhone 可用「分享 → 添加到主屏幕」');
        return;
      }
      try {
        var r = req.call(doc);
        // 有的浏览器返回 Promise：被拒（比如没算作"用户手势"）时如实说一句
        if (r && r.catch) {
          r.catch(function (e) { notice('全屏没成功：' + ((e && e.message) || e)); });
        }
      } catch (e) {
        notice('全屏没成功：' + e.message);
      }
    });
    return b;
  }

  /** 在手机状态行上说一句，8 秒后自己消失。 */
  function notice(msg) {
    phoneNotice = msg;
    repaint();
    setTimeout(function () {
      if (phoneNotice === msg) {
        phoneNotice = '';
        repaint();
      }
    }, 8000);
  }

  /**
   * 手机上那一行薄状态行。
   *
   * 安卓的状态行是塞在左栏顶部的（buildStatusRow 加在 left 里），不在旗语栏里 ——
   * 所以手机版也放这儿，而不是像桌面版那样占一整条头部。手机屏就那么点高。
   * 右端两个按钮：单独打开（当前这一屏开成新标签页）、全屏。
   */
  function phoneStatusRow() {
    var row = el('div', 'm-phone-status');
    row.appendChild(el('span', 'dot' + (connected ? ' ok' : ' bad')));
    row.appendChild(el('span', 'phone-status-text',
      phoneNotice || (connected ? ('已连接 · ' + ago(lastPush))
                                : (errNote || '未连接'))));
    row.appendChild(el('span', 'spacer'));

    var bid = phoneMode() === 'landscape' ? mobilePanel : 'messages';
    var pop = el('button', 'phone-btn', '单独打开');
    pop.type = 'button';
    pop.title = '在新标签页里单独打开「' + (TITLES[bid] || bid) + '」';
    pop.addEventListener('click', function () { popout(bid); });
    row.appendChild(pop);

    var fs = fullscreenButton();
    if (fs) {
      row.appendChild(fs);
    }
    return row;
  }

  /**
   * 手机版主界面 —— 只显示和安卓一致的内容，**比例也照安卓来**。
   *
   * 安卓的排布（F1MainActivity.buildLayout / buildBody）：
   *   旗语栏   固定 42dp 高
   *   竖屏     旗语栏 + [ 状态行 + 消息列表 ]
   *   横屏     旗语栏 + [ 状态行 + 消息列表 | 右侧面板 | 窄框图标条(38dp) ]
   *   左右两栏各 weight=1f（各一半），窄条固定 38dp；面板一次只显示一屏
   *
   * ★ 安卓**没有**顶部大标题行 —— 状态行是塞在左栏里的。手机屏就这么点高，
   *   照抄桌面版的头部 + 每块面板的标题行纯属浪费：我第一版就是这么做的，
   *   横屏 390px 里光旗语栏 86px、头部 80px，比例明显不对（用户一眼看出来）。
   */
  function renderPhoneMain(root, mode) {
    var wrap = el('div', 'phone phone-' + mode);

    // 旗语栏：只要那一条（42px，见 CSS），标题行和摘要行都不给
    var flags = el('div', 'm-flags');
    renderFlags(flags);
    wrap.appendChild(flags);

    var body = el('div', 'm-phone-body');

    var left = el('div', 'm-phone-left');
    left.appendChild(phoneStatusRow());
    var msgs = el('div', 'panel m-msgs m-phone-msgs');
    msgs.setAttribute('data-board', 'messages');
    left.appendChild(msgs);
    body.appendChild(left);

    var area = null;
    if (mode === 'landscape') {
      // 面板和左栏是**兄弟**（各 weight 1），窄条也是兄弟（固定 38px）——
      // 和安卓一样。要是把面板和窄条包在一起，左栏就比面板宽了。
      if (mobilePanel === 'ring') {
        // 圆环：标题在外、被测量的画布区在内（这个坑踩过一次）
        area = el('div', 'ring-area');
      } else {
        area = el('div', 'panel m-phone-box');
        area.setAttribute('data-board', mobilePanel);
      }
      body.appendChild(area);
      body.appendChild(phoneIconStrip());
    }
    wrap.appendChild(body);
    root.appendChild(wrap);

    // ★ 先入树再渲染（圆环要量盒子尺寸），和别处一致
    renderMessages(msgs);
    if (area) {
      RENDER[mobilePanel](area);
    }
  }

  // ---------------------------------------------------------------
  // 主界面：顶部旗语栏 + 左侧圆环 + 右侧 6 屏
  // ---------------------------------------------------------------

  function renderMain(root) {
    clear(root);

    // 手机：只显示和安卓同款的内容，**而且比例也照安卓**。
    // ★ 必须一开始就分叉：第一版我把手机版"追加"在桌面排布**后面**，
    //   于是手机上出现了两个旗语栏、上面还压着 80px 的头部（头部 + 旗语栏
    //   吃掉 390px 里的 43%）。用 CDP 量高度才看出来 —— 眼睛看截图只觉得"有点挤"。
    var phone = phoneMode();
    if (phone) {
      renderPhoneMain(root, phone);
      return;
    }

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
    // 只要有小窗弹出去，头部就给一个"一次全关"的出口。
    // 没有这个按钮时用户只能一个个点「收回」，或者去关窗口 —— 他反馈过。
    var all = popAllButton();
    if (all) {
      right.appendChild(all);
    }
    head.appendChild(right);

    root.appendChild(head);

    // 旗语栏也要能弹出去，所以套一层：标题栏在外、横条在内。
    // （renderFlags 会 clear 自己的容器，标题不能和它共用同一个容器）
    var flagsCol = el('div', 'm-flags-col');
    flagsCol.appendChild(titleRow(TITLES.flags, 'flags'));
    var flags = el('div', 'm-flags');
    renderFlags(flags);
    flagsCol.appendChild(flags);
    root.appendChild(flagsCol);

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

    // 手机上只显示安卓同款的内容（竖屏只看通报、横屏两栏），不走下面那套
    // "圆环 + 右侧 6 块全铺"的桌面排布。分叉在函数开头（见上）。

    var body = el('div', 'm-body');
    var left = el('div', 'm-left');

    // 圆环也一样：标题在外、正方形画布区在内。
    // renderRing 量的是"画布区"的短边，所以标题不能放在被测量的那个盒子里，
    // 否则量到的短边会少了标题那一行，圆会画得偏大。
    var ringCol = el('div', 'm-ring');
    ringCol.appendChild(titleRow(TITLES.ring, 'ring'));
    var ringArea = el('div', 'ring-area');
    ringCol.appendChild(ringArea);   // 画布区先入树，最后才画（见本函数末尾）
    left.appendChild(ringCol);

    var msgBox = el('div', 'panel m-msgs');
    msgBox.setAttribute('data-board', 'messages');
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

    // ★ 圆环必须等挂进文档之后再画，而且必须在最后。
    //   它是按"画布区的短边"定边长的 —— 盒子还不在文档里时 clientWidth/Height
    //   都是 0，只能走 320 的回退值。那不只是圆不圆的问题：因为每次重绘都会
    //   重建整棵树，这个 0 会被反复量到，圆环就"永远只有 320px"、周围一大片空。
    renderRing(ringArea);
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
    // 在小窗里也能一键收回所有小窗（包括自己这个）—— 省得回到主界面去点。
    var all = popAllButton();
    if (all) {
      head.appendChild(all);
    }
    root.appendChild(head);
    var box = el('div', 'b-box b-' + id);
    // ★ 先入树再渲染。
    //   反过来的话盒子还没有布局，clientWidth/Height 都是 0 ——
    //   圆环这类"按盒子算尺寸"的看板会永远按回退值画（320px），
    //   而且 ?once=1 快照模式下没有后续重绘来纠正。
    root.appendChild(box);
    RENDER[id](box);
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

  // 每 3 秒问一次"哪些看板弹出来了"：用户可能直接用窗口的 X 关掉，
  // 那样按钮上的"收回"要能自己变回"弹出"。
  setInterval(function () {
    if (!ONCE) { refreshPopped(); }
  }, 3000);

  // 窗口被拉大/拉小时立刻重画。不靠等下一次推送 —— 拖拽过程中
  // 圆环会明显看到"被拉扁"，哪怕只有一秒也很难看。
  var resizeTimer = null;
  window.addEventListener('resize', function () {
    if (resizeTimer) { clearTimeout(resizeTimer); }
    resizeTimer = setTimeout(function () { resizeTimer = null; repaint(); }, 120);
  });

  // 进出全屏也要重画：不仅是按钮上的字要换，圆环也得按新高度重新量一次。
  // （转屏会触发 resize，但全屏不一定 —— 两者都挂上。）
  var fsEvents = ['fullscreenchange', 'webkitfullscreenchange'];
  for (var fi = 0; fi < fsEvents.length; fi++) {
    document.addEventListener(fsEvents[fi], function () {
      if (resizeTimer) { clearTimeout(resizeTimer); }
      resizeTimer = setTimeout(function () { resizeTimer = null; repaint(); }, 120);
    });
  }

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
      .then(function () {
        refreshPopped();   // 首屏就问清楚"哪些看板已经弹出来了"
        if (!ONCE) { connect(); }
      });
  });
}());