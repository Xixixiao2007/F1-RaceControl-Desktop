# F1-RaceControl-Desktop

F1 赛事控制消息的 **Windows 客户端**，功能和 [F1-RaceControl](https://github.com/Xixixiao2007/F1-RaceControl)（安卓版）一致：
直连 F1 官方公开数据流，不需要 Home Assistant、不需要令牌。

在安卓版的基础上多了三件事：

1. **看板能拉成独立窗口** —— 右侧 6 个分屏各自可以变成一个独立的原生窗口，同时摆在屏幕上；
2. **内置 Web 服务器** —— 手机、平板、iPhone 打开浏览器就能看，界面和 Windows 上完全一样；
3. **一站式处理防火墙** —— 一条命令加好入站规则，局域网直接能连。

```
                          ┌──────────────────────────────┐
   F1 官方公开流 ───────► │  数据层（复用安卓版源码）      │
   （SignalR Core）        │  F1Client / F1Feed / TrackState│
                          │  Translator / MessageStore …  │
                          └──────────────┬───────────────┘
                                         │ 同一份状态
                          ┌──────────────▼───────────────┐
                          │  Web 服务器（JDK 自带，零依赖）│
                          │  /api/state  /api/events(SSE) │
                          └───┬──────────┬───────────┬───┘
                              │          │           │
                     原生看板窗口    本机浏览器    局域网 / iPhone
                   （WebView2）    （同一份页面）（同一份页面）
```

## 快速开始

**下载解压即用**（不需要编译）：[最新发布包](https://github.com/Xixixiao2007/F1-RaceControl-Desktop/releases/latest) —— 解压后双击 `启动.cmd`。
只需要装过 Java；没有 .NET 运行时也能用，看板窗口会降级成 Edge 无地址栏窗口。

从源码跑：

```bat
python tools\build.py --native --run -- --open --all-boards
```

这条命令会：编译 → 编原生窗口程序 → 启动 → 打开主界面和全部看板窗口。

启动后控制台会打印两个地址，其中**局域网那个是给手机用的**：

```
  本机     : http://127.0.0.1:8720/
  局域网   : http://192.168.1.103:8720/   <- 手机 / iPad 用这个
```

想在手机上用，第一次要放行防火墙：

```bat
python tools\build.py --run -- --firewall
```

（会弹一次 UAC；不加也行，但手机多半连不上而本机一切正常。）

## 没有比赛的时候也能看

内置回放模式，用 `.rclog` 文件演练界面，不联网：

```bat
python tools\build.py --run -- --replay ..\F1-RaceControl\tools\mock_data\bahrain2026_race.rclog --speed 200 --open --all-boards
```

## 看板

| id | 名称 |
| --- | --- |
| `flags` | 顶部旗语栏 |
| `ring` | 车手圆环（区段状态） |
| `track` | 赛道图 |
| `tyres` | 轮胎 / 进站（3 列 × 8 行） |
| `timing` | 成绩榜 |
| `weather` | 天气 |
| `fastest` | 个人最快圈 |
| `session` | 环节 |
| `messages` | 赛事通报 |

拉出独立窗口：

```bat
--board tyres,timing,ring        :: 只要这三块
--all-boards                     :: 全部
--list-boards                    :: 列出 id
```

窗口位置和大小**会被记住**（按看板 id 存在 `%LOCALAPPDATA%\F1-RaceControl-Desktop\windows\`），
摆一次就够了。`F11` 全屏，`Esc` 退出全屏。

## 功能一致是怎么保证的

不是"照着安卓重写一遍"，而是**同一份源码编译两遍**：

`tools/sync_shared.py` 把安卓仓库的 14 个产品源码 + 7 个测试桩同步进 `shared/`，
并在 `shared/PROVENANCE.json` 里钉住来源 commit 和每个文件的 SHA256。
`F1Client`、`F1Feed`、`TrackState`、`Translator`、`MessageStore` 全都是安卓那份本体。

界面上也是同样的思路：旗语栏的分段位置、圆环的角度都直接调用安卓那份 `F1Layout` 计算后下发，
颜色也是安卓 `TrackState` 算好的 ARGB 值 —— **网页不另配一套色板或几何**，否则"同款 UI"从这一层就开始漂。

```bat
python tools\sync_shared.py --check    :: 校验 shared/ 与 PROVENANCE 一致
python tools\sync_shared.py --drift    :: 查上游安卓仓库有没有改动
```

## 环境要求

| 组件 | 要求 | 缺了会怎样 |
| --- | --- | --- |
| JDK | 8 或更高 | 编译/运行不了 |
| .NET 运行时 | 9（WinForms） | 退化成 Edge 无地址栏窗口，任务栏图标是浏览器 |
| WebView2 运行时 | Win11 与较新 Win10 自带 | 窗口能开但里面显示错误提示 |

## 已知限制

- **只验证过回放数据**，还没在真实比赛时段跑过实时流。
- **防火墙自动加规则要弹 UAC**，所以没法在无人值守时验证；代码里对"用户点了否"是如实报错，不假装成功。
- **看板不是逐行移植安卓的绘制代码**：结构相同（同样的面板划分）、颜色和几何来自安卓，但不是像素级复刻。
- 看板窗口各自带一套浏览器进程，八块全开内存占用不小。
- 服务器退出后看板窗口会留在原地显示"连接断了"（重开服务器会自己接上）。

## 文档

- [使用说明](docs/USAGE.md) —— 全部命令行参数、手机接入、故障排查
- [参与开发](docs/CONTRIBUTING.md) —— 共享源码同步规则、构建与测试
- [更新日志](CHANGELOG.md)