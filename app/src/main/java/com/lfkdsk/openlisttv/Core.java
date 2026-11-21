package com.lfkdsk.openlisttv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯逻辑(不碰 android.*,可以 JVM 单测):读 OpenList 输出、配置端口、日志缓冲、重启退避。 */
final class Core {
    private Core() {}

    static final int DEFAULT_PORT = 5244;

    // 第一次启动:"Successfully created the admin user and the initial password is: xxxx"
    private static final Pattern INITIAL = Pattern.compile("initial password is:\\s*(\\S+)");
    // admin random / admin set 的输出里有一行 "password: xxxx"
    private static final Pattern PASSWORD = Pattern.compile("(?m)^\\s*(?:.*\\s)?password:\\s*(\\S+)\\s*$");
    private static final Pattern PORT = Pattern.compile("\"http_port\"\\s*:\\s*(-?\\d+)");
    // logrus 的颜色控制符
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    static String initialPassword(String line) {
        Matcher m = INITIAL.matcher(stripAnsi(line));
        return m.find() ? m.group(1) : null;
    }

    static String passwordFromAdminOutput(String out) {
        Matcher m = PASSWORD.matcher(stripAnsi(out));
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /** data/config.json 里的 http_port;没有配置文件或读不出来返回默认 5244。 */
    static int httpPort(String configJson) {
        if (configJson == null) return DEFAULT_PORT;
        Matcher m = PORT.matcher(configJson);
        if (!m.find()) return DEFAULT_PORT;
        int p = Integer.parseInt(m.group(1));
        return p > 0 && p < 65536 ? p : DEFAULT_PORT;
    }

    // 配置里 "log": { "enable": true, ... }:开着时日志只写进 data/log/log.log,不打到屏幕输出
    private static final Pattern FILE_LOG = Pattern.compile("(\"log\"\\s*:\\s*\\{[^{}]*?\"enable\"\\s*:\\s*)true");

    /** 关掉 OpenList 的日志文件,让日志都走标准输出(我们自己收集、显示)。没有要改的返回原文。 */
    static String disableFileLog(String configJson) {
        if (configJson == null) return null;
        return FILE_LOG.matcher(configJson).replaceFirst("$1false");
    }

    static String stripAnsi(String s) {
        return s == null ? "" : ANSI.matcher(s).replaceAll("");
    }

    /** 服务器意外退出后的重启间隔(秒):越失败越慢,稳定跑满 1 分钟后重置。 */
    static final int[] BACKOFF = {2, 5, 10, 30, 60};

    static int backoffSeconds(int failuresInRow) {
        return BACKOFF[Math.min(Math.max(failuresInRow, 0), BACKOFF.length - 1)];
    }

    /** 最近 N 行日志。 */
    static final class LogBuffer {
        private final int max;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        LogBuffer(int max) {
            this.max = max;
        }

        synchronized void add(String line) {
            lines.addLast(stripAnsi(line));
            while (lines.size() > max) lines.removeFirst();
        }

        synchronized List<String> tail(int n) {
            List<String> all = new ArrayList<>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }
}
