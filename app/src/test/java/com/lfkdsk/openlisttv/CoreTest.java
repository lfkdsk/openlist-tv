package com.lfkdsk.openlisttv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class CoreTest {
    @Test
    public void readsInitialPasswordThroughAnsiColors() {
        String line = "\u001B[36mINFO\u001B[0m[2026-11-22 00:24:25] Successfully created the admin user and the initial password is: aB3dE9fG";
        assertEquals("aB3dE9fG", Core.initialPassword(line));
        assertNull(Core.initialPassword("start HTTP server @ 0.0.0.0:5244"));
    }

    @Test
    public void readsPasswordFromAdminRandomOutput() {
        String out = "INFO[0000] reading config file: data/config.json\nINFO[0000] admin user's info: \nusername: admin\npassword: Zq81xK2m\n";
        assertEquals("Zq81xK2m", Core.passwordFromAdminOutput(out));
        assertNull(Core.passwordFromAdminOutput("FATA[0000] failed to open database"));
    }

    @Test
    public void readsPortFromConfig() {
        assertEquals(5244, Core.httpPort(null));
        assertEquals(5244, Core.httpPort("{\"scheme\":{}}"));
        assertEquals(5300, Core.httpPort("{\"scheme\": {\"address\": \"0.0.0.0\", \"http_port\": 5300, \"https_port\": -1}}"));
        assertEquals(5244, Core.httpPort("{\"http_port\": -1}"));
    }

    @Test
    public void disablesOnlyTheLogFileSwitch() {
        String cfg = "{\n  \"force\": false,\n  \"log\": {\n    \"enable\": true,\n    \"name\": \"data/log/log.log\",\n    \"max_size\": 50\n  },\n  \"tasks\": {\"copy\": {\"enable\": true}}\n}";
        String out = Core.disableFileLog(cfg);
        assertEquals(true, out.contains("\"log\": {\n    \"enable\": false"));
        assertEquals(true, out.contains("\"copy\": {\"enable\": true}"));
        assertEquals(out, Core.disableFileLog(out));
        assertNull(Core.disableFileLog(null));
    }

    @Test
    public void backoffGrowsAndCaps() {
        assertEquals(2, Core.backoffSeconds(0));
        assertEquals(10, Core.backoffSeconds(2));
        assertEquals(60, Core.backoffSeconds(99));
    }

    @Test
    public void logBufferKeepsLastLinesWithoutColors() {
        Core.LogBuffer b = new Core.LogBuffer(3);
        for (String s : new String[]{"a", "b", "\u001B[31mc\u001B[0m", "d"}) b.add(s);
        assertEquals(Arrays.asList("b", "c", "d"), b.tail(10));
        assertEquals(Arrays.asList("c", "d"), b.tail(2));
    }
}
