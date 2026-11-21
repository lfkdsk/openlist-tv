package com.lfkdsk.openlisttv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 遥控器浏览 OpenList 里的文件:OK 进文件夹 / 用默认播放器打开,长按 OK 临时换播放器,
 * 返回键回上一级,菜单键设默认播放器。
 */
public class BrowserActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private OpenListApi api;
    private String path = "/";
    private final List<OpenListApi.Item> items = new ArrayList<>();
    /** 每个目录离开时选中的位置,返回时恢复 */
    private final Map<String, Integer> positions = new HashMap<>();
    private TextView title, hint, empty;
    private ListView list;
    private Adapter adapter;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new OpenListApi(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F17);
        root.setPadding(dp(56), dp(28), dp(56), dp(16));

        title = text(26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        hint = text(15, 0xFF8B97AB);
        root.addView(hint, lp(-1, -2, 4));

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(selector());
        list.setDrawSelectorOnTop(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(items.get(pos), false));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            open(items.get(pos), true);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(12);
        root.addView(list, lp);

        empty = text(20, 0xFF8B97AB);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        load("/");
    }

    private void renderHint() {
        hint.setText("OK 打开 · 长按 OK 换播放器 · 菜单键设默认播放器(现在:" + Players.currentLabel(this) + ") · 返回 上一级");
    }

    private void load(String p) {
        if (loading) return;
        loading = true;
        title.setText(p);
        renderHint();
        new Thread(() -> {
            try {
                List<OpenListApi.Item> got = api.list(p);
                // 文件夹在前,名字自然排序
                got.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : naturalCompare(a.name, b.name));
                main.post(() -> show(p, got));
            } catch (OpenListApi.LoginRequired e) {
                main.post(() -> { loading = false; askLogin(p, e.getMessage()); });
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    Toast.makeText(this, "读取失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (items.isEmpty()) showEmpty("读取失败:" + e.getMessage() + "\n服务器是不是没在运行?");
                });
            }
        }, "list").start();
    }

    private void show(String p, List<OpenListApi.Item> got) {
        loading = false;
        path = p;
        items.clear();
        items.addAll(got);
        adapter.notifyDataSetChanged();
        title.setText(p.equals("/") ? "OpenList · 全部存储" : p);
        if (got.isEmpty()) {
            showEmpty(p.equals("/") ? "还没有挂载任何存储。\n用手机扫 OpenList TV 主页上的二维码,在管理页里添加网盘或本地文件夹。" : "这个文件夹是空的");
            return;
        }
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        Integer pos = positions.get(p);
        int at = pos != null && pos < got.size() ? pos : 0;
        list.requestFocus();
        list.setSelection(at);
    }

    private void showEmpty(String msg) {
        empty.setText(msg);
        empty.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
    }

    private void open(OpenListApi.Item it, boolean askPlayer) {
        String full = Paths.join(path, it.name);
        if (it.dir) {
            positions.put(path, list.getSelectedItemPosition());
            load(full);
            return;
        }
        Paths.Kind k = Paths.kind(it.name, false);
        if (k == Paths.Kind.SUBTITLE || k == Paths.Kind.OTHER) {
            Toast.makeText(this, "这个文件不能直接播放", Toast.LENGTH_SHORT).show();
            return;
        }
        Players.play(this, Paths.playUrl(api.base(), full, it.sign), it.name, askPlayer);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            String up = Paths.parent(path);
            if (up == null) return super.onKeyDown(keyCode, event);
            load(up);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            Players.choose(this, this::renderHint);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 登录框:用户名、密码由用户输入(电视键盘可以用手机打)。 */
    private void askLogin(String p, String why) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        TextView msg = text(16, 0xFF555555);
        msg.setText(why + "\n登录一次后会记住,服务器重启后自动重新登录。");
        box.addView(msg);
        EditText user = new EditText(this);
        user.setHint("用户名");
        user.setSingleLine();
        user.setText(api.savedUser());
        box.addView(user);
        EditText pass = new EditText(this);
        pass.setHint("密码");
        pass.setSingleLine();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle("登录 OpenList")
                .setView(box)
                .setPositiveButton("登录", (d, w) -> {
                    String u = user.getText().toString().trim();
                    String pw = pass.getText().toString();
                    new Thread(() -> {
                        try {
                            api.login(u, pw);
                            main.post(() -> load(p));
                        } catch (Exception e) {
                            main.post(() -> askLogin(p, "登录失败:" + e.getMessage()));
                        }
                    }, "login").start();
                })
                .setNegativeButton("取消", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        pass.requestFocus();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView icon, name, meta;
            if (convert == null) {
                row = new LinearLayout(BrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));
                icon = text(24, Color.WHITE);
                name = text(21, Color.WHITE);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                meta = text(15, 0xFF8B97AB);
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), -2));
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(meta);
                row.setTag(new TextView[]{icon, name, meta});
            } else {
                row = (LinearLayout) convert;
            }
            TextView[] v = (TextView[]) row.getTag();
            OpenListApi.Item it = items.get(i);
            switch (Paths.kind(it.name, it.dir)) {
                case DIR: v[0].setText("📁"); break;
                case VIDEO: v[0].setText("🎬"); break;
                case AUDIO: v[0].setText("🎵"); break;
                case IMAGE: v[0].setText("🖼"); break;
                case SUBTITLE: v[0].setText("💬"); break;
                default: v[0].setText("📄");
            }
            v[1].setText(it.name);
            String date = it.modified != null && it.modified.length() >= 10 ? it.modified.substring(0, 10) : "";
            v[2].setText(it.dir ? date : Paths.size(it.size) + "   " + date);
            return row;
        }
    }

    private StateListDrawable selector() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFF2B5AA8);
        on.setCornerRadius(dp(10));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    /** "第2集" 排在 "第10集" 前面。 */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
