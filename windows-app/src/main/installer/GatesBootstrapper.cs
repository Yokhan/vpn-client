using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("GATES Installer")]
[assembly: AssemblyProduct("GATES")]
[assembly: AssemblyCompany("Yokhan (Igor Nazarov)")]
[assembly: AssemblyCopyright("Copyright (c) 2026 Yokhan (Igor Nazarov)")]
[assembly: AssemblyVersion("1.9.1.0")]
[assembly: AssemblyFileVersion("1.9.1.0")]

namespace Gates.Installer
{
    internal static class Program
    {
        private const string InstallDir = @"C:\Program Files\GATES";
        private const string DataDir = @"C:\ProgramData\GATES";
        private const string LogPath = @"C:\ProgramData\GATES\installer.log";
        private const string MsiLogPath = @"C:\ProgramData\GATES\installer-msi.log";
        private const string MsiRepairLogPath = @"C:\ProgramData\GATES\installer-repair-msi.log";
        private const string MutexName = @"Global\GatesSetupInstallerMutex";
        private const string ServiceName = "GatesVpn";
        private static readonly Regex GuidPattern = new Regex(@"^\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}$", RegexOptions.Compiled);
        private static readonly object LogLock = new object();

        [STAThread]
        private static int Main(string[] args)
        {
            bool quiet = HasArg(args, "/quiet", "/qn", "/silent", "-quiet", "--quiet");
            bool passive = HasArg(args, "/passive", "-passive");

            bool createdNew;
            using (Mutex mutex = new Mutex(true, MutexName, out createdNew))
            {
                if (!createdNew)
                {
                    Log("Another GATES installer instance is already active.");
                    if (!quiet)
                    {
                        MessageBox.Show(
                            "Установщик GATES уже запущен. Дождитесь завершения текущего окна установки.",
                            "GATES — Установка",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Information
                        );
                    }
                    return 1618;
                }

                if (quiet)
                {
                    string error;
                    int code = RunInstall(null, true, out error);
                    return code;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (InstallerForm form = new InstallerForm(passive))
                {
                    Application.Run(form);
                    return form.ExitCode;
                }
            }
        }

        private static bool HasArg(string[] args, params string[] names)
        {
            if (args == null) return false;
            foreach (string arg in args)
            {
                if (arg == null) continue;
                string normalized = arg.Trim();
                foreach (string name in names)
                {
                    if (string.Equals(normalized, name, StringComparison.OrdinalIgnoreCase)) return true;
                }
            }
            return false;
        }

        internal static void Log(string message)
        {
            try
            {
                Directory.CreateDirectory(DataDir);
                string line = string.Format("[{0:yyyy-MM-dd HH:mm:ss}] {1}", DateTime.Now, message);
                lock (LogLock)
                {
                    File.AppendAllText(LogPath, line + Environment.NewLine, Encoding.UTF8);
                }
            }
            catch
            {
            }
        }

        internal static int RunInstall(Action<string> reportStatus, bool quiet, out string errorMessage)
        {
            errorMessage = null;
            string tempDir = Path.Combine(Path.GetTempPath(), "GATES-setup-" + Guid.NewGuid().ToString("N"));
            string msiPath = Path.Combine(tempDir, "GATES.msi");
            try
            {
                Log("=== GATES self-healing EXE installer start ===");
                Directory.CreateDirectory(tempDir);

                if (reportStatus != null) reportStatus("Остановка службы и процессов GATES…");
                StopGatesRuntimeAndProcesses();

                if (reportStatus != null) reportStatus("Очистка конфликтующих и старых версий GATES…");
                RemoveExistingGatesMsiRegistrations();
                StopGatesRuntimeAndProcesses();
                CleanBrokenInstallFiles();

                if (reportStatus != null) reportStatus("Распаковка установщика GATES…");
                ExtractEmbeddedMsi(msiPath);

                if (reportStatus != null) reportStatus("Установка файлов GATES…");
                string msiUi = quiet ? "/qn" : "/passive";
                int installExit = RunProcessWait(
                    "msiexec.exe",
                    string.Format("/i \"{0}\" {1} /norestart MSIRESTARTMANAGERCONTROL=Disable /l*v \"{2}\"", msiPath, msiUi, MsiLogPath),
                    300000
                );
                Log("msiexec /i exit=" + installExit);

                bool acceptedExit = installExit == 0 || installExit == 3010 || installExit == 1641 || installExit == 1638;
                if (!acceptedExit)
                {
                    RestoreServiceRecovery();
                    errorMessage = string.Format(
                        "Установка завершилась с ошибкой Windows Installer (код {0}). Подробности: {1}",
                        installExit,
                        LogPath
                    );
                    return installExit != 0 ? installExit : 1603;
                }

                List<string> missingBeforeRepair = FindMissingInstalledFiles();
                if (installExit == 1638 || missingBeforeRepair.Count > 0)
                {
                    Log("Running msiexec /fa repair (installExit=" + installExit + ", missing=" + missingBeforeRepair.Count + ")");
                    if (reportStatus != null) reportStatus("Восстановление файлов приложения…");
                    StopGatesRuntimeAndProcesses();
                    int repairExit = RunProcessWait(
                        "msiexec.exe",
                        string.Format("/fa \"{0}\" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable /l*v \"{1}\"", msiPath, MsiRepairLogPath),
                        300000
                    );
                    Log("msiexec /fa exit=" + repairExit);
                }

                if (reportStatus != null) reportStatus("Проверка целостности установленных файлов…");
                List<string> missingAfter = FindMissingInstalledFiles();
                if (missingAfter.Count > 0)
                {
                    RestoreServiceRecovery();
                    string preview = string.Join(", ", missingAfter.GetRange(0, Math.Min(3, missingAfter.Count)).ToArray());
                    Log("Verification failed, missing files: " + string.Join("; ", missingAfter.ToArray()));
                    errorMessage = string.Format(
                        "Не все файлы GATES установились на диск ({0} и др.). Повторите установку после перезагрузки или откройте лог: {1}",
                        preview,
                        LogPath
                    );
                    return 1603;
                }

                if (reportStatus != null) reportStatus("Запуск службы GatesVpn…");
                RestoreServiceRecovery();
                StartServiceBestEffort();

                if (!quiet)
                {
                    if (reportStatus != null) reportStatus("Запуск GATES…");
                    LaunchInstalledAppBestEffort();
                }

                Log("=== GATES self-healing EXE installer finished successfully ===");
                return 0;
            }
            catch (Exception ex)
            {
                Log("Installer exception: " + ex);
                errorMessage = "Сбой установщика: " + ex.Message + " (лог: " + LogPath + ")";
                return 1;
            }
            finally
            {
                try
                {
                    if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true);
                }
                catch
                {
                }
            }
        }

        private static void ExtractEmbeddedMsi(string destinationPath)
        {
            Assembly asm = Assembly.GetExecutingAssembly();
            using (Stream stream = asm.GetManifestResourceStream("GATES.msi"))
            {
                if (stream == null)
                {
                    throw new InvalidOperationException("Внутри установщика отсутствует пакет GATES.msi.");
                }
                using (FileStream fs = new FileStream(destinationPath, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    stream.CopyTo(fs);
                }
            }
        }

        private static void StopGatesRuntimeAndProcesses()
        {
            if (ServiceExists())
            {
                RunProcessWait("sc.exe", "failure " + ServiceName + " reset= 0 actions= \"\"", 10000);
                RunProcessWait("sc.exe", "stop " + ServiceName, 10000);
                WaitForServiceStopped(20000);
                RunProcessWait("taskkill.exe", "/F /T /IM Gates.Service.exe", 10000);
            }

            RunProcessWait("taskkill.exe", "/F /T /IM GATES.exe", 10000);

            foreach (string procName in new[] { "xray", "sing-box" })
            {
                try
                {
                    Process[] procs = Process.GetProcessesByName(procName);
                    foreach (Process p in procs)
                    {
                        try
                        {
                            string path = p.MainModule != null ? p.MainModule.FileName : null;
                            if (!string.IsNullOrEmpty(path) &&
                                path.StartsWith(InstallDir + "\\", StringComparison.OrdinalIgnoreCase))
                            {
                                p.Kill();
                                p.WaitForExit(3000);
                            }
                        }
                        catch
                        {
                        }
                        finally
                        {
                            p.Dispose();
                        }
                    }
                }
                catch
                {
                }
            }

            Thread.Sleep(400);
        }

        private static bool ServiceExists()
        {
            int rc = RunProcessWait("sc.exe", "query " + ServiceName, 8000);
            return rc == 0;
        }

        private static void WaitForServiceStopped(int timeoutMs)
        {
            Stopwatch sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs)
            {
                string stdout = RunProcessCapture("sc.exe", "query " + ServiceName, 5000);
                if (stdout.IndexOf("STOPPED", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    stdout.IndexOf("1060", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    return;
                }
                Thread.Sleep(500);
            }
        }

        private static void RemoveExistingGatesMsiRegistrations()
        {
            List<string> productCodes = FindInstalledGatesProductCodes();
            if (productCodes.Count == 0)
            {
                Log("No prior GATES MSI entries detected.");
                return;
            }

            foreach (string code in productCodes)
            {
                Log("Uninstalling existing GATES MSI product: " + code);
                StopGatesRuntimeAndProcesses();
                int rc = RunProcessWait(
                    "msiexec.exe",
                    string.Format("/x {0} /qn /norestart MSIRESTARTMANAGERCONTROL=Disable", code),
                    180000
                );
                Log("msiexec /x " + code + " exit=" + rc);
            }
        }

        private static List<string> FindInstalledGatesProductCodes()
        {
            List<string> results = new List<string>();
            HashSet<string> seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string[] uninstallRoots = new[]
            {
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
                @"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
            };

            RegistryHive[] hives = new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser };
            RegistryView[] views = new[] { RegistryView.Registry64, RegistryView.Registry32 };

            foreach (RegistryHive hive in hives)
            {
                foreach (RegistryView view in views)
                {
                    try
                    {
                        using (RegistryKey baseKey = RegistryKey.OpenBaseKey(hive, view))
                        {
                            foreach (string rootPath in uninstallRoots)
                            {
                                using (RegistryKey uninstallKey = baseKey.OpenSubKey(rootPath, false))
                                {
                                    if (uninstallKey == null) continue;
                                    foreach (string subName in uninstallKey.GetSubKeyNames())
                                    {
                                        if (!GuidPattern.IsMatch(subName)) continue;
                                        try
                                        {
                                            using (RegistryKey item = uninstallKey.OpenSubKey(subName, false))
                                            {
                                                if (item == null) continue;
                                                string displayName = Convert.ToString(item.GetValue("DisplayName"));
                                                if (!string.Equals(displayName != null ? displayName.Trim() : "", "GATES", StringComparison.OrdinalIgnoreCase))
                                                {
                                                    continue;
                                                }
                                                if (seen.Add(subName))
                                                {
                                                    string ver = Convert.ToString(item.GetValue("DisplayVersion"));
                                                    Log("Found registered GATES product: " + subName + " version=" + ver + " hive=" + hive);
                                                    results.Add(subName);
                                                }
                                            }
                                        }
                                        catch
                                        {
                                        }
                                    }
                                }
                            }
                        }
                    }
                    catch
                    {
                    }
                }
            }
            return results;
        }

        private static void CleanBrokenInstallFiles()
        {
            // Only remove binary/runtime folders under C:\Program Files\GATES after all MSI entries
            // have been uninstalled. User data (%APPDATA%\GATES and C:\ProgramData\GATES) is untouched.
            if (!Directory.Exists(InstallDir)) return;
            foreach (string subDir in new[] { "app", "runtime" })
            {
                string path = Path.Combine(InstallDir, subDir);
                if (Directory.Exists(path))
                {
                    try
                    {
                        Directory.Delete(path, true);
                        Log("Cleaned leftover binary dir: " + path);
                    }
                    catch (Exception ex)
                    {
                        Log("Could not remove leftover dir " + path + ": " + ex.Message);
                    }
                }
            }
            foreach (string fileName in new[] { "GATES.exe", "GATES.ico" })
            {
                string filePath = Path.Combine(InstallDir, fileName);
                if (File.Exists(filePath))
                {
                    try
                    {
                        File.Delete(filePath);
                    }
                    catch (Exception ex)
                    {
                        Log("Could not remove leftover file " + filePath + ": " + ex.Message);
                    }
                }
            }
        }

        private static List<string> FindMissingInstalledFiles()
        {
            List<string> missing = new List<string>();
            string[] requiredPaths = new[]
            {
                Path.Combine(InstallDir, "GATES.exe"),
                Path.Combine(InstallDir, @"runtime\lib\modules"),
                Path.Combine(InstallDir, @"app\GATES.cfg"),
                Path.Combine(InstallDir, @"app\resources\gates-service\Gates.Service.jar"),
                Path.Combine(InstallDir, @"app\resources\gates-service\Gates.Service.exe"),
                Path.Combine(InstallDir, @"app\resources\yokhan-runtime\windows\xray.exe"),
                Path.Combine(InstallDir, @"app\resources\yokhan-runtime\windows\sing-box.exe")
            };

            foreach (string p in requiredPaths)
            {
                if (!File.Exists(p)) missing.Add(p);
            }

            string cfgPath = Path.Combine(InstallDir, @"app\GATES.cfg");
            if (File.Exists(cfgPath))
            {
                string appDir = Path.Combine(InstallDir, "app");
                foreach (string line in File.ReadAllLines(cfgPath, Encoding.UTF8))
                {
                    const string prefix = "app.classpath=$APPDIR\\";
                    if (line.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                    {
                        string rel = line.Substring(prefix.Length).Trim();
                        if (rel.Length > 0)
                        {
                            string full = Path.Combine(appDir, rel);
                            if (!File.Exists(full)) missing.Add(full);
                        }
                    }
                }
            }
            return missing;
        }

        private static void RestoreServiceRecovery()
        {
            if (ServiceExists())
            {
                RunProcessWait("sc.exe", "failure " + ServiceName + " reset= 3600 actions= restart/10000", 8000);
            }
        }

        private static void StartServiceBestEffort()
        {
            if (!ServiceExists()) return;
            RunProcessWait("sc.exe", "start " + ServiceName, 10000);
            Stopwatch sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 15000)
            {
                string stdout = RunProcessCapture("sc.exe", "query " + ServiceName, 5000);
                if (stdout.IndexOf("RUNNING", StringComparison.OrdinalIgnoreCase) >= 0) return;
                Thread.Sleep(500);
            }
        }

        private static void LaunchInstalledAppBestEffort()
        {
            string exePath = Path.Combine(InstallDir, "GATES.exe");
            if (!File.Exists(exePath)) return;
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = "\"" + exePath + "\"",
                    UseShellExecute = false,
                    CreateNoWindow = true
                });
            }
            catch (Exception ex)
            {
                Log("Launch GATES.exe warning: " + ex.Message);
            }
        }

        private static int RunProcessWait(string fileName, string arguments, int timeoutMs)
        {
            try
            {
                using (Process p = Process.Start(new ProcessStartInfo
                {
                    FileName = fileName,
                    Arguments = arguments,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WindowStyle = ProcessWindowStyle.Hidden
                }))
                {
                    if (p == null) return 1;
                    if (!p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                        return 1460;
                    }
                    return p.ExitCode;
                }
            }
            catch
            {
                return 1;
            }
        }

        private static string RunProcessCapture(string fileName, string arguments, int timeoutMs)
        {
            try
            {
                using (Process p = Process.Start(new ProcessStartInfo
                {
                    FileName = fileName,
                    Arguments = arguments,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    CreateNoWindow = true,
                    WindowStyle = ProcessWindowStyle.Hidden
                }))
                {
                    if (p == null) return "";
                    string output = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(timeoutMs);
                    return output ?? "";
                }
            }
            catch
            {
                return "";
            }
        }

        private sealed class InstallerForm : Form
        {
            private readonly Label _titleLabel;
            private readonly Label _statusLabel;
            private readonly ProgressBar _progressBar;
            private readonly Button _closeButton;
            private readonly Button _logButton;
            private readonly bool _passive;

            public int ExitCode { get; private set; }

            public InstallerForm(bool passive)
            {
                _passive = passive;
                ExitCode = 1;
                Text = "GATES — Установка и обновление";
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                MinimizeBox = true;
                StartPosition = FormStartPosition.CenterScreen;
                ClientSize = new Size(460, 175);
                BackColor = Color.FromArgb(14, 15, 19);
                ForeColor = Color.FromArgb(242, 244, 248);
                Font = new Font("Segoe UI", 9.5f);

                try
                {
                    Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
                }
                catch
                {
                }

                _titleLabel = new Label
                {
                    Text = "Установка GATES 1.9.1",
                    Font = new Font("Segoe UI Semibold", 12f),
                    ForeColor = Color.White,
                    Location = new Point(20, 18),
                    Size = new Size(420, 26)
                };

                _statusLabel = new Label
                {
                    Text = "Подготовка к установке…",
                    ForeColor = Color.FromArgb(198, 204, 214),
                    Location = new Point(20, 52),
                    Size = new Size(420, 42)
                };

                _progressBar = new ProgressBar
                {
                    Style = ProgressBarStyle.Marquee,
                    MarqueeAnimationSpeed = 28,
                    Location = new Point(20, 102),
                    Size = new Size(420, 18)
                };

                _logButton = new Button
                {
                    Text = "Открыть лог",
                    Location = new Point(224, 132),
                    Size = new Size(102, 28),
                    Visible = false,
                    FlatStyle = FlatStyle.Flat,
                    ForeColor = Color.White,
                    BackColor = Color.FromArgb(32, 36, 46)
                };
                _logButton.Click += delegate
                {
                    try
                    {
                        if (File.Exists(LogPath))
                        {
                            Process.Start(new ProcessStartInfo { FileName = "notepad.exe", Arguments = "\"" + LogPath + "\"", UseShellExecute = true });
                        }
                    }
                    catch
                    {
                    }
                };

                _closeButton = new Button
                {
                    Text = "Закрыть",
                    Location = new Point(338, 132),
                    Size = new Size(102, 28),
                    Visible = false,
                    FlatStyle = FlatStyle.Flat,
                    ForeColor = Color.White,
                    BackColor = Color.FromArgb(46, 125, 255)
                };
                _closeButton.Click += delegate { Close(); };

                Controls.Add(_titleLabel);
                Controls.Add(_statusLabel);
                Controls.Add(_progressBar);
                Controls.Add(_logButton);
                Controls.Add(_closeButton);

                Shown += OnShownStartWork;
            }

            private void OnShownStartWork(object sender, EventArgs e)
            {
                Thread worker = new Thread(delegate()
                {
                    string error;
                    int code = RunInstall(UpdateStatus, false, out error);
                    ExitCode = code;
                    BeginInvoke((Action)delegate
                    {
                        if (code == 0)
                        {
                            _progressBar.Style = ProgressBarStyle.Continuous;
                            _progressBar.Value = 100;
                            _statusLabel.Text = "GATES успешно установлен и запущен.";
                            Close();
                        }
                        else
                        {
                            _progressBar.Style = ProgressBarStyle.Continuous;
                            _progressBar.Value = 0;
                            _titleLabel.Text = "Ошибка установки GATES";
                            _statusLabel.Text = error ?? ("Код ошибки: " + code);
                            if (_passive)
                            {
                                Close();
                            }
                            else
                            {
                                _logButton.Visible = true;
                                _closeButton.Visible = true;
                            }
                        }
                    });
                });
                worker.IsBackground = true;
                worker.Start();
            }

            private void UpdateStatus(string text)
            {
                if (IsDisposed) return;
                try
                {
                    BeginInvoke((Action)delegate
                    {
                        if (!IsDisposed) _statusLabel.Text = text;
                    });
                }
                catch
                {
                }
            }
        }
    }
}
