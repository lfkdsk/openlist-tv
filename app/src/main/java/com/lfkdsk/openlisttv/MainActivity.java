package com.lfkdsk.openlisttv;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 电视界面:状态、管理地址(二维码)、管理员密码、启动/停止、日志。全部用遥控器操作。 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private OpenListServer server;
    private ImageView qr;
    private TextView status, address, admin, logView, notes;
    private Button browse, player, toggle, showPw, resetPw, logBtn;
    private String shownUrl;
    private boolean showLog, showInitial;
    private long resetArmedAt;
    private String resetResult;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = OpenListServer.get(this);
        ServerService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0B0F17);
        root.setPadding(dp(56), dp(40), dp(56), dp(32));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(12), dp(12), dp(12), dp(12));
        left.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        TextView qrHint = text(15, 0xFF8B97AB);
        qrHint.setText("手机扫码打开管理页");
        qrHint.setPadding(0, dp(10), 0, 0);
        left.addView(qrHint);
        root.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1);
        rp.leftMargin = dp(48);
        root.addView(right, rp);

        TextView title = text(32, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("OpenList TV");
        right.addView(title);
        status = text(20, 0xFF35C98B);
        right.addView(status, top(6));
        address = text(19, 0xFF8BB4FF);
        address.setLineSpacing(0, 1.25f);
        right.addView(address, top(14));
        admin = text(18, 0xFFC9D2E0);
        admin.setLineSpacing(0, 1.3f);
        right.addView(admin, top(14));

        LinearLayout main1 = new LinearLayout(this);
        main1.setOrientation(LinearLayout.HORIZONTAL);
        browse = button("浏览文件", v -> startActivity(new android.content.Intent(this, BrowserActivity.class)));
        player = button("默认播放器", v -> Players.choose(this, this::refresh));
        main1.addView(browse);
        main1.addView(player, left(16));
        right.addView(main1, top(20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        toggle = button("停止服务", v -> {
            if (server.processAlive() || server.listening()) server.stop(); else server.start(true);
            refresh();
        });
        showPw = button("显示初始密码", v -> { showInitial = !showInitial; refresh(); });
        resetPw = button("重置管理员密码", v -> onReset());
        logBtn = button("查看日志", v -> { showLog = !showLog; refresh(); });
        buttons.addView(toggle);
        buttons.addView(showPw, left(16));
        buttons.addView(resetPw, left(16));
        buttons.addView(logBtn, left(16));
        right.addView(buttons, top(16));

        notes = text(15, 0xFF8B97AB);
        notes.setLineSpacing(0, 1.3f);
        right.addView(notes, top(16));
        logView = text(13, 0xFFAAB4C3);
        logView.setTypeface(Typeface.MONOSPACE);
        right.addView(logView, top(10));

        setContentView(root);
        browse.requestFocus();
    }

    private void onReset() {
        long now = System.currentTimeMillis();
        if (now - resetArmedAt > 5000) {
            resetArmedAt = now;
            resetResult = "再按一次「重置管理员密码」确认:会把 admin 的密码换成新的随机密码。";
            refresh();
            return;
        }
        resetArmedAt = 0;
        resetResult = "正在重置…";
        refresh();
        new Thread(() -> {
            String pw = server.resetAdminPassword();
            main.post(() -> {
                resetResult = pw != null
                        ? "新密码:" + pw + "   (只显示这一次,用它登录管理页后建议在网页里改成自己的密码)"
                        : "重置失败,看看日志";
                refresh();
            });
        }, "reset").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    private void refresh() {
        new Thread(() -> {
            boolean listening = server.listening();
            boolean alive = server.processAlive();
            String ip = lanAddress();
            int port = server.port();
            main.post(() -> render(listening, alive, ip, port));
        }, "status").start();
    }

    private void render(boolean listening, boolean alive, String ip, int port) {
        String url = ip != null ? "http://" + ip + ":" + port : null;
        if (url != null && !url.equals(shownUrl)) {
            qr.setImageBitmap(qrBitmap(url, dp(236)));
            shownUrl = url;
        }
        if (listening) {
            status.setTextColor(0xFF35C98B);
            status.setText("● 运行中");
        } else if (alive) {
            status.setTextColor(0xFFF0B34A);
            status.setText("○ 启动中…");
        } else {
            status.setTextColor(0xFFFF6B5F);
            status.setText(server.userStopped() ? "○ 已停止(按「启动服务」)" : "○ 没在运行" + (server.lastError != null ? ":" + server.lastError : ""));
        }
        toggle.setText(alive || listening ? "停止服务" : "启动服务");
        address.setText(url == null ? "电视还没有联网" : "管理页:" + url + "\nWebDAV:" + url + "/dav   (用管理员或网页里建的用户登录)");

        String initial = server.initialPassword();
        StringBuilder a = new StringBuilder("管理员用户名:admin");
        if (initial != null) a.append(showInitial ? "\n初始密码:" + initial : "\n初始密码:已隐藏(按「显示初始密码」)");
        else a.append("\n密码已经改过;忘了可以按「重置管理员密码」");
        if (resetResult != null) a.append("\n").append(resetResult);
        admin.setText(a);
        showPw.setVisibility(initial != null ? Button.VISIBLE : Button.GONE);
        showPw.setText(showInitial ? "隐藏初始密码" : "显示初始密码");
        logBtn.setText(showLog ? "收起日志" : "查看日志");
        player.setText("默认播放器:" + Players.currentLabel(this));
        browse.setEnabled(listening);

        StringBuilder n = new StringBuilder("网盘、本地文件夹、用户都在手机或电脑浏览器打开管理页里设置。");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && !nm.isNotificationListenerAccessGranted(new ComponentName(this, KeepAliveListener.class))) {
            n.append("\n⚠ 后台常驻没授权,开机不会自动启动:adb shell cmd notification allow_listener com.lfkdsk.openlisttv/com.lfkdsk.openlisttv.KeepAliveListener");
        }
        if (android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            n.append("\n⚠ 还不能读电视存储(挂本地文件夹要用):adb shell appops set com.lfkdsk.openlisttv MANAGE_EXTERNAL_STORAGE allow");
        }
        notes.setText(n);

        if (showLog) {
            List<String> tail = server.log.tail(14);
            logView.setText(String.join("\n", tail));
        } else {
            logView.setText("");
        }
    }

    private String lanAddress() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static Bitmap qrBitmap(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setFocusable(true);
        b.setBackground(bg(false));
        b.setOnFocusChangeListener((v, has) -> v.setBackground(bg(has)));
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable bg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? 0xFF2B5AA8 : 0xFF1B2230);
        g.setCornerRadius(dp(12));
        if (focused) g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams top(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(m);
        return p;
    }

    private LinearLayout.LayoutParams left(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(m);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
