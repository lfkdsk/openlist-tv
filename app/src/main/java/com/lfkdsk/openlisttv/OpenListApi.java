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
 * 登录顺序:记住的账号 → 初始密码(还没改过时) → 访客(管理页里开了访客时)。
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

    private final OpenListServer server;
    private final SharedPreferences prefs;
    private String token;
    private boolean guest;

    OpenListApi(Context context) {
        server = OpenListServer.get(context);
        prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    }

    String base() {
        return "http://127.0.0.1:" + server.port();
    }

    String savedUser() {
        return prefs.getString("user", "admin");
    }

    /** 用户在登录页输入的账号;登录成功才记住。 */
    void login(String user, String password) throws IOException, LoginRequired {
        token = doLogin(user, password);
        guest = false;
        prefs.edit().putString("user", user).putString("pass", password).apply();
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
        String pass = prefs.getString("pass", null);
        if (pass != null) {
            try {
                token = doLogin(savedUser(), pass);
                return;
            } catch (LoginRequired e) {
                // 密码在网页里改过了:往下试
            }
        }
        String initial = server.initialPassword();
        if (initial != null) {
            try {
                token = doLogin("admin", initial);
                return;
            } catch (LoginRequired ignored) {
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
