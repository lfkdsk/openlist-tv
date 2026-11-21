package com.lfkdsk.openlisttv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathsTest {
    @Test
    public void joinsAndWalksUp() {
        assertEquals("/阿里云盘", Paths.join("/", "阿里云盘"));
        assertEquals("/阿里云盘/电影", Paths.join("/阿里云盘", "电影"));
        assertEquals("/阿里云盘/电影", Paths.join("/阿里云盘/", "电影"));
        assertEquals("/阿里云盘", Paths.parent("/阿里云盘/电影"));
        assertEquals("/", Paths.parent("/阿里云盘"));
        assertNull(Paths.parent("/"));
    }

    @Test
    public void playUrlEncodesEachSegment() {
        assertEquals("http://127.0.0.1:5244/d/%E7%94%B5%E5%BD%B1/A%20B%23%E7%AC%AC1%E9%9B%86.mkv?sign=ab%3D%3D",
                Paths.playUrl("http://127.0.0.1:5244", "/电影/A B#第1集.mkv", "ab=="));
        assertEquals("http://h/d/x.mp4", Paths.playUrl("http://h", "/x.mp4", ""));
    }

    @Test
    public void kindsAndMime() {
        assertEquals(Paths.Kind.VIDEO, Paths.kind("a.MKV", false));
        assertEquals(Paths.Kind.DIR, Paths.kind("a.mkv", true));
        assertEquals(Paths.Kind.SUBTITLE, Paths.kind("a.ass", false));
        assertEquals("video/*", Paths.mime("x.rmvb"));
        assertEquals("audio/*", Paths.mime("x.flac"));
        assertEquals("*/*", Paths.mime("x.nfo"));
    }

    @Test
    public void sizes() {
        assertEquals("512 B", Paths.size(512));
        assertEquals("1.5 KB", Paths.size(1536));
        assertEquals("4.3 GB", Paths.size(4617089843L));
    }

    @Test
    public void naturalOrder() {
        assertTrue(BrowserActivity.naturalCompare("第2集", "第10集") < 0);
        assertTrue(BrowserActivity.naturalCompare("S01E09", "S01E10") < 0);
        assertTrue(BrowserActivity.naturalCompare("a", "B") < 0);
        assertEquals(0, BrowserActivity.naturalCompare("x01", "x1"));
    }
}
