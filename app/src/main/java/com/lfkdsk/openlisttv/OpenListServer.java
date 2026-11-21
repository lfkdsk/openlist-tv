package com.lfkdsk.openlisttv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 管理官方 OpenList 服务器进程(nativeLibraryDir/libopenlist.so server --data files/data)。
 * 意外退出会按退避间隔自动重启;stop() 是用户主动停止,记住状态,开机/拉起时不再启动。
 */
final class OpenListServer {
    private static final String TAG = "OpenListTV";
    private static OpenListServer instance;

    static synchronized OpenListServer get(Context context) {
        if (instance == null) instance = new OpenListServer(context.getApplicationContext());
        return instance;
    }

    final Context context;
    final File binary;
    final File dataDir;
    final Core.LogBuffer log = new Core.LogBuffer(300);
    private final SharedPreferences prefs;
    private final Handler worker;
    private Process process;
    private boolean wanted;
    private int failures;
    private long startedAt;
    volatile String lastError;

    private OpenListServer(Context context) {
        this.context = context;
        binary = new File(context.getApplicationInfo().nativeLibraryDir, "libopenlist.so");
        dataDir = new File(context.getFilesDir(), "data");
        prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("openlist");
        t.start();
        worker = new Handler(t.getLooper());
    }

    /** 用户有没有手动停掉服务(停掉后开机和保活都不再拉起)。 */
    boolean userStopped() {
        return prefs.getBoolean("userStopped", false);
    }

    String initialPassword() {
        return prefs.getString("initialPassword", null);
    }

    int port() {
        File cfg = new File(dataDir, "config.json");
        try {
            return Core.httpPort(cfg.isFile() ? new String(java.nio.file.Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8) : null);
        } catch (IOException e) {
            return Core.DEFAULT_PORT;
        }
    }

    synchronized boolean processAlive() {
        return process != null && process.isAlive();
    }

    /** 端口能连上才算真正在服务。 */
    boolean listening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 由保活/开机调用:用户没手动停止才启动。 */
    void ensureRunning() {
        if (!userStopped()) start(false);
    }

    void start(boolean byUser) {
        if (byUser) prefs.edit().putBoolean("userStopped", false).apply();
        worker.post(() -> {
            wanted = true;
            launch();
        });
    }

    void stop() {
        prefs.edit().putBoolean("userStopped", true).apply();
        worker.post(() -> {
            wanted = false;
            worker.removeCallbacksAndMessages(null);
            Process p;
            synchronized (this) { p = process; }
            if (p == null) return;
            p.destroy(); // SIGTERM,让 OpenList 正常落盘
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
            log.add("— 已停止 —");
        });
    }

    private void launch() {
        synchronized (this) {
            if (process != null && process.isAlive()) return;
        }
        if (!binary.canExecute()) {
            lastError = "找不到服务器程序:" + binary;
            log.add(lastError);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();
        useStdoutLogging();
        ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "server", "--data", dataDir.getAbsolutePath());
        pb.directory(context.getFilesDir());
        pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        // Android 不给应用进程设 TZ,Go 程序会按 UTC 记日志、算定时任务;传系统时区进去
        pb.environment().put("TZ", java.util.TimeZone.getDefault().getID());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            synchronized (this) { process = p; }
            startedAt = SystemClock.elapsedRealtime();
            lastError = null;
            log.add("— 启动 OpenList —");
            Log.i(TAG, "started " + binary);
            Thread reader = new Thread(() -> pump(p), "openlist-log");
            reader.setDaemon(true);
            reader.start();
            // 第一次启动时 config.json 还不存在,初始密码写在 OpenList 自己的日志文件里
            worker.postDelayed(this::findInitialPasswordInLogFile, 8000);
        } catch (IOException e) {
            lastError = "启动失败:" + e.getMessage();
            log.add(lastError);
            scheduleRestart();
        }
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.add(line);
                Log.i("OpenListCore", Core.stripAnsi(line));
                String pw = Core.initialPassword(line);
                if (pw != null) prefs.edit().putString("initialPassword", pw).apply();
                if (line.contains("address already in use")) lastError = "端口 " + port() + " 被占用(是不是官方 OpenList 也开着?)";
            }
        } catch (IOException ignored) {
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        log.add("— 服务器退出,代码 " + code + " —");
        worker.post(() -> {
            synchronized (this) { if (process == p) process = null; }
            if (wanted) scheduleRestart();
        });
    }

    /** 关掉 OpenList 自己的日志文件(改配置,下次启动生效),日志改走标准输出,「查看日志」能看到全部。 */
    private void useStdoutLogging() {
        File cfg = new File(dataDir, "config.json");
        if (!cfg.isFile()) return;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
            String t = Core.disableFileLog(s);
            if (t != null && !t.equals(s)) java.nio.file.Files.write(cfg.toPath(), t.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "config", e);
        }
    }

    /** 从 data/log 下的日志文件里找首次启动时的初始密码(只在还没记下时找)。 */
    private void findInitialPasswordInLogFile() {
        if (initialPassword() != null || prefs.getBoolean("initialScanned", false)) return;
        File[] files = new File(dataDir, "log").listFiles();
        if (files == null) return;
        for (File f : files) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String pw = Core.initialPassword(line);
                    if (pw != null) {
                        prefs.edit().putString("initialPassword", pw).putBoolean("initialScanned", true).apply();
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void scheduleRestart() {
        if (SystemClock.elapsedRealtime() - startedAt > 60_000) failures = 0;
        int delay = Core.backoffSeconds(failures++);
        log.add("— " + delay + " 秒后重启 —");
        worker.postDelayed(() -> { if (wanted) launch(); }, delay * 1000L);
    }

    /**
     * 重置管理员密码:运行 `openlist admin random`,返回新密码(拿不到返回 null)。
     * 新密码只显示在电视上给用户看,不保存。阻塞,不要在主线程调用。
     */
    String resetAdminPassword() {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("admin");
        cmd.add("random");
        cmd.add("--data");
        cmd.add(dataDir.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            p.waitFor(20, TimeUnit.SECONDS);
            String pw = Core.passwordFromAdminOutput(out);
            if (pw != null) prefs.edit().remove("initialPassword").putBoolean("initialScanned", true).apply();
            return pw;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
