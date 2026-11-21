package com.lfkdsk.openlisttv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 调本机 OpenList 的接口(http://127.0.0.1:端口/api/...)。
 * 电视本机浏览用一个内置只读账号 tv_local(密码随机,只存在电视上):第一次用管理员身份登录后自动创建,
 * 之后一直用它自动登录,管理员密码不再保存,改了也不影响。
 * 登录顺序:tv_local → 管理员(初始密码或用户输入一次)并顺带建 tv_local → 访客。
 * OpenList 的登录凭证只在服务器内存里有效,服务器重启后自动重新登录。阻塞调用,不要在主线程用。
 */
final class OpenListApi {
    static final class LoginRequired extends Exception {
        LoginRequired(String msg) { super(msg); }
    }

    static final class Item {
        final String name;
        final boolean dir;
        final long size;
        final String modified;
        final String sign;

        Item(String name, boolean dir, long size, String modified, String sign) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
            this.sign = sign;
        }
    }

    static final String LOCAL_USER = "tv_local";
    // 1: 访问带密码的目录不用再输密码;12: 可以读压缩包。不给写入、删除、WebDAV。
    static final int LOCAL_PERMISSION = (1 << 1) | (1 << 12);

    private final OpenListServer server;
    private final SharedPreferences prefs;
    private String token;
    private boolean guest;

    OpenListApi(Context context) {
        server = OpenListServer.get(context);
        prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    }

    /**
     * 安装向导是否完成(/api/public/init_status)。新版 OpenList 不再自动建带初始密码的管理员,
     * 第一次打开管理页时由安装向导让用户自己设账号密码。拿不到返回 null。
     */
    static Boolean initialized(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/public/init_status").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            JSONObject j = new JSONObject(read(c.getInputStream()));
            c.disconnect();
            JSONObject d = j.optJSONObject("data");
            return d != null ? d.optBoolean("initialized", true) : null;
        } catch (Exception e) {
            return null;
        }
    }

    String base() {
        return "http://127.0.0.1:" + server.port();
    }

    String savedUser() {
        return prefs.getString("user", "admin");
    }

    static boolean hasLocalUser(Context context) {
        return context.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("localPass", null) != null;
    }

    /** 用户在登录页输入的账号。是管理员就顺带建好 tv_local,之后不再需要、也不保存这个密码。 */
    void login(String user, String password) throws IOException, LoginRequired {
        String t = doLogin(user, password);
        guest = false;
        if (provisionLocal(t)) {
            token = doLogin(LOCAL_USER, prefs.getString("localPass", ""));
            prefs.edit().putString("user", user).remove("pass").apply();
        } else {
            // 不是管理员(建不了用户):只能记住这个账号
            token = t;
            prefs.edit().putString("user", user).putString("pass", password).apply();
        }
    }

    /** 用管理员凭证建(或重置)tv_local,成功后把它的随机密码存在电视上。 */
    private boolean provisionLocal(String adminToken) {
        String pass = OpenListServer.randomPassword(24);
        try {
            JSONObject u = new JSONObject();
            u.put("username", LOCAL_USER);
            u.put("password", pass);
            u.put("base_path", "/");
            u.put("role", 0);
            u.put("permission", LOCAL_PERMISSION);
            u.put("disabled", false);
            JSONObject r = call("POST", "/api/admin/user/create", u, adminToken);
            if (r.optInt("code") != 200) {
                // 已经有了(比如重装过应用):找到它,把密码重置成新的
                JSONObject list = call("GET", "/api/admin/user/list?page=1&per_page=500", null, adminToken);
                JSONObject data = list.optJSONObject("data");
                JSONArray content = data != null ? data.optJSONArray("content") : null;
                if (content == null) return false;
                long id = -1;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject o = content.optJSONObject(i);
                    if (o != null && LOCAL_USER.equals(o.optString("username"))) id = o.optLong("id");
                }
                if (id < 0) return false;
                u.put("id", id);
                r = call("POST", "/api/admin/user/update", u, adminToken);
                if (r.optInt("code") != 200) return false;
            }
        } catch (Exception e) {
            return false;
        }
        prefs.edit().putString("localPass", pass).apply();
        return true;
    }

    void forget() {
        prefs.edit().remove("pass").apply();
        token = null;
    }

    private String doLogin(String user, String password) throws IOException, LoginRequired {
        JSONObject body = new JSONObject();
        try {
            body.put("username", user);
            body.put("password", password);
        } catch (Exception ignored) {
        }
        JSONObject r = call("POST", "/api/auth/login", body, null);
        if (r.optInt("code") != 200) throw new LoginRequired(r.optString("message", "登录失败"));
        return r.optJSONObject("data").optString("token");
    }

    /** 确保有可用身份;拿不到就抛 LoginRequired(界面弹登录页)。 */
    private void ensureSession() throws IOException, LoginRequired {
        if (token != null || guest) return;
        String local = prefs.getString("localPass", null);
        if (local != null) {
            try {
                token = doLogin(LOCAL_USER, local);
                return;
            } catch (LoginRequired e) {
                // tv_local 被删了或改了密码:往下用管理员重新建
                prefs.edit().remove("localPass").apply();
            }
        }
        // 管理员:之前记住的(旧版本存过)或初始密码,登录成功就建 tv_local
        String pass = prefs.getString("pass", null);
        String initial = server.initialPassword();
        String[][] admins = {{savedUser(), pass}, {"admin", initial}};
        for (String[] a : admins) {
            if (a[1] == null) continue;
            try {
                String t = doLogin(a[0], a[1]);
                if (provisionLocal(t)) {
                    token = doLogin(LOCAL_USER, prefs.getString("localPass", ""));
                    prefs.edit().remove("pass").apply();
                } else {
                    token = t;
                }
                return;
            } catch (LoginRequired e) {
                // 密码在网页里改过了:往下试
            }
        }
        // 访客:不带凭证请求自己的信息,没被禁用就能用
        JSONObject me = call("GET", "/api/me", null, null);
        JSONObject d = me.optJSONObject("data");
        if (me.optInt("code") == 200 && d != null && !d.optBoolean("disabled", true)) {
            guest = true;
            return;
        }
        throw new LoginRequired("需要登录 OpenList(或者在管理页里开启访客)");
    }

    /** 列目录。凭证失效(服务器重启过)时重新登录一次再试。 */
    List<Item> list(String path) throws IOException, LoginRequired {
        JSONObject body = new JSONObject();
        try {
            body.put("path", path);
            body.put("password", "");
            body.put("page", 1);
            body.put("per_page", 0);
            body.put("refresh", false);
        } catch (Exception ignored) {
        }
        JSONObject r = authed("/api/fs/list", body);
        if (r.optInt("code") != 200) throw new IOException(r.optString("message", "读取失败"));
        List<Item> out = new ArrayList<>();
        JSONObject data = r.optJSONObject("data");
        JSONArray content = data != null ? data.optJSONArray("content") : null;
        if (content == null) return out;
        for (int i = 0; i < content.length(); i++) {
            JSONObject o = content.optJSONObject(i);
            if (o == null) continue;
            out.add(new Item(o.optString("name"), o.optBoolean("is_dir"), o.optLong("size"), o.optString("modified"), o.optString("sign", "")));
        }
        return out;
    }

    private JSONObject authed(String api, JSONObject body) throws IOException, LoginRequired {
        ensureSession();
        JSONObject r = call("POST", api, body, guest ? null : token);
        if (r.optInt("code") == 401) {
            // 服务器重启后旧凭证作废
            token = null;
            guest = false;
            ensureSession();
            r = call("POST", api, body, guest ? null : token);
        }
        if (r.optInt("code") == 401) throw new LoginRequired(r.optString("message", "需要登录"));
        return r;
    }

    private JSONObject call(String method, String api, JSONObject body, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + api).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("code", code);
                err.put("message", "HTTP " + code);
            } catch (Exception ignored) {
            }
            return err;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
