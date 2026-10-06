// 看板原生窗口 —— 一个独立、可移动、可缩放的窗口，里面装一个 WebView2。
//
// 为什么不直接用 Edge 的 --app 窗口：用户要的是"我们自己程序的窗口"
// （任务栏图标、窗口类名都是 F1-RaceControl 的），而不是一个浏览器窗口。
//
// 这里**不重复实现界面**：窗口里加载的还是 Java 侧那一份 HTML/JS，
// 所以主界面、本机浏览器、局域网、iPhone 看到的永远是同一份渲染代码。
//
// 命令行：
//   F1BoardWindow.exe --url <地址> --title <标题> --key <看板id>
//                     [--width N] [--height N] [--x N] [--y N]
//
// 窗口位置和大小会按 --key 记住，下次打开同一块看板就回到原位 ——
// 八块看板摆一次就够了，不用每次重新拖。

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace F1BoardWindow
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            Args a;
            try
            {
                a = Args.Parse(args);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "F1-RaceControl 看板",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            if (a.Url == null)
            {
                MessageBox.Show(
                    "缺少 --url 参数。\r\n\r\n" +
                    "这个程序由 F1-RaceControl-Desktop 自动拉起，一般不需要手动启动。",
                    "F1-RaceControl 看板", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new BoardForm(a));
        }
    }

    internal sealed class BoardForm : Form
    {
        private readonly Args _a;
        private readonly WebView2 _view;
        private bool _fullscreen;
        private Rectangle _restoreBounds;
        private FormBorderStyle _restoreStyle;
        private FormWindowState _restoreState;

        internal BoardForm(Args a)
        {
            _a = a;

            Text = a.Title ?? "F1 Race Control";
            // 深色底：WebView2 内容到位之前不要闪一下白屏，
            // 和网页背景 #0d1117 对齐。
            BackColor = Color.FromArgb(13, 17, 23);
            MinimumSize = new Size(280, 180);
            StartPosition = FormStartPosition.Manual;
            Bounds = WindowMemory.Resolve(a);
            KeyPreview = true;

            _view = new WebView2
            {
                Dock = DockStyle.Fill,
                DefaultBackgroundColor = Color.FromArgb(13, 17, 23)
            };
            Controls.Add(_view);

            Load += OnLoadAsync;
            FormClosing += OnClosing;
        }

        private async void OnLoadAsync(object sender, EventArgs e)
        {
            try
            {
                // 每块看板一个自己的用户数据目录：
                // WebView2 用同一个目录时，多个实例会共享同一个浏览器进程，
                // 窗口之间会互相影响（一个卡住全卡住）。各用各的最稳。
                CoreWebView2Environment env = await CoreWebView2Environment.CreateAsync(
                    null, WindowMemory.UserDataDir(_a.Key));
                await _view.EnsureCoreWebView2Async(env);

                CoreWebView2Settings s = _view.CoreWebView2.Settings;
                s.AreDefaultContextMenusEnabled = false;   // 看板不需要右键菜单
                s.IsStatusBarEnabled = false;
                s.AreDevToolsEnabled = _a.DevTools;
                s.IsZoomControlEnabled = true;             // 允许 Ctrl+滚轮缩放
                s.IsPinchZoomEnabled = true;
                s.AreBrowserAcceleratorKeysEnabled = true;

                _view.CoreWebView2.Navigate(_a.Url);
            }
            catch (Exception ex)
            {
                // ★ WebView2 运行时缺失是最可能的原因，必须说清楚，
                //   否则用户只看到一片深色，不知道少了什么。
                ShowFatal(
                    "WebView2 初始化失败。\r\n\r\n" +
                    "Windows 上通常自带 WebView2 运行时；如果没有，装一个即可：\r\n" +
                    "https://developer.microsoft.com/microsoft-edge/webview2/\r\n\r\n" +
                    "原始错误：\r\n" + ex.Message);
            }
        }

        private void ShowFatal(string msg)
        {
            Controls.Clear();
            var box = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                Dock = DockStyle.Fill,
                BackColor = Color.FromArgb(13, 17, 23),
                ForeColor = Color.FromArgb(230, 237, 243),
                BorderStyle = BorderStyle.None,
                Font = new Font("Microsoft YaHei UI", 10f),
                Text = msg
            };
            Controls.Add(box);
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            // F11 全屏：挂在电视/副屏上时有用（窗口本身仍有边框，可随时拖）
            if (keyData == Keys.F11)
            {
                ToggleFullscreen();
                return true;
            }
            if (keyData == Keys.Escape && _fullscreen)
            {
                ToggleFullscreen();
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        private void ToggleFullscreen()
        {
            if (!_fullscreen)
            {
                _restoreBounds = Bounds;
                _restoreStyle = FormBorderStyle;
                _restoreState = WindowState;
                WindowState = FormWindowState.Normal;
                FormBorderStyle = FormBorderStyle.None;
                Bounds = Screen.FromControl(this).Bounds;
            }
            else
            {
                FormBorderStyle = _restoreStyle;
                Bounds = _restoreBounds;
                WindowState = _restoreState;
            }
            _fullscreen = !_fullscreen;
        }

        private void OnClosing(object sender, FormClosingEventArgs e)
        {
            // 记住位置/大小。全屏状态下不记 —— 否则下次打开就变成全屏了。
            if (!_fullscreen)
            {
                WindowMemory.Save(_a.Key, WindowState == FormWindowState.Normal
                    ? Bounds
                    : RestoreBounds);
            }
            else
            {
                WindowMemory.Save(_a.Key, _restoreBounds);
            }
        }
    }

    /// <summary>记住每块看板的窗口位置和大小。</summary>
    internal static class WindowMemory
    {
        private static string Root
        {
            get
            {
                string local = Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData);
                return Path.Combine(local, "F1-RaceControl-Desktop");
            }
        }

        internal static string UserDataDir(string key)
        {
            return Path.Combine(Root, "webview2", Safe(key));
        }

        private static string FileFor(string key)
        {
            return Path.Combine(Root, "windows", Safe(key) + ".json");
        }

        private static string Safe(string key)
        {
            if (string.IsNullOrEmpty(key))
            {
                return "default";
            }
            var b = new System.Text.StringBuilder();
            foreach (char c in key)
            {
                b.Append(char.IsLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
            }
            return b.Length == 0 ? "default" : b.ToString();
        }

        /// <summary>
        /// 定出窗口初始位置。优先级：
        /// 命令行显式给的位置 > 上次记住的位置 > 命令行给的尺寸 > 默认。
        /// </summary>
        internal static Rectangle Resolve(Args a)
        {
            Rectangle remembered = Load(a.Key);
            int w = a.Width > 0 ? a.Width : (remembered.Width > 0 ? remembered.Width : 620);
            int h = a.Height > 0 ? a.Height : (remembered.Height > 0 ? remembered.Height : 420);
            int x, y;
            if (a.X >= 0 && a.Y >= 0)
            {
                x = a.X;
                y = a.Y;
            }
            else if (remembered.Width > 0)
            {
                x = remembered.X;
                y = remembered.Y;
            }
            else
            {
                x = 0;
                y = 0;
            }

            var r = new Rectangle(x, y, w, h);
            // 显示器拔了/分辨率变了，上次的位置可能在屏幕外 ——
            // 那样窗口会"打开但看不见"，比位置不对更让人困惑。
            if (!VisibleOnAnyScreen(r))
            {
                Rectangle wa = Screen.PrimaryScreen.WorkingArea;
                r = new Rectangle(
                    Math.Max(wa.X, Math.Min(r.X, wa.Right - 120)),
                    Math.Max(wa.Y, Math.Min(r.Y, wa.Bottom - 80)),
                    Math.Min(r.Width, wa.Width),
                    Math.Min(r.Height, wa.Height));
            }
            return r;
        }

        private static bool VisibleOnAnyScreen(Rectangle r)
        {
            foreach (Screen s in Screen.AllScreens)
            {
                Rectangle hit = Rectangle.Intersect(s.WorkingArea, r);
                // 至少要有一块像样的可见区域，不能只露几个像素
                if (hit.Width >= 120 && hit.Height >= 60)
                {
                    return true;
                }
            }
            return false;
        }

        private static Rectangle Load(string key)
        {
            try
            {
                string p = FileFor(key);
                if (!File.Exists(p))
                {
                    return Rectangle.Empty;
                }
                using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(p));
                JsonElement r = doc.RootElement;
                return new Rectangle(
                    r.GetProperty("x").GetInt32(),
                    r.GetProperty("y").GetInt32(),
                    r.GetProperty("w").GetInt32(),
                    r.GetProperty("h").GetInt32());
            }
            catch
            {
                return Rectangle.Empty;   // 记的东西坏了就当没记，不影响打开
            }
        }

        internal static void Save(string key, Rectangle r)
        {
            try
            {
                if (r.Width <= 0 || r.Height <= 0)
                {
                    return;
                }
                string p = FileFor(key);
                Directory.CreateDirectory(Path.GetDirectoryName(p));
                var d = new Dictionary<string, object>
                {
                    ["x"] = r.X, ["y"] = r.Y, ["w"] = r.Width, ["h"] = r.Height
                };
                File.WriteAllText(p, JsonSerializer.Serialize(d,
                    new JsonSerializerOptions { WriteIndented = true }));
            }
            catch
            {
                // 记不住位置不算错误，别因此影响使用
            }
        }
    }

    internal sealed class Args
    {
        internal string Url;
        internal string Title;
        internal string Key = "default";
        internal int Width;
        internal int Height;
        internal int X = -1;
        internal int Y = -1;
        internal bool DevTools;

        internal static Args Parse(string[] args)
        {
            var a = new Args();
            for (int i = 0; i < args.Length; i++)
            {
                string k = args[i];
                switch (k)
                {
                    case "--url": a.Url = Next(args, ref i, k); break;
                    case "--title": a.Title = Next(args, ref i, k); break;
                    case "--key": a.Key = Next(args, ref i, k); break;
                    case "--width": a.Width = Int(Next(args, ref i, k), k); break;
                    case "--height": a.Height = Int(Next(args, ref i, k), k); break;
                    case "--x": a.X = Int(Next(args, ref i, k), k); break;
                    case "--y": a.Y = Int(Next(args, ref i, k), k); break;
                    case "--devtools": a.DevTools = true; break;
                    default:
                        throw new ArgumentException("不认识的参数：" + k);
                }
            }
            return a;
        }

        private static string Next(string[] args, ref int i, string flag)
        {
            if (i + 1 >= args.Length)
            {
                throw new ArgumentException(flag + " 后面要跟一个值");
            }
            return args[++i];
        }

        private static int Int(string s, string flag)
        {
            if (!int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out int v))
            {
                throw new ArgumentException(flag + " 需要一个整数，收到：" + s);
            }
            return v;
        }
    }
}