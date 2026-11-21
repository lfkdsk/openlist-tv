package com.lfkdsk.openlisttv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 默认播放器:能打开视频网址的应用里选一个,或者每次询问。 */
final class Players {
    private Players() {}

    static final class Player {
        final String label;
        final ComponentName component;

        Player(String label, ComponentName component) {
            this.label = label;
            this.component = component;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    /** 装了的、能打开 http 视频的播放器。 */
    static List<Player> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Player> out = new LinkedHashMap<>();
        for (String sample : new String[]{"http://127.0.0.1/a.mkv", "http://127.0.0.1/a.mp4"}) {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(sample), "video/*");
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || out.containsKey(pkg)) continue;
                out.put(pkg, new Player(String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name)));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** 默认播放器;没设或已卸载返回 null(每次询问)。 */
    static Player current(Context c) {
        String flat = prefs(c).getString("component", null);
        if (flat == null) return null;
        for (Player p : installed(c)) if (p.component.flattenToString().equals(flat)) return p;
        return null;
    }

    static String currentLabel(Context c) {
        Player p = current(c);
        return p != null ? p.label : "每次询问";
    }

    /** 弹出选择框设默认播放器。 */
    static void choose(Activity a, Runnable done) {
        List<Player> list = installed(a);
        String[] labels = new String[list.size() + 1];
        labels[0] = "每次询问";
        Player cur = current(a);
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            labels[i + 1] = list.get(i).label;
            if (cur != null && cur.component.equals(list.get(i).component)) checked = i + 1;
        }
        new AlertDialog.Builder(a)
                .setTitle("默认播放器")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    SharedPreferences.Editor e = prefs(a).edit();
                    if (which == 0) e.remove("component");
                    else e.putString("component", list.get(which - 1).component.flattenToString());
                    e.apply();
                    d.dismiss();
                    if (done != null) done.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 播放:有默认播放器就直接用它,否则(或 alwaysAsk)弹系统的选择框。
     * title 给 MX / Nova / VLC 这类认 "title" 参数的播放器显示片名。
     */
    static void play(Activity a, String url, String name, boolean alwaysAsk) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), Paths.mime(name))
                .putExtra("title", name)
                .putExtra("filename", name);
        Player p = alwaysAsk ? null : current(a);
        try {
            if (p != null) a.startActivity(new Intent(i).setComponent(p.component));
            else a.startActivity(Intent.createChooser(i, "用哪个播放器打开「" + name + "」"));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, "没有能播放这个文件的应用", Toast.LENGTH_LONG).show();
        }
    }
}
