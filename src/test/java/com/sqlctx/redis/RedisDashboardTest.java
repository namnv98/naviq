package com.sqlctx.redis;

import com.sqlctx.ui.Dashboard;
import com.sqlctx.ui.Screen;
import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisDashboardTest {

    @BeforeEach
    void english() {
        I18n.set(I18n.Lang.EN, false);
    }

    @AfterEach
    void vietnamese() {
        I18n.set(I18n.Lang.VI, false);
    }

    private static RedisStats.Node node(String address, boolean master, int ops) {
        return new RedisStats.Node(address, master, master ? 1 : -1, RedisStats.parseInfo("""
                redis_version:7.4.11
                instantaneous_ops_per_sec:%d
                used_memory:1048576
                used_memory_human:1.00M
                maxmemory:0
                total_system_memory:17179869184
                total_system_memory_human:16.00G
                connected_clients:3
                keyspace_hits:%d
                keyspace_misses:%d
                role:%s
                db0:keys=10,expires=0,avg_ttl=0
                """.formatted(ops, 9 + 9 * (ops / 100), 1 + ops / 100, master ? "master" : "slave")), null);
    }

    @Test
    void vừaMànHìnhVàCóChiTiết() {
        RedisProfile p = new RedisProfile("dev", "127.0.0.1", 6379, null, null, 0, false, false, null, null);
        RedisDashboard d = new RedisDashboard(null, p);
        for (int i = 0; i < 3; i++) {
            d.add(new RedisStats.Sample(i * 2000L, List.of(node("10.0.0.1:6379", true, 100 * i), node("10.0.0.2:6379", false, 5),
                    new RedisStats.Node("10.0.0.3:6379", true, 0, java.util.Map.of(), "refused")), "ok", List.of()));
        }
        for (int width : new int[]{80, 140}) {
            Dashboard.State st = new Dashboard.State();
            st.intervalMs = 2000;
            List<String> screen = d.render(width, 30, st);
            screen.forEach(l -> assertTrue(Screen.width(l) <= width, width + ": " + l));
            String all = String.join("\n", screen).replaceAll("\u001b\\[[0-9;]*m", "");
            for (String expected : List.of("REDIS", "● ok", "ops/s", "205", "no maxmemory", "90.0%", "10.0.0.2:6379", "replica",
                    "✖ down", "cannot get INFO - refused", "maxmemory not set")) {
                assertTrue(all.contains(expected), width + " thiếu " + expected + "\n" + all);
            }
            st.detail = true;
            String detail = String.join("\n", d.render(width, 30, st)).replaceAll("\u001b\\[[0-9;]*m", "");
            assertTrue(detail.contains("10.0.0.1:6379 · master") && detail.contains("redis_version") && detail.contains("Keyspace"),
                    detail);
        }
    }
}
