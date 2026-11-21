package com.lfkdsk.openlisttv;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/** 纯逻辑(可单测):OpenList 路径拼接、播放地址、文件类型、大小显示。 */
final class Paths {
    private Paths() {}

    enum Kind { DIR, VIDEO, AUDIO, IMAGE, SUBTITLE, OTHER }

    static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || dir.equals("/")) return "/" + name;
        return (dir.endsWith("/") ? dir : dir + "/") + name;
    }

    static String parent(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) return null;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    /** OpenList 的直链(/d/路径?sign=…),每段路径单独编码,保留 /。 */
    static String playUrl(String base, String path, String sign) {
        StringBuilder sb = new StringBuilder(base).append("/d");
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(encode(seg));
        }
        if (sign != null && !sign.isEmpty()) sb.append("?sign=").append(encode(sign));
        return sb.toString();
    }

    static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    static Kind kind(String name, boolean dir) {
        if (dir) return Kind.DIR;
        String ext = ext(name);
        switch (ext) {
            case "mp4": case "m4v": case "mkv": case "mov": case "avi": case "wmv": case "ts": case "m2ts": case "mts":
            case "webm": case "flv": case "rmvb": case "rm": case "mpg": case "mpeg": case "iso": case "vob": case "3gp":
                return Kind.VIDEO;
            case "mp3": case "flac": case "m4a": case "wav": case "aac": case "ogg": case "ape": case "dts": case "opus": case "wma":
                return Kind.AUDIO;
            case "jpg": case "jpeg": case "png": case "webp": case "gif": case "bmp": case "heic":
                return Kind.IMAGE;
            case "srt": case "ass": case "ssa": case "vtt": case "sub": case "sup":
                return Kind.SUBTITLE;
            default:
                return Kind.OTHER;
        }
    }

    /** 交给播放器的 MIME:按类别给通配,播放器自己认容器。 */
    static String mime(String name) {
        switch (kind(name, false)) {
            case VIDEO: return "video/*";
            case AUDIO: return "audio/*";
            case IMAGE: return "image/*";
            default: return "*/*";
        }
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
