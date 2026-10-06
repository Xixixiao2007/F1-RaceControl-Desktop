# 参与开发

## 最重要的一条规则：不要手改 `shared/`

`shared/` 里的源码**属于安卓仓库**，是由脚本同步过来的副本。直接改这里，
改动会在下次同步时被覆盖，而且安卓版和桌面版会悄悄不一致 —— 这正是这个项目
最想避免的事。

```bat
python tools\sync_shared.py           :: 从 ..\F1-RaceControl 重新同步
python tools\sync_shared.py --check   :: 校验 shared/ 与 PROVENANCE.json 一致
python tools\sync_shared.py --drift   :: 查上游安卓仓库有没有改动
```

要改 `F1Client` / `F1Feed` / `Translator` 这些，改**安卓仓库**，然后回来同步。
`shared/PROVENANCE.json` 里记着来源 commit 和每个文件的 SHA256，`--check` 会验这个。

桌面版自己的代码放 `src/com/haf1/racecontrol/desktop/`。

## 构建

```bat
python tools\build.py                 :: 编译 + 打 jar
python tools\build.py --native        :: 顺便编原生窗口
python tools\build.py --native --run -- --replay <某.rclog> --open
python tools\build.py --clean
python tools\build_native.py --publish  :: 自包含的原生窗口（免装 .NET）
```

产物：

- `build/F1-RaceControl-Desktop.jar` —— 自带 `web/` 界面资源的单文件
- `native/BoardWindow/bin/Release/net9.0-windows/F1BoardWindow.exe`

## 测试

```bat
:: 起一个回放服务器，然后跑接口自检
python tools\build.py --run -- --port 8720 --replay ..\F1-RaceControl\tools\mock_data\bahrain2026_race.rclog --no-window
python tools\check_server.py 8720
```

`check_server.py` 覆盖：健康检查、状态体积、车手/消息/区段数量、
中文翻译与回落、旗语栏几何合计必须为 1000‰、圆环角度必须等于 `F1Layout.ringStarts`、
页面可达、SSE 真的在推。

其中有一条**必须保留**的不变量检查：**任何字段都不许是被字符串化的 JSON**。
踩过一次 —— Java 侧 `Json.Obj.put(k, String)` 会给值加引号，于是
`new Json.Arr().done()` 的结果变成字符串 `'[]'` 而不是数组，前端一读就 TypeError，
而**接口依然全 200**、服务器日志干干净净，页面却一片空白。这类错误只能靠机器拦。

## 代码约定

- **Java 源码必须能在 Java 8 上编译**（安卓那套 JDK 就是 8）。具体地：
  - **不用 lambda**（安卓 6 也不安全），用匿名内部类
  - 不用 `ProcessBuilder.Redirect.DISCARD`（Java 9+）这类新 API
  - 不用 `var`、不用 `List.of`
- **注释里不要出现 `*/`**（会提前闭合块注释，整个文件编译失败，
  报的还是一堆"非法字符"，看着像编码问题）。同理 XML 注释里不能出现连续两个减号。
- 中文注释写"为什么"，不写"做了什么"。踩过的坑要写进去。
- 出错要**如实报**：降级就说降级，防火墙没加成就不说加成，不假装成功。

## 界面

`web/` 里只有一份实现，同时服务主界面、原生看板窗口、本机浏览器和局域网设备。
改一处所有地方都变，**不要**为原生窗口另写一套。

- `web/app.js` —— SSE 连接、状态存储、每个看板的渲染函数、主界面组装
- `web/style.css` —— 深色主题，`@media (max-width:820px)` 以下自动竖排
- `web/index.html` / `web/board.html` —— 两个壳

界面用的**几何和颜色都来自 Java 侧**（`F1Layout` 算的旗语栏分段与圆环角度、
`TrackState` 的 ARGB），不要在 JS 里另配一套，否则"和安卓同款"就守不住了。

## 提交

提交信息写清楚"为什么改"。版本号在 `VERSION` 文件里，改动要同步更新
`CHANGELOG.md`，并保证 `DesktopMain.VERSION` 与 `VERSION` 一致。