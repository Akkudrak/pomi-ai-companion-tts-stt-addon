// Pomi Voz: lee en voz alta lo que Pomi dice en el servidor (plugin Pomi para Paper) y la deja oír al
// jugador. El jugador escribe /p voz en el juego, y aquí pone la dirección y el código que le salen. La
// app se queda vinculada (guarda un token), escucha las frases de Pomi (Server-Sent Events en /eventos)
// y reproduce cada una con el audio que el servidor pide a Kokoro (/audio/<id>, PCM 24 kHz mono).
// Con "Escuchar" activado oye el micrófono todo el tiempo, corta lo que el jugador dice por volumen y lo
// manda a /escuchar; el servidor lo pasa por Whisper y, si empieza con "Pomi" ("oye Pomi, consigue
// madera"), lo ejecuta como /p. No hay tecla: solo la palabra de activación.
// Se compila con el csc de Windows (.NET Framework 4, C# 5): ver crear-app.ps1.

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Media;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Pomi Voz")]
[assembly: System.Reflection.AssemblyProduct("Pomi Voz")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace PomiVoz
{
    /// <summary>
    /// El micrófono por defecto de Windows (waveIn), sin librerías: 16 bits mono, con varios búferes que
    /// se van llenando y se leen por sondeo.
    /// </summary>
    sealed class Microphone : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)]
        struct WaveFormat
        {
            public ushort Tag, Channels;
            public uint SampleRate, BytesPerSecond;
            public ushort BlockAlign, BitsPerSample, ExtraSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WaveHeader
        {
            public IntPtr Data;
            public uint BufferLength, BytesRecorded;
            public IntPtr User;
            public uint Flags, Loops;
            public IntPtr Next, Reserved;
        }

        const uint WaveMapper = 0xFFFFFFFF;
        const uint Done = 1;
        const int Buffers = 8;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct WaveInCaps
        {
            public ushort Mid, Pid;
            public uint DriverVersion;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
            public string Name;
            public uint Formats;
            public ushort Channels, Reserved;
        }

        [DllImport("winmm.dll")] static extern int waveInGetNumDevs();
        [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveInGetDevCapsW")]
        static extern int waveInGetDevCaps(UIntPtr device, ref WaveInCaps caps, int size);
        [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle, uint device, ref WaveFormat format, IntPtr callback, IntPtr instance, uint flags);
        [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle, IntPtr header, int size);
        [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle, IntPtr header, int size);
        [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle, IntPtr header, int size);
        [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
        [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
        [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);

        static readonly int HeaderSize = Marshal.SizeOf(typeof(WaveHeader));
        readonly IntPtr handle;
        readonly IntPtr[] headers = new IntPtr[Buffers];
        int next;
        /// <summary>Lo que quedó de un búfer después de sacar cuadros completos.</summary>
        readonly MemoryStream pending = new MemoryStream();

        /// <summary>Los micrófonos de Windows, en el orden de sus números (Windows corta los nombres a 31 letras).</summary>
        public static List<string> Devices()
        {
            List<string> names = new List<string>();
            int count = waveInGetNumDevs();
            for (int i = 0; i < count; i++)
            {
                WaveInCaps caps = new WaveInCaps();
                names.Add(waveInGetDevCaps((UIntPtr) (uint) i, ref caps, Marshal.SizeOf(typeof(WaveInCaps))) == 0
                        ? caps.Name : "Micrófono " + (i + 1));
            }
            return names;
        }

        /// <param name="name">El micrófono por su nombre; vacío o si ya no está, el predeterminado de Windows.</param>
        public Microphone(string name, int rate, int bufferBytes)
        {
            uint device = WaveMapper;
            if (!string.IsNullOrEmpty(name))
            {
                int index = Devices().IndexOf(name);
                if (index >= 0)
                {
                    device = (uint) index;
                }
            }
            WaveFormat format = new WaveFormat();
            format.Tag = 1;
            format.Channels = 1;
            format.SampleRate = (uint) rate;
            format.BitsPerSample = 16;
            format.BlockAlign = 2;
            format.BytesPerSecond = (uint) rate * 2;
            int result = waveInOpen(out handle, device, ref format, IntPtr.Zero, IntPtr.Zero, 0);
            if (result != 0)
            {
                throw new InvalidOperationException("waveInOpen devolvió " + result);
            }
            for (int i = 0; i < Buffers; i++)
            {
                WaveHeader header = new WaveHeader();
                header.Data = Marshal.AllocHGlobal(bufferBytes);
                header.BufferLength = (uint) bufferBytes;
                headers[i] = Marshal.AllocHGlobal(HeaderSize);
                Marshal.StructureToPtr(header, headers[i], false);
                waveInPrepareHeader(handle, headers[i], HeaderSize);
                waveInAddBuffer(handle, headers[i], HeaderSize);
            }
            waveInStart(handle);
        }

        /// <summary>Llena el cuadro con el siguiente pedazo de audio. False si todavía no hay (ya esperó un poco).</summary>
        public bool Read(byte[] frame)
        {
            while (pending.Length < frame.Length)
            {
                WaveHeader header = (WaveHeader) Marshal.PtrToStructure(headers[next], typeof(WaveHeader));
                if ((header.Flags & Done) == 0)
                {
                    Thread.Sleep(10);
                    return false;
                }
                byte[] data = new byte[header.BytesRecorded];
                Marshal.Copy(header.Data, data, 0, data.Length);
                pending.Seek(0, SeekOrigin.End);
                pending.Write(data, 0, data.Length);
                waveInUnprepareHeader(handle, headers[next], HeaderSize);
                header.BytesRecorded = 0;
                header.Flags = 0;
                Marshal.StructureToPtr(header, headers[next], false);
                waveInPrepareHeader(handle, headers[next], HeaderSize);
                waveInAddBuffer(handle, headers[next], HeaderSize);
                next = (next + 1) % Buffers;
            }
            byte[] all = pending.ToArray();
            Buffer.BlockCopy(all, 0, frame, 0, frame.Length);
            pending.SetLength(0);
            pending.Write(all, frame.Length, all.Length - frame.Length);
            return true;
        }

        public void Dispose()
        {
            waveInReset(handle);
            for (int i = 0; i < Buffers; i++)
            {
                waveInUnprepareHeader(handle, headers[i], HeaderSize);
                WaveHeader header = (WaveHeader) Marshal.PtrToStructure(headers[i], typeof(WaveHeader));
                Marshal.FreeHGlobal(header.Data);
                Marshal.FreeHGlobal(headers[i]);
            }
            waveInClose(handle);
        }
    }

    /// <summary>
    /// Visor del micrófono: el nivel de los últimos segundos en barritas (gris = ruido, verde = voz que
    /// se va a mandar, azul = Pomi hablando, el micrófono no cuenta) y la línea del umbral de voz.
    /// </summary>
    sealed class VoiceMeter : Control
    {
        public const byte Noise = 0, Voice = 1, PomiTalking = 2;
        const int History = 150;
        /// <summary>Más fuerte que esto se está saturando (se oirá mal).</summary>
        const double Clipping = 28000;

        readonly float[] levels = new float[History];
        readonly byte[] kinds = new byte[History];
        readonly System.Windows.Forms.Timer refresh = new System.Windows.Forms.Timer();
        int newest;
        double threshold = 700;
        bool dirty;

        public VoiceMeter()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint
                    | ControlStyles.ResizeRedraw, true);
            refresh.Interval = 40;
            refresh.Tick += delegate
            {
                if (dirty)
                {
                    dirty = false;
                    Invalidate();
                }
            };
            refresh.Start();
        }

        /// <summary>Un cuadro de 20 ms. Se llama desde el hilo del micrófono.</summary>
        public void Push(double level, double threshold, byte kind)
        {
            lock (levels)
            {
                newest = (newest + 1) % History;
                levels[newest] = (float) level;
                kinds[newest] = kind;
                this.threshold = threshold;
            }
            dirty = true;
        }

        public void Clear()
        {
            lock (levels)
            {
                Array.Clear(levels, 0, History);
            }
            dirty = true;
        }

        /// <summary>Escala logarítmica, como se oye: 0 = silencio, 1 = lo más fuerte posible.</summary>
        static float Scale(double level)
        {
            return (float) Math.Max(0, Math.Min(1, Math.Log10(Math.Max(level, 1)) / Math.Log10(32768)));
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.Clear(Color.FromArgb(28, 30, 34));
            int height = ClientSize.Height;
            float barWidth = (float) ClientSize.Width / History;
            float limit;
            bool clipping = false;
            using (SolidBrush noise = new SolidBrush(Color.FromArgb(110, 115, 125)))
            using (SolidBrush voice = new SolidBrush(Color.FromArgb(80, 200, 110)))
            using (SolidBrush pomi = new SolidBrush(Color.FromArgb(80, 140, 220)))
            {
                lock (levels)
                {
                    for (int i = 0; i < History; i++)
                    {
                        int index = (newest + 1 + i) % History;
                        float bar = Scale(levels[index]) * (height - 2);
                        if (bar < 1)
                        {
                            continue;
                        }
                        Brush brush = kinds[index] == Voice ? voice : kinds[index] == PomiTalking ? pomi : noise;
                        g.FillRectangle(brush, i * barWidth, height - bar, Math.Max(1, barWidth - 1), bar);
                    }
                    clipping = levels[newest] > Clipping;
                    limit = height - Scale(threshold) * (height - 2);
                }
            }
            using (Pen line = new Pen(Color.FromArgb(230, 190, 70)))
            {
                line.DashStyle = System.Drawing.Drawing2D.DashStyle.Dash;
                g.DrawLine(line, 0, limit, ClientSize.Width, limit);
            }
            using (SolidBrush text = new SolidBrush(Color.FromArgb(230, 190, 70)))
            {
                g.DrawString("umbral de voz", Font, text, 4, Math.Max(0, limit - Font.Height - 1));
            }
            if (clipping)
            {
                using (SolidBrush red = new SolidBrush(Color.FromArgb(230, 80, 70)))
                {
                    g.DrawString("¡muy fuerte! baja el volumen", Font, red, ClientSize.Width - 190, 2);
                }
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                refresh.Dispose();
            }
            base.Dispose(disposing);
        }
    }

    /// <summary>Lo que la app recuerda entre sesiones (%APPDATA%\PomiVoz\config.txt).</summary>
    sealed class Settings
    {
        public string Server = "";
        public string Token = "";
        public string Player = "";
        public decimal Pitch = 1.25m;
        public int Volume = 80;
        public bool Muted;
        public bool Listening = true;
        /// <summary>Nombre del micrófono; vacío = el predeterminado de Windows.</summary>
        public string Microphone = "";
        /// <summary>Volumen del micrófono en %, aplicado a las muestras (100 = tal cual).</summary>
        public int Gain = 100;
        /// <summary>Tecla para hablar (código virtual de Windows; 0 = ninguna).</summary>
        public int Key;

        static string FilePath
        {
            get
            {
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PomiVoz", "config.txt");
            }
        }

        public static Settings Load()
        {
            Settings settings = new Settings();
            if (!File.Exists(FilePath))
            {
                return settings;
            }
            foreach (string line in File.ReadAllLines(FilePath, Encoding.UTF8))
            {
                int eq = line.IndexOf('=');
                if (eq <= 0)
                {
                    continue;
                }
                string key = line.Substring(0, eq).Trim();
                string value = line.Substring(eq + 1).Trim();
                decimal number;
                switch (key)
                {
                    case "servidor": settings.Server = value; break;
                    case "token": settings.Token = value; break;
                    case "jugador": settings.Player = value; break;
                    case "tono":
                        if (decimal.TryParse(value, System.Globalization.NumberStyles.Number, System.Globalization.CultureInfo.InvariantCulture, out number))
                        {
                            settings.Pitch = Math.Max(0.8m, Math.Min(1.6m, number));
                        }
                        break;
                    case "volumen":
                        if (decimal.TryParse(value, out number))
                        {
                            settings.Volume = (int) Math.Max(0, Math.Min(100, number));
                        }
                        break;
                    case "silencio": settings.Muted = value == "true"; break;
                    case "escuchar": settings.Listening = value != "false"; break;
                    case "microfono": settings.Microphone = value; break;
                    case "tecla":
                        if (decimal.TryParse(value, out number))
                        {
                            settings.Key = (int) Math.Max(0, Math.Min(255, number));
                        }
                        break;
                    case "ganancia":
                        if (decimal.TryParse(value, out number))
                        {
                            settings.Gain = (int) Math.Max(0, Math.Min(400, number));
                        }
                        break;
                }
            }
            return settings;
        }

        public void Save()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
            File.WriteAllLines(FilePath, new string[] {
                "servidor=" + Server,
                "token=" + Token,
                "jugador=" + Player,
                "tono=" + Pitch.ToString(System.Globalization.CultureInfo.InvariantCulture),
                "volumen=" + Volume,
                "silencio=" + (Muted ? "true" : "false"),
                "escuchar=" + (Listening ? "true" : "false"),
                "microfono=" + Microphone,
                "ganancia=" + Gain,
                "tecla=" + Key,
            }, Encoding.UTF8);
        }
    }

    sealed class MainForm : Form
    {
        /// <summary>Kokoro da PCM de 16 bits, mono, a 24 kHz.</summary>
        const int KokoroRate = 24000;
        /// <summary>Si se juntan más frases que esto, se saltan las viejas para no quedarse atrás.</summary>
        const int MaxQueued = 3;

        [DllImport("winmm.dll")]
        static extern int waveOutSetVolume(IntPtr device, uint volume);

        readonly Settings settings = Settings.Load();
        readonly BlockingCollection<KeyValuePair<long, string>> queue = new BlockingCollection<KeyValuePair<long, string>>();
        readonly TextBox serverBox = new TextBox();
        readonly TextBox codeBox = new TextBox();
        readonly Button linkButton = new Button();
        readonly Button unlinkButton = new Button();
        readonly Label status = new Label();
        readonly NumericUpDown pitchBox = new NumericUpDown();
        readonly TrackBar volumeBar = new TrackBar();
        readonly CheckBox muteBox = new CheckBox();
        readonly ListBox log = new ListBox();
        readonly CheckBox listenBox = new CheckBox();
        [DllImport("user32.dll")]
        static extern short GetAsyncKeyState(int key);

        readonly Button keyButton = new Button();
        /// <summary>Esperando a que el jugador presione la tecla nueva.</summary>
        bool capturingKey;
        readonly ComboBox micBox = new ComboBox();
        readonly TrackBar gainBar = new TrackBar();
        readonly Label gainText = new Label();
        readonly VoiceMeter meter = new VoiceMeter();
        readonly Label hearState = new Label();
        /// <summary>Se cambió de micrófono: el hilo del oído lo vuelve a abrir.</summary>
        volatile bool micChanged;
        /// <summary>Frases mandadas a Whisper que aún no responden.</summary>
        int understanding;
        string shownHearState = "";
        /// <summary>Mientras Pomi habla (y un momento después) no se oye el micrófono, para no oírse a sí misma.</summary>
        volatile bool speaking;
        long quietSince;
        readonly NotifyIcon tray = new NotifyIcon();
        /// <summary>Sube cada vez que cambia el vínculo, para que la conexión vieja se dé por terminada.</summary>
        int generation;
        HttpWebRequest openStream;
        volatile bool closing;

        public MainForm()
        {
            Text = "Pomi Voz";
            Font = new Font("Segoe UI", 9.5f);
            ClientSize = new Size(460, 660);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            Icon = SystemIcons.Information;

            TableLayoutPanel grid = new TableLayoutPanel();
            grid.Dock = DockStyle.Fill;
            grid.Padding = new Padding(12);
            grid.ColumnCount = 3;
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 80));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
            Controls.Add(grid);

            grid.Controls.Add(Caption("Servidor"), 0, 0);
            serverBox.Dock = DockStyle.Fill;
            serverBox.Text = settings.Server;
            grid.Controls.Add(serverBox, 1, 0);
            grid.SetColumnSpan(serverBox, 2);

            grid.Controls.Add(Caption("Código"), 0, 1);
            codeBox.Dock = DockStyle.Fill;
            codeBox.CharacterCasing = CharacterCasing.Upper;
            codeBox.MaxLength = 6;
            grid.Controls.Add(codeBox, 1, 1);
            linkButton.Text = "Vincular";
            linkButton.Dock = DockStyle.Fill;
            linkButton.Click += delegate { Link(); };
            grid.Controls.Add(linkButton, 2, 1);
            AcceptButton = linkButton;

            status.Dock = DockStyle.Fill;
            status.AutoSize = false;
            status.Height = 40;
            status.TextAlign = ContentAlignment.MiddleLeft;
            grid.Controls.Add(status, 0, 2);
            grid.SetColumnSpan(status, 2);
            unlinkButton.Text = "Desvincular";
            unlinkButton.Dock = DockStyle.Fill;
            unlinkButton.Click += delegate { Unlink("Desvinculada."); };
            grid.Controls.Add(unlinkButton, 2, 2);

            grid.Controls.Add(Caption("Tono"), 0, 3);
            pitchBox.Minimum = 0.8m;
            pitchBox.Maximum = 1.6m;
            pitchBox.Increment = 0.05m;
            pitchBox.DecimalPlaces = 2;
            pitchBox.Value = settings.Pitch;
            pitchBox.Width = 70;
            pitchBox.ValueChanged += delegate { settings.Pitch = pitchBox.Value; settings.Save(); };
            grid.Controls.Add(pitchBox, 1, 3);
            muteBox.Text = "Silenciar";
            muteBox.Checked = settings.Muted;
            muteBox.CheckedChanged += delegate { settings.Muted = muteBox.Checked; settings.Save(); };
            grid.Controls.Add(muteBox, 2, 3);

            grid.Controls.Add(Caption("Volumen"), 0, 4);
            volumeBar.Minimum = 0;
            volumeBar.Maximum = 100;
            volumeBar.TickFrequency = 10;
            volumeBar.Value = settings.Volume;
            volumeBar.Dock = DockStyle.Fill;
            volumeBar.ValueChanged += delegate { settings.Volume = volumeBar.Value; ApplyVolume(); settings.Save(); };
            grid.Controls.Add(volumeBar, 1, 4);
            grid.SetColumnSpan(volumeBar, 2);

            grid.Controls.Add(Caption("Micrófono"), 0, 5);
            micBox.Dock = DockStyle.Fill;
            micBox.DropDownStyle = ComboBoxStyle.DropDownList;
            FillMicrophones();
            // Recién conectado (audífonos, USB): se ve al abrir la lista.
            micBox.DropDown += delegate { FillMicrophones(); };
            micBox.SelectionChangeCommitted += delegate
            {
                settings.Microphone = micBox.SelectedIndex <= 0 ? "" : (string) micBox.SelectedItem;
                settings.Save();
                micChanged = true;
            };
            grid.Controls.Add(micBox, 1, 5);
            listenBox.Text = "Con \"Pomi\"";
            listenBox.Checked = settings.Listening;
            listenBox.CheckedChanged += delegate { settings.Listening = listenBox.Checked; settings.Save(); };
            grid.Controls.Add(listenBox, 2, 5);

            grid.Controls.Add(Caption("Vol. mic"), 0, 6);
            gainBar.Minimum = 0;
            gainBar.Maximum = 400;
            gainBar.TickFrequency = 50;
            gainBar.SmallChange = 10;
            gainBar.LargeChange = 50;
            gainBar.Value = settings.Gain;
            gainBar.Dock = DockStyle.Fill;
            gainBar.ValueChanged += delegate
            {
                settings.Gain = gainBar.Value;
                gainText.Text = settings.Gain + " %";
                settings.Save();
            };
            grid.Controls.Add(gainBar, 1, 6);
            gainText.Text = settings.Gain + " %";
            gainText.AutoSize = true;
            gainText.Anchor = AnchorStyles.Left;
            grid.Controls.Add(gainText, 2, 6);

            grid.Controls.Add(Caption("Tecla"), 0, 7);
            keyButton.Dock = DockStyle.Fill;
            keyButton.Height = 30;
            keyButton.Text = KeyText();
            keyButton.Click += delegate
            {
                capturingKey = true;
                keyButton.Text = "Presiona una tecla o botón lateral del mouse (Esc = ninguna)";
            };
            // Botones del mouse: el izquierdo y el derecho no, que se usan para todo.
            keyButton.MouseDown += (sender, e) =>
            {
                int vk = e.Button == MouseButtons.XButton1 ? 0x05 : e.Button == MouseButtons.XButton2 ? 0x06
                        : e.Button == MouseButtons.Middle ? 0x04 : 0;
                if (capturingKey && vk != 0)
                {
                    SetKey(vk);
                }
            };
            keyButton.LostFocus += delegate
            {
                if (capturingKey)
                {
                    capturingKey = false;
                    keyButton.Text = KeyText();
                }
            };
            grid.Controls.Add(keyButton, 1, 7);
            grid.SetColumnSpan(keyButton, 2);

            meter.Dock = DockStyle.Fill;
            meter.Height = 80;
            meter.Font = new Font("Segoe UI", 8f);
            grid.Controls.Add(meter, 0, 8);
            grid.SetColumnSpan(meter, 3);
            hearState.Dock = DockStyle.Fill;
            hearState.AutoSize = false;
            hearState.Height = 24;
            hearState.TextAlign = ContentAlignment.MiddleLeft;
            hearState.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            grid.Controls.Add(hearState, 0, 9);
            grid.SetColumnSpan(hearState, 3);

            log.Dock = DockStyle.Fill;
            log.HorizontalScrollbar = true;
            grid.Controls.Add(log, 0, 10);
            grid.SetColumnSpan(log, 3);
            grid.RowCount = 11;
            for (int i = 0; i < 10; i++)
            {
                grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            }
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

            tray.Icon = SystemIcons.Information;
            tray.Text = "Pomi Voz";
            tray.DoubleClick += delegate { Show(); WindowState = FormWindowState.Normal; Activate(); };
            Resize += delegate
            {
                if (WindowState == FormWindowState.Minimized)
                {
                    Hide();
                    tray.Visible = true;
                }
                else
                {
                    tray.Visible = false;
                }
            };

            ApplyVolume();
            Thread speaker = new Thread(Speak);
            speaker.IsBackground = true;
            speaker.Start();
            Thread ears = new Thread(Hear);
            ears.IsBackground = true;
            ears.Start();
            if (settings.Token.Length > 0)
            {
                Connect();
            }
            else
            {
                SetStatus("Escribe /p voz en el juego y pon aquí la dirección y el código.");
            }
            UpdateButtons();
        }

        static Label Caption(string text)
        {
            Label label = new Label();
            label.Text = text;
            label.AutoSize = true;
            label.Anchor = AnchorStyles.Left;
            return label;
        }

        /// <summary>Mientras se espera la tecla nueva, la primera que se presione es la tecla para hablar.</summary>
        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (!capturingKey)
            {
                return base.ProcessCmdKey(ref msg, keyData);
            }
            Keys key = keyData & Keys.KeyCode;
            SetKey(key == Keys.Escape ? 0 : (int) key);
            return true;
        }

        void SetKey(int vk)
        {
            capturingKey = false;
            settings.Key = vk;
            settings.Save();
            keyButton.Text = KeyText();
            ActiveControl = null;
        }

        string KeyText()
        {
            return settings.Key == 0 ? "Sin tecla (clic para elegir una)" : "Mantén " + KeyName(settings.Key) + " para hablar (clic para cambiarla)";
        }

        static string KeyName(int vk)
        {
            switch (vk)
            {
                case 0x04: return "el botón de en medio del mouse";
                case 0x05: return "el botón lateral 1 del mouse";
                case 0x06: return "el botón lateral 2 del mouse";
                case 0x10: return "Shift";
                case 0x11: return "Ctrl";
                case 0x12: return "Alt";
                case 0x14: return "Bloq Mayús";
                case 0x20: return "Espacio";
                case 0xC0: return "Ñ";
            }
            string name = ((Keys) vk).ToString();
            if (name.Length == 2 && name[0] == 'D' && char.IsDigit(name[1]))
            {
                name = name.Substring(1);
            }
            return name.StartsWith("Oem") ? "la tecla " + name : name;
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            closing = true;
            tray.Visible = false;
            AbortStream();
            base.OnFormClosing(e);
        }

        // ---- Vínculo ----------------------------------------------------------------------------

        string BaseUrl
        {
            get
            {
                string server = settings.Server.Trim().TrimEnd('/');
                return server.StartsWith("http://") || server.StartsWith("https://") ? server : "http://" + server;
            }
        }

        void Link()
        {
            string server = serverBox.Text.Trim();
            string code = codeBox.Text.Trim();
            if (server.Length == 0 || code.Length == 0)
            {
                SetStatus("Falta la dirección o el código. Los da /p voz en el juego.");
                return;
            }
            settings.Server = server;
            linkButton.Enabled = false;
            SetStatus("Vinculando...");
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    HttpWebRequest request = (HttpWebRequest) WebRequest.Create(BaseUrl + "/vincular");
                    request.Method = "POST";
                    request.Timeout = 10000;
                    byte[] body = Encoding.UTF8.GetBytes(code);
                    request.ContentType = "text/plain; charset=utf-8";
                    request.ContentLength = body.Length;
                    using (Stream output = request.GetRequestStream())
                    {
                        output.Write(body, 0, body.Length);
                    }
                    string answer = ReadAll(request);
                    string token = Field(answer, "token");
                    string player = Field(answer, "jugador");
                    Invoke((MethodInvoker) delegate
                    {
                        settings.Token = token;
                        settings.Player = player;
                        settings.Save();
                        codeBox.Text = "";
                        Connect();
                        UpdateButtons();
                    });
                }
                catch (Exception e)
                {
                    string message = ErrorText(e);
                    Invoke((MethodInvoker) delegate
                    {
                        SetStatus("No se pudo vincular: " + message);
                        UpdateButtons();
                    });
                }
            });
        }

        void Unlink(string why)
        {
            settings.Token = "";
            settings.Player = "";
            settings.Save();
            generation++;
            AbortStream();
            SetStatus(why + " Escribe /p voz en el juego para vincular de nuevo.");
            UpdateButtons();
        }

        void UpdateButtons()
        {
            bool linked = settings.Token.Length > 0;
            linkButton.Enabled = true;
            unlinkButton.Enabled = linked;
        }

        // ---- Frases (Server-Sent Events) --------------------------------------------------------

        void Connect()
        {
            generation++;
            AbortStream();
            int mine = generation;
            string url = BaseUrl + "/eventos";
            string token = settings.Token;
            Thread listener = new Thread(delegate () { Listen(mine, url, token); });
            listener.IsBackground = true;
            listener.Start();
        }

        void Listen(int mine, string url, string token)
        {
            while (!closing && mine == generation)
            {
                UiStatus(mine, "Conectando con " + settings.Server + "...");
                try
                {
                    HttpWebRequest request = (HttpWebRequest) WebRequest.Create(url);
                    request.Headers["Authorization"] = "Bearer " + token;
                    request.Timeout = 10000;
                    // El servidor manda un ping cada 20 s: si pasa más de un minuto sin nada, se cayó.
                    request.ReadWriteTimeout = 60000;
                    openStream = request;
                    using (HttpWebResponse response = (HttpWebResponse) request.GetResponse())
                    using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                    {
                        UiStatus(mine, "Conectada" + (settings.Player.Length > 0 ? " como " + settings.Player : "") + ". Pomi habla aquí.");
                        long id = 0;
                        StringBuilder data = new StringBuilder();
                        string line;
                        while (!closing && mine == generation && (line = reader.ReadLine()) != null)
                        {
                            if (line.Length == 0)
                            {
                                if (id > 0 && data.Length > 0)
                                {
                                    Heard(id, data.ToString());
                                }
                                id = 0;
                                data.Clear();
                            }
                            else if (line.StartsWith("id:"))
                            {
                                long.TryParse(line.Substring(3).Trim(), out id);
                            }
                            else if (line.StartsWith("data:"))
                            {
                                data.Append(line.Substring(5).TrimStart());
                            }
                        }
                    }
                }
                catch (WebException e)
                {
                    HttpWebResponse response = e.Response as HttpWebResponse;
                    if (response != null && response.StatusCode == HttpStatusCode.Unauthorized)
                    {
                        if (mine == generation)
                        {
                            BeginInvoke((MethodInvoker) delegate { Unlink("El servidor ya no reconoce esta app."); });
                        }
                        return;
                    }
                    UiStatus(mine, "Sin conexión (" + ErrorText(e) + "). Reintentando...");
                }
                catch (Exception e)
                {
                    UiStatus(mine, "Sin conexión (" + ErrorText(e) + "). Reintentando...");
                }
                for (int i = 0; i < 50 && !closing && mine == generation; i++)
                {
                    Thread.Sleep(100);
                }
            }
        }

        void AbortStream()
        {
            HttpWebRequest request = openStream;
            openStream = null;
            if (request != null)
            {
                try
                {
                    request.Abort();
                }
                catch (Exception)
                {
                }
            }
        }

        void Heard(long id, string text)
        {
            UiLog("Pomi: " + text);
            while (queue.Count >= MaxQueued)
            {
                KeyValuePair<long, string> skipped;
                queue.TryTake(out skipped);
            }
            queue.Add(new KeyValuePair<long, string>(id, text));
        }

        // ---- Voz --------------------------------------------------------------------------------

        void Speak()
        {
            foreach (KeyValuePair<long, string> line in queue.GetConsumingEnumerable())
            {
                if (settings.Muted || settings.Token.Length == 0)
                {
                    continue;
                }
                try
                {
                    HttpWebRequest request = (HttpWebRequest) WebRequest.Create(BaseUrl + "/audio/" + line.Key);
                    request.Headers["Authorization"] = "Bearer " + settings.Token;
                    request.Timeout = 30000;
                    byte[] pcm;
                    using (HttpWebResponse response = (HttpWebResponse) request.GetResponse())
                    using (Stream input = response.GetResponseStream())
                    using (MemoryStream buffer = new MemoryStream())
                    {
                        input.CopyTo(buffer);
                        pcm = buffer.ToArray();
                    }
                    if (pcm.Length < 2)
                    {
                        continue;
                    }
                    // Tocarlo más rápido sube el tono, como hacía el mod (1.25 = Pomi).
                    int rate = (int) Math.Round(KokoroRate * (double) settings.Pitch);
                    speaking = true;
                    try
                    {
                        using (MemoryStream wav = new MemoryStream(Wav(pcm, rate)))
                        using (SoundPlayer player = new SoundPlayer(wav))
                        {
                            player.PlaySync();
                        }
                    }
                    finally
                    {
                        speaking = false;
                        quietSince = DateTime.UtcNow.Ticks;
                    }
                }
                catch (Exception e)
                {
                    string message = ErrorText(e);
                    UiLog("(sin voz: " + message + ")");
                }
            }
        }

        // ---- Oído (micrófono → /escuchar) -------------------------------------------------------

        const int MicRate = 16000;
        const int FrameMs = 20;
        const int FrameBytes = MicRate * 2 * FrameMs / 1000;
        /// <summary>Audio de justo antes de que empiece la voz, para no cortar la primera sílaba.</summary>
        const int PreRollFrames = 15;
        /// <summary>Cuadros fuertes seguidos que empiezan una frase (filtra golpes y clics).</summary>
        const int StartFrames = 3;
        /// <summary>Silencio que termina una frase (0.7 s).</summary>
        const int EndSilenceFrames = 35;
        const int MaxFrames = 10000 / FrameMs;
        const int MinFrames = 400 / FrameMs;
        /// <summary>Lo más que se graba con la tecla: 15 s (lo que acepta el servidor).</summary>
        const int MaxKeyBytes = 15 * MicRate * 2;
        /// <summary>Volumen mínimo que cuenta como voz (además de 3 veces el ruido de fondo).</summary>
        const double MinThreshold = 700;
        /// <summary>Tras hablar Pomi, se ignora el micrófono este tiempo (el eco de la bocina).</summary>
        const long EchoTicks = 400 * TimeSpan.TicksPerMillisecond;

        void Hear()
        {
            Microphone mic = null;
            byte[] frame = new byte[FrameBytes];
            Queue<byte[]> preRoll = new Queue<byte[]>();
            MemoryStream utterance = new MemoryStream();
            bool inSpeech = false;
            int loud = 0, quiet = 0, frames = 0;
            double noise = 0;
            MemoryStream keyRecording = new MemoryStream();
            bool recordingKey = false;
            string micError = null;
            while (!closing)
            {
                // The mic is on for the name, the key, or both.
                bool wanted = settings.Token.Length > 0 && (settings.Listening || settings.Key != 0);
                if (mic != null && (micChanged || !wanted))
                {
                    mic.Dispose();
                    mic = null;
                    inSpeech = false;
                    recordingKey = false;
                    preRoll.Clear();
                    noise = 0;
                    meter.Clear();
                }
                if (micChanged)
                {
                    micError = null;
                }
                micChanged = false;
                if (!wanted)
                {
                    SetHearState(settings.Token.Length == 0 ? "Micrófono apagado: primero vincula la app."
                            : "Micrófono apagado: activa \"Con Pomi\" o elige una tecla.", Color.Gray);
                    Thread.Sleep(200);
                    continue;
                }
                if (mic == null)
                {
                    try
                    {
                        mic = new Microphone(settings.Microphone, MicRate, FrameBytes * 5);
                        micError = null;
                    }
                    catch (Exception e)
                    {
                        if (micError != e.Message)
                        {
                            micError = e.Message;
                            UiLog("(no se pudo abrir el micrófono: " + e.Message + ". Revisa que Windows deje usarlo a las apps de escritorio)");
                        }
                        SetHearState("No se pudo abrir el micrófono.", Color.Firebrick);
                        Thread.Sleep(3000);
                        continue;
                    }
                }
                if (!mic.Read(frame))
                {
                    continue;
                }
                Amplify(frame, settings.Gain / 100.0);
                double level = Rms(frame);
                double threshold = Math.Max(MinThreshold, noise * 3.0);

                // Talk key: record while held (even over Pomi's voice), send on release, no name needed.
                bool keyDown = settings.Key != 0 && (GetAsyncKeyState(settings.Key) & 0x8000) != 0;
                if (keyDown)
                {
                    if (!recordingKey)
                    {
                        recordingKey = true;
                        keyRecording.SetLength(0);
                        inSpeech = false;
                        preRoll.Clear();
                    }
                    if (keyRecording.Length < MaxKeyBytes)
                    {
                        keyRecording.Write(frame, 0, frame.Length);
                    }
                    meter.Push(level, threshold, VoiceMeter.Voice);
                    SetHearState("● Grabando... suelta la tecla para mandarlo.", Color.ForestGreen);
                    continue;
                }
                if (recordingKey)
                {
                    recordingKey = false;
                    if (keyRecording.Length >= MinFrames * FrameBytes)
                    {
                        SendInBackground(keyRecording.ToArray(), true);
                    }
                    continue;
                }
                if (!settings.Listening)
                {
                    meter.Push(level, threshold, VoiceMeter.Noise);
                    SetHearState(Interlocked.CompareExchange(ref understanding, 0, 0) > 0 ? "Entendiendo lo que dijiste..."
                            : "Mantén " + KeyName(settings.Key) + " para hablarle a Pomi.", Color.DimGray);
                    continue;
                }
                if (speaking || DateTime.UtcNow.Ticks - quietSince < EchoTicks)
                {
                    meter.Push(level, threshold, VoiceMeter.PomiTalking);
                    SetHearState("Pomi está hablando (no te oigo mientras).", Color.SteelBlue);
                    inSpeech = false;
                    preRoll.Clear();
                    loud = 0;
                    continue;
                }
                meter.Push(level, threshold, inSpeech || level > threshold ? VoiceMeter.Voice : VoiceMeter.Noise);
                if (inSpeech)
                {
                    SetHearState("● Te oigo...", Color.ForestGreen);
                }
                else if (Interlocked.CompareExchange(ref understanding, 0, 0) > 0)
                {
                    SetHearState("Entendiendo lo que dijiste...", Color.DarkOrange);
                }
                else
                {
                    SetHearState("Escuchando. Di \"Pomi, baila\" o \"Pomi\" y luego la orden.", Color.DimGray);
                }
                if (!inSpeech)
                {
                    noise = noise == 0 ? level : noise * 0.98 + level * 0.02;
                    preRoll.Enqueue((byte[]) frame.Clone());
                    if (preRoll.Count > PreRollFrames)
                    {
                        preRoll.Dequeue();
                    }
                    loud = level > threshold ? loud + 1 : 0;
                    if (loud >= StartFrames)
                    {
                        inSpeech = true;
                        utterance.SetLength(0);
                        frames = preRoll.Count;
                        foreach (byte[] earlier in preRoll)
                        {
                            utterance.Write(earlier, 0, earlier.Length);
                        }
                        preRoll.Clear();
                        quiet = 0;
                    }
                }
                else
                {
                    utterance.Write(frame, 0, frame.Length);
                    frames++;
                    quiet = level < threshold * 0.6 ? quiet + 1 : 0;
                    if (quiet >= EndSilenceFrames || frames >= MaxFrames)
                    {
                        inSpeech = false;
                        loud = 0;
                        if (frames - quiet >= MinFrames)
                        {
                            SendInBackground(utterance.ToArray(), false);
                        }
                    }
                }
            }
            if (mic != null)
            {
                mic.Dispose();
            }
        }

        void SendInBackground(byte[] said, bool fromKey)
        {
            Interlocked.Increment(ref understanding);
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    Send(said, fromKey);
                }
                finally
                {
                    Interlocked.Decrement(ref understanding);
                }
            });
        }

        /// <summary>Manda una frase al servidor y muestra qué entendió. Con tecla no hace falta decir "Pomi".</summary>
        void Send(byte[] pcm, bool fromKey)
        {
            try
            {
                HttpWebRequest request = (HttpWebRequest) WebRequest.Create(BaseUrl + "/escuchar" + (fromKey ? "?tecla=1" : ""));
                request.Method = "POST";
                request.Headers["Authorization"] = "Bearer " + settings.Token;
                request.ContentType = "application/octet-stream";
                request.ContentLength = pcm.Length;
                request.Timeout = 30000;
                using (Stream output = request.GetRequestStream())
                {
                    output.Write(pcm, 0, pcm.Length);
                }
                string answer = ReadAll(request);
                string state = Field(answer, "estado");
                string text = Field(answer, "texto");
                if (state == "escuchando")
                {
                    Chime();
                    UiLog("● Pomi te escucha... (" + text + ")");
                }
                else if (state == "enviado")
                {
                    UiLog("Tú: " + Field(answer, "orden"));
                }
                else if (fromKey)
                {
                    UiLog(text.Length == 0 ? "(no te entendí: no se oyó nada claro, revisa el visor y el volumen del micrófono)"
                            : "(oí \"" + text + "\", pero el servidor no lo tomó como orden: ¿tiene el plugin actualizado?)");
                }
                else if (text.Length > 0)
                {
                    UiLog("(oí \"" + text + "\", pero no empezó con Pomi)");
                }
            }
            catch (Exception e)
            {
                WebException web = e as WebException;
                HttpWebResponse response = web == null ? null : web.Response as HttpWebResponse;
                if (response != null && response.StatusCode == HttpStatusCode.NotFound)
                {
                    UiLog("(el plugin Pomi del servidor es una versión sin oído: actualízalo para usar órdenes de voz)");
                }
                else
                {
                    UiLog("(no te entendí: " + ErrorText(e) + ")");
                }
            }
        }

        static double Rms(byte[] pcm)
        {
            long sum = 0;
            for (int i = 0; i + 1 < pcm.Length; i += 2)
            {
                int sample = (short) (pcm[i] | (pcm[i + 1] << 8));
                sum += (long) sample * sample;
            }
            return Math.Sqrt(sum / (pcm.Length / 2.0));
        }

        /// <summary>Volumen del micrófono: multiplica las muestras, sin pasarse del máximo.</summary>
        static void Amplify(byte[] pcm, double gain)
        {
            if (Math.Abs(gain - 1.0) < 0.01)
            {
                return;
            }
            for (int i = 0; i + 1 < pcm.Length; i += 2)
            {
                int sample = (int) ((short) (pcm[i] | (pcm[i + 1] << 8)) * gain);
                sample = Math.Max(short.MinValue, Math.Min(short.MaxValue, sample));
                pcm[i] = (byte) sample;
                pcm[i + 1] = (byte) (sample >> 8);
            }
        }

        /// <summary>El texto bajo el visor; solo toca la ventana si cambió.</summary>
        void SetHearState(string text, Color color)
        {
            if (text == shownHearState || closing || !IsHandleCreated)
            {
                return;
            }
            shownHearState = text;
            BeginInvoke((MethodInvoker) delegate
            {
                hearState.Text = text;
                hearState.ForeColor = color;
            });
        }

        void FillMicrophones()
        {
            micBox.Items.Clear();
            micBox.Items.Add("Predeterminado de Windows");
            foreach (string name in Microphone.Devices())
            {
                micBox.Items.Add(name);
            }
            int selected = settings.Microphone.Length == 0 ? 0 : micBox.Items.IndexOf(settings.Microphone);
            if (selected < 0)
            {
                // Desconectado: se queda en la lista para que se vea cuál era, pero se usa el predeterminado.
                selected = micBox.Items.Add(settings.Microphone);
            }
            micBox.SelectedIndex = selected;
        }

        /// <summary>Dos notitas: "te escucho".</summary>
        static void Chime()
        {
            int rate = 22050;
            byte[] pcm = new byte[rate * 2 * 24 / 100];
            for (int i = 0; i < pcm.Length / 2; i++)
            {
                double t = (double) i / rate;
                double frequency = t < 0.12 ? 880 : 1320;
                double fade = Math.Min(1.0, Math.Min(t, 0.24 - t) * 40);
                short sample = (short) (Math.Sin(2 * Math.PI * frequency * t) * 6000 * fade);
                pcm[i * 2] = (byte) sample;
                pcm[i * 2 + 1] = (byte) (sample >> 8);
            }
            using (SoundPlayer player = new SoundPlayer(new MemoryStream(Wav(pcm, rate))))
            {
                player.PlaySync();
            }
        }

        static readonly object transcriptLock = new object();

        /// <summary>
        /// Agrega la línea a la conversación del día ("conversacion-2026-10-19.txt" junto al .exe; si ahí no
        /// se puede escribir, en %APPDATA%\PomiVoz). Nunca sobreescribe: si el archivo del día ya existe, sigue
        /// al final.
        /// </summary>
        static void SaveToTranscript(string text)
        {
            DateTime now = DateTime.Now;
            string name = "conversacion-" + now.ToString("yyyy-MM-dd") + ".txt";
            string line = now.ToString("HH:mm:ss") + "  " + text + Environment.NewLine;
            lock (transcriptLock)
            {
                try
                {
                    File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, name), line, Encoding.UTF8);
                }
                catch (Exception)
                {
                    try
                    {
                        string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PomiVoz");
                        Directory.CreateDirectory(folder);
                        File.AppendAllText(Path.Combine(folder, name), line, Encoding.UTF8);
                    }
                    catch (Exception)
                    {
                        // Sin dónde guardar: la conversación sigue en la ventana.
                    }
                }
            }
        }

        void UiLog(string text)
        {
            SaveToTranscript(text);
            if (closing || !IsHandleCreated)
            {
                return;
            }
            BeginInvoke((MethodInvoker) delegate
            {
                log.Items.Insert(0, DateTime.Now.ToString("HH:mm") + "  " + text);
                while (log.Items.Count > 100)
                {
                    log.Items.RemoveAt(log.Items.Count - 1);
                }
            });
        }

        /// <summary>Encabezado WAV para PCM de 16 bits mono.</summary>
        static byte[] Wav(byte[] pcm, int rate)
        {
            using (MemoryStream stream = new MemoryStream(44 + pcm.Length))
            using (BinaryWriter writer = new BinaryWriter(stream))
            {
                writer.Write(Encoding.ASCII.GetBytes("RIFF"));
                writer.Write(36 + pcm.Length);
                writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
                writer.Write(16);
                writer.Write((short) 1);
                writer.Write((short) 1);
                writer.Write(rate);
                writer.Write(rate * 2);
                writer.Write((short) 2);
                writer.Write((short) 16);
                writer.Write(Encoding.ASCII.GetBytes("data"));
                writer.Write(pcm.Length);
                writer.Write(pcm);
                writer.Flush();
                return stream.ToArray();
            }
        }

        void ApplyVolume()
        {
            uint level = (uint) (settings.Volume * 0xFFFF / 100);
            waveOutSetVolume(IntPtr.Zero, (level << 16) | level);
        }

        // ---- Utilidades -------------------------------------------------------------------------

        void SetStatus(string text)
        {
            status.Text = text;
            tray.Text = text.Length > 60 ? text.Substring(0, 60) : text;
        }

        void UiStatus(int mine, string text)
        {
            if (closing || mine != generation || !IsHandleCreated)
            {
                return;
            }
            BeginInvoke((MethodInvoker) delegate
            {
                if (mine == generation)
                {
                    SetStatus(text);
                }
            });
        }

        static string ReadAll(HttpWebRequest request)
        {
            using (HttpWebResponse response = (HttpWebResponse) request.GetResponse())
            using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
            {
                return reader.ReadToEnd();
            }
        }

        /// <summary>Un campo de texto de un JSON plano ({"token":"...","jugador":"..."}).</summary>
        static string Field(string json, string name)
        {
            Match match = Regex.Match(json, "\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            return match.Success ? Regex.Unescape(match.Groups[1].Value) : "";
        }

        /// <summary>El mensaje del servidor si mandó uno (código vencido, etc.), o el error de red.</summary>
        static string ErrorText(Exception e)
        {
            WebException web = e as WebException;
            if (web != null && web.Response != null)
            {
                try
                {
                    using (StreamReader reader = new StreamReader(web.Response.GetResponseStream(), Encoding.UTF8))
                    {
                        string text = reader.ReadToEnd().Trim();
                        if (text.Length > 0 && text.Length < 200)
                        {
                            return text;
                        }
                    }
                }
                catch (Exception)
                {
                }
            }
            return e.Message;
        }

        [STAThread]
        static void Main()
        {
            ServicePointManager.DefaultConnectionLimit = 8;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
