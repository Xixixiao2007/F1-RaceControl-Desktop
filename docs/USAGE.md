# 使用说明

## 一条命令启动

```bat
python tools\build.py --native --run -- --open --all-boards
```

- `--native` 顺便编译看板原生窗口（第一次要联网取 WebView2 包）
- `--run` 编译完直接启动
- `--` 之后是传给程序的参数

启动后控制台会打印：

```
F1-Race Control 桌面版 0.1.0
  界面目录 : ...\web
  数据源   : 官方公开流（实时）
  本机     : http://127.0.0.1:8720/
  局域网   : http://192.168.1.103:8720/   <- 手机 / iPad 用这个
  防火墙   : 未处理（加 --firewall 自动加规则；手机连不上多半是这里）
  窗口程序 : 原生 F1BoardWindow.exe
```

`Ctrl+C` 退出。

## 全部参数

| 参数 | 说明 |
| --- | --- |
| `--port N` | 监听端口，默认 `8720`；`0` 表示随便挑一个空闲端口 |
| `--web DIR` | 界面资源目录，默认自动找 `web/`（找不到就用 jar 里内置的） |
| `--open` | 启动时打开主界面窗口 |
| `--board a,b,c` | 把指定看板拉成独立窗口 |
| `--all-boards` | 打开全部 9 块看板 |
| `--list-boards` | 列出看板 id 后退出 |
| `--board-exe PATH` | 指定 `F1BoardWindow.exe` 路径（默认自动找） |
| `--firewall` | 自动加 Windows 防火墙入站规则（会弹一次 UAC） |
| `--firewall-check` | 只检查防火墙状态和端口监听，不改动、不提权 |
| `--replay FILE` | 用 `.rclog` 回放文件，不连网 |
| `--speed N` | 回放倍速，默认 `60` |
| `--main-size WxH` | 主窗口尺寸，默认 `1600x900` |
| `--board-size WxH` | 看板窗口尺寸，默认 `620x420` |
| `--no-window` | 只起服务器，不开窗口 |
| `-h`, `--help` | 帮助 |

## 看板窗口

每块看板都是一个**独立的原生窗口**：可以随便拖动、拉伸缩放，任务栏里各自有图标，
最小化/关闭互不影响，就像微信/QQ 的窗口一样。

窗口位置和大小**会按看板 id 记住**，下次打开回到原位 —— 八块摆一次就够了。
想手动改记住的位置，命令行给的 `--x/--y/--width/--height` 优先于记忆。

快捷键：`F11` 全屏，`Esc` 退出全屏。

### 八块看板建议摆法

```
python tools\build.py --run -- --board flags,ring,track,tyres,timing,weather,fastest,session
```

默认按 3 列网格铺开（每块 620×420），起点在屏幕左上角。

**窗口会不会互相压着？** 会，而且这在单屏 1080p 上无法避免：主界面
1600×900 加上几块看板，面积本来就超过一屏。所以实际用法是二选一 ——

- 只想看总览：只开主界面（`--open`）
- 想让某几块面板一直显示：把那几块拉出来（`--board tyres,timing`），
  主界面留在后面当底图，需要时点任务栏调出来

先摆一次位置就好：**每个窗口的位置和大小都会按看板 id 记住**
（存在 `%LOCALAPPDATA%\F1-RaceControl-Desktop\windows\`），下次打开回到原位。
想重新排，删掉那个目录里的对应 json 即可。

## 手机上用（含 iPhone）

1. 电脑上启动时带上 `--firewall`（第一次）：
   ```bat
   python tools\build.py --run -- --firewall --open
   ```
   会弹一次 UAC 问你要不要允许，点"是"。
2. 手机连**同一个 Wi-Fi**，浏览器打开控制台打印的那个局域网地址，例如
   `http://192.168.1.103:8720/`
3. iPhone 上可以「共享 → 添加到主屏幕」，之后像 App 一样从桌面图标打开。

界面是自适应的：窄屏自动从"左右分栏"变成"上下堆叠"，手机上竖着看也能用。

### 手机打不开时

先跑一次自查（**不提权、不改任何设置**）：

```bat
启动.cmd --firewall-check
```

它会报三件事，对应三种完全不同的修法：

```
  防火墙检查: 规则名 F1-RaceControl-Desktop 不存在      ← 防火墙没放行
             端口 8720 未被覆盖
             本机监听 正常                              ← 服务器这边是好的
```

| 看到什么 | 说明 | 怎么办 |
| --- | --- | --- |
| 本机监听 ★ 没在监听 | 服务器/端口的问题，跟防火墙无关 | 换端口，或看服务器是不是启动失败 |
| 规则不存在 / 端口未被覆盖 | 防火墙没放行 | 加 `--firewall`，或用下面那条 netsh |
| 两项都正常手机还是打不开 | 问题在网络，不在本机 | 往下看第 1、4、5 条 |

按这个顺序查（**本机能打开、手机打不开，九成是第 2 条**）：

1. 手机和电脑是不是同一个网段（电脑上 `ipconfig` 看 IPv4 地址）
2. 防火墙有没有放行：管理员 PowerShell 里跑
   ```bat
   netsh advfirewall firewall show rule name=F1-RaceControl-Desktop
   ```
   没有就加：
   ```bat
   netsh advfirewall firewall add rule name=F1-RaceControl-Desktop dir=in action=allow protocol=TCP localport=8720 profile=private,domain
   ```
3. 电脑上先自己验证服务器活着：
   ```
   http://127.0.0.1:8720/api/health
   ```
   应该返回 `{"ok":true,...}`
4. 路由器有没有开「AP 隔离 / 客户端隔离」—— 开了的话同一 Wi-Fi 下的设备也互相不通
5. 公司/学校的网络可能禁止设备互访

## 没有比赛的时候

```bat
python tools\build.py --run -- --replay ..\F1-RaceControl\tools\mock_data\bahrain2026_race.rclog --speed 200 --open --all-boards
```

`.rclog` 也能直接用安卓版的数据，回放会按原速的 N 倍把整场比赛"重演"一遍，
用来调布局、试看板摆放、验证手机能不能连，都不需要等比赛。

默认只放一遍，放完停在最后一帧。

## 故障排查

**窗口开不出来 / 任务栏图标是浏览器**
说明没找到 `F1BoardWindow.exe`，程序会明确打印「降级用 Edge/Chrome」。装 .NET 运行时后：

```bat
python tools\build_native.py
```

**窗口里显示「WebView2 初始化失败」**
缺 WebView2 运行时。Win11 和较新的 Win10 自带；没有的话装一个：
<https://developer.microsoft.com/microsoft-edge/webview2/>

**页面一片空白，但接口正常**
现在页面会把脚本错误直接显示在底部红条上。没有红条却空白，就在地址后面加 `?once=1`
看是不是长连接的问题。

**端口被占用**
换一个：`--port 8800`。用 `--port 0` 让系统随便挑也行，实际端口会在启动信息里打印。

**想看原始数据**
`http://127.0.0.1:8720/api/full` 是所有合并后的原始流（很大）；
`/api/state` 是界面实际用的那一份派生状态。

## 界面上的每一块

| 面板 | 内容 |
| --- | --- |
| 顶部旗语栏 | 按区段显示黄旗/双黄旗/安全车等，无旗语时显示整条绿（全赛道畅通） |
| 通报横幅 | 最新一条赛事通报，有中文用中文，没有就用英文原文 |
| 车手圆环 | 环形显示各区段状态，圆心是当前总结；角度由安卓那份 `F1Layout` 计算 |
| 赛道图 | 当前旗语级别、涉及区段、TrackStatus 原始值 |
| 轮胎 / 进站 | 3 列 × 8 行共 24 格，按赛道位置排，显示配方、胎龄、进站次数 |
| 成绩榜 | 名次、车手、差距、间隔、配方、圈数、进站 |
| 天气 | 气温、赛道温、天气描述 |
| 个人最快圈 | 按最快圈排序 |
| 环节 | 会议、赛道、环节、状态、剩余时间、圈数、前三名 |
| 赛事通报 | 最近 60 条，鼠标悬停看英文原文与 F1 官方 UTC 时间戳 |