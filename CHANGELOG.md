# 更新日志

## 0.1.0 — 首个版本（2026-10-07）

第一个可用版本。功能对齐 [F1-RaceControl](https://github.com/Xixixiao2007/F1-RaceControl) 安卓版。

**数据层**
- 通过 `tools/sync_shared.py` 复用安卓版源码本体（`F1Client` / `F1Feed` / `TrackState` /
  `Translator` / `MessageStore` 等 14 个文件），来源 commit 与逐文件 SHA256 记录在
  `shared/PROVENANCE.json`；支持 `--check` 校验与 `--drift` 查上游改动。
- 支持实时流与 `.rclog` 回放（回放可调倍速，无需联网、无需等到比赛）。

**内置 Web 服务器**（JDK 自带 `com.sun.net.httpserver`，零第三方依赖）
- `/` 主界面、`/board/<id>` 单看板、`/api/state` 快照、`/api/events` SSE 推送、
  `/api/health`、`/api/boards`、`/api/full`。
- 默认绑 `0.0.0.0`，局域网与 iPhone 可直接访问。
- `?once=1` 静态快照模式：只拉一次不挂长连接。
- 下发的状态里，旗语栏分段宽度与圆环角度由安卓那份 `F1Layout` 计算，
  颜色为 `TrackState` 的 ARGB 值，避免网页与安卓在几何/配色上漂移。

**界面**
- 一份 HTML/JS 同时服务主界面、原生看板窗口、本机浏览器与局域网设备。
- 深色紧凑布局：顶部旗语栏 + 橙色通报横幅 + 左侧圆环与通报列表 + 右侧 6 个面板。
- 通报中文简述走 `Translator.gloss`；按契约翻不出来时回落显示英文原文，不显示空白。
- 页面内 `window.onerror` 把脚本错误直接显示出来（避免"接口全 200 但页面空白"这类静默失败）。

**独立看板窗口**
- 原生窗口程序 `F1BoardWindow.exe`（.NET 9 + WinForms + WebView2），独立的进程与顶层窗口，
  可自由移动、缩放，任务栏图标是本程序而非浏览器。
- 按看板 id 记住窗口位置与大小；`F11` 全屏、`Esc` 退出全屏；显示器变化导致位置出屏时自动拉回。
- 缺 .NET 运行时则降级为 Edge/Chrome 无地址栏窗口，并在控制台**明确标注降级**。

**局域网/防火墙**
- `--firewall` 检查并添加入站规则（仅专用/域网络），需要一次 UAC 提权。
- 用户拒绝提权或策略拦截时如实报出原因，并给出可直接粘贴的手动命令，不假装成功。

**测试与工具**
- `tools/check_server.py`：不依赖浏览器的接口自检，含"任何字段不许是被字符串化的 JSON"
  这条不变量与数组字段类型断言。
- `tools/sync_shared.py --check`：共享源码一致性校验。
- `tools/build_native.py`：原生窗口编译（nuget 直连失败时自动改走系统代理）。
- 已用真实巴林站数据端到端验证：22 位车手、19 个区段、中文翻译、
  三块原生窗口同时显示并各自挂上 SSE 推送。