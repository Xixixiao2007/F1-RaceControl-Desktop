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
python tools\make_dist.py             :: 打免安装 zip（构建 + 打包 + 列文件 + 算 SHA256）
```

`make_dist.py` 有一道清单核对：`F1BoardWindow.exe` / `.dll` / `.deps.json` /
`.runtimeconfig.json` / WebView2 的两个 managed DLL /
`runtimes/win-x64/native/WebView2Loader.dll` 缺任何一个都直接报错、不打包。
**这道检查是有用的**：第一版手写清单就漏了 `WebView2Loader.dll`
（它在 `runtimes/` 子目录下，不在根目录），而少它的后果是"窗口一闪就没"，
打包脚本本身不会报错。

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
页面可达、SSE 真的在推、弹出接口、以及下面两条不变量。

其中有一条**必须保留**的不变量检查：**任何字段都不许是被字符串化的 JSON**。
踩过一次 —— Java 侧 `Json.Obj.put(k, String)` 会给值加引号，于是
`new Json.Arr().done()` 的结果变成字符串 `'[]'` 而不是数组，前端一读就 TypeError，
而**接口依然全 200**、服务器日志干干净净，页面却一片空白。这类错误只能靠机器拦。

另一条不变量：**非回环来源不许让这台电脑开窗口**。局域网里任何设备发
`POST /api/popout/<id>` 都只能拿到 `mode:"tab"`（让它自己开新标签页）。
自检里用"从本机走局域网地址请求一次"来验证 —— 服务器看到的来源就不是回环。
这条坏了就是"同网段谁都能让你弹窗"。

窗口位置记忆单独有一个测试（因为它验证的是**强杀之后**还在不在，
而 `check_server.py` 那条路径是优雅关闭，测不到）：

```bat
python tools\test_window_memory.py 8720
```

它会用一个独立的 key（`testx`）开窗口、挪位置、强杀、再开一次，
确认位置回来了；跑完删掉自己的记录文件，不动你的。

还有一个测试验证"服务器没了，小窗自己关"，它必须走**真控制台窗口**这条路：

```bat
python tools\test_watchdog.py 8790
```

为什么不能用"起进程再 taskkill"来测：直接 `Popen` 出来的 java 没有自己的
控制台窗口（它继承了父进程的），`taskkill` 不带 `/F` 时**什么也发不出去**，
测试会假装通过。这个脚本用 `cmd.exe /c` + `CREATE_NEW_CONSOLE` 造一个真的
控制台窗口，再给它发 `WM_CLOSE` —— 和用户用鼠标点那个 X 是同一件事。

两条经验（都是踩出来的）：

- 服务器输出**要重定向到文件**（写进那个 `server.cmd` 里）。原来没有重定向，
  输出全在那个新开的控制台窗口里，测试一失败就完全没线索。
- "窗口还在（看门狗不误杀）"这一步会被**外部干扰**：测试窗和你自己开的真窗
  长得一模一样，有人顺手关掉它就会被判成失败。所以失败时会打印「服务器还健康吗」
  用来区分"看门狗误杀"和"被别的东西关掉"。

配套的探针 `tools\probe_hook.py` 用来回答一个更底层的问题：这条路和 Ctrl+C
到底谁会执行 JVM 的 shutdown hook。**实测结论：关控制台窗口不执行，Ctrl+C 执行。**
所以"关掉 cmd 之后小窗要跟着关"只能由窗口侧轮询服务器解决。留着这个探针是为了
防止将来有人把那段轮询当成多余的代码"简化"回退出钩子 —— 那样 bug 会原样回来。

还有一条**专盯看门狗门槛**的回归测试：

```bat
python tools\test_watchdog_early.py
```

它盯的是这个洞：看门狗原来写的是"必须连着成功过一次才开始计数"（防误杀），
但这条门槛**没有上限** —— 服务器要是在第一次成功探测之前就消失，计数永远不开始，
窗口就永远留着了。修法是"没见过成功"时用 10 次（约 30 秒）兜底。

脚本里那半确定性用例很关键：它把窗口指向一个**根本没有服务器**的端口，
于是"从未探到过成功"是必然的，不是碰运气；老代码在这里永远不倒。
（第一版我写的是"起实例后尽快杀服务器"，但那不保证赶上第一次探测之前 ——
碰运气的测试会时红时绿。）

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

### 两个踩过的渲染坑（改界面时务必注意）

**一、要测量尺寸的元素，必须先挂进文档再渲染。**
`renderMain` 和 `renderBoard` 的顺序都是先 `appendChild`、最后才画。
反过来的话元素还没布局，`clientWidth/clientHeight` 全是 `0` ——
圆环这类"按盒子短边算边长"的看板会拿不到真实尺寸，只能走 320px 的回退值。
更麻烦的是 `?once=1` 快照模式下没有后续重绘来纠正，错就一直错着。
（圆环里补了"量不到就下一帧重画"的保险，有次数上限，但那只是兜底，不是许可证。）

**二、canvas 的像素尺寸和 CSS 尺寸必须成固定比例，否则一定糊。**
原来的写法是 `cv.style.width = '100%'`（内容盒宽度）、而像素尺寸取自
`clientWidth`（含 padding），两者不等时 CSS 就会把画布拉伸 ——
拉伸即模糊，宽高比不一致还会把圆变成椭圆。
现在统一是：边长 = 画布区短边（保证正方形），CSS 用 **px 写死**，
像素尺寸 = 边长 × `devicePixelRatio`。想确认当前实际量到多少，
看画布上的 `data-fit="宽x高"` 属性 —— 这个属性当初就是把
`data-fit="0x0"` 一眼看出来才定位到上面的第一个坑的。

布局比例也归这里管：`.m-msgs` 是 `flex: 0 1 38%`（**不参与放大**），
剩余空间全给圆环。它原来是 `flex: 1 1 46%`，会和圆环平分剩余空间，
1600×900 下把圆环压到 144px。

## 手机版的排布

**唯一的依据是安卓**（`HA-F1-RaceControl/app/src/.../F1MainActivity.java`），
包括尺寸 —— 别自己发挥：

| 东西 | 安卓 | 网页版 |
| --- | --- | --- |
| 旗语栏高 | `dp(42)`（`buildLayout`） | 42px（`.phone .m-flags`） |
| 左栏 / 面板 | 各 `weight=1f`（各一半） | `flex: 1 1 0` 各一份，是**兄弟** |
| 窄框图标条 | 每个图标 `dp(38)` 宽 | 38px（`.m-strip`） |
| 顶部标题行 | 没有 | 没有（`.phone .ptitle { display: none }`） |
| 状态行 | 在左栏里（`buildStatusRow`） | 在左栏里（`.m-phone-status`） |

"是不是手机"和短边阈值（560）**只有 `app.js` 的 `phoneMode()` 一处**；
CSS 只负责长什么样。同一件事写在两个地方迟早会不一致。

两条踩过的坑：

1. **手机版要在 `renderMain` 开头就分叉返回**，不能"追加"在桌面排布后面。
   第一版是追加的，结果手机上有**两个旗语栏**、上面还压着 80px 的头部。
   注意：看截图只会觉得"有点挤"，是拿 `getBoundingClientRect` 量出来才发现的
   （旗语栏 86px 而不是 42px）。改布局时**量，不要看**。
2. 圆环那一屏的标题行必须在外、被测量的 `.ring-area` 在内，否则量到的短边
   少一行，圆会画得偏大。

量的工具：`dsh\tools\cdp_geo.mjs`（打 rect）+ `dsh\tools\check_phone_ratio.py`
（按上面那张表逐条断言）。切屏点击用 `dsh\tools\cdp_probe.mjs`。

## 原生窗口的构建

**UA 标记**：原生窗口在 WebView2 的 UA 尾巴上加 `F1RaceControlShell/1`，
服务器靠它区分"这一份页面跑在我们自己的窗口里"还是"跑在浏览器里"：

- `native\BoardWindow\Program.cs` 的 `ShellMarker` 常量
- `WebServer.SHELL_MARKER`（Java 侧）
- `tools\test_watchdog.py` 里的 `SHELL_UA`

**三处必须完全一致**，改一处忘另一处就会出现"弹出形态莫名其妙变了"。

为什么需要它：来源地址分不出"我们的窗口"和"本机浏览器"（都是回环），
而这两处用户的期望正好相反 —— 我们的窗口里点「弹出」要原生小窗，
浏览器里点「弹出」要网页标签页。注意它只回答"**想不想要**原生窗口"；
"**能不能**"仍然只看来源地址（回环），那是安全边界，不能靠客户端可以
随便改的 UA 字符串来定。

`F1BoardWindow.exe` **只有 151 KB 而且大小永远不变** —— 它是 .NET 的
apphost，只是个启动器；**真正的代码在 `F1BoardWindow.dll` 里**。
想确认"我改的代码到底进没进产物"，要搜 dll，别搜 exe：

```powershell
$b = [IO.File]::ReadAllBytes("native\BoardWindow\bin\Release\net9.0-windows\F1BoardWindow.dll")
# 字符串字面量是 UTF-16，方法名在元数据里是 UTF-8，两种都要搜
[Text.Encoding]::Unicode.GetString($b).Contains("/api/health")
```

（这个坑我踩过：改完代码搜 exe 搜不到符号，以为构建没生效，白查了一轮。
其实构建一直是好的。）

发布包（`tools/make_dist.py`）必须同时带上 `F1BoardWindow.exe` **和**
`F1BoardWindow.dll`、`F1BoardWindow.runtimeconfig.json`、`*.deps.json`
以及 `runtimes\win-x64\native\WebView2Loader.dll` —— 少了任何一个，
窗口会开不出来或者里面一片空白。

## 提交

提交信息写清楚"为什么改"。版本号在 `VERSION` 文件里，改动要同步更新
`CHANGELOG.md`，并保证 `DesktopMain.VERSION` 与 `VERSION` 一致。