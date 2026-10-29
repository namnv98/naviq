package com.sqlctx.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tên thiết bị đi mạng qua máy này, lấy từ bảng cấp IP của dnsmasq (hotspot) và libvirt (máy ảo). */
class DevicesTest {

    @Test
    void đọcTênTừBảngDhcpCủaHotspotVàMáyẢo(@TempDir Path dir) throws IOException {
        Path leases = Files.writeString(dir.resolve("leases"), """
                1793297567 c2:58:a7:94:ed:15 10.42.0.54 namnv98 01:c2:58:a7:94:ed:15
                1793297600 aa:bb:cc:dd:ee:ff 10.42.0.77 * *
                duid 00:01:00:01:2c:aa
                """);
        Path nm = Files.createDirectory(dir.resolve("nm"));
        Files.writeString(nm.resolve("dnsmasq-wlan0.leases"), "1793297567 11:22:33:44:55:66 10.42.1.5 iPad *\n");
        Path libvirt = Files.createDirectory(dir.resolve("libvirt"));
        Files.writeString(libvirt.resolve("default.status"), """
                [
                  {"ip-address": "192.168.122.10", "mac-address": "52:54:00:aa:bb:cc", "hostname": "k8s-master", "expiry-time": 1},
                  {"ip-address": "192.168.122.11", "mac-address": "52:54:00:aa:bb:cd", "expiry-time": 1}
                ]
                """);
        Devices d = new Devices(List.of(leases, dir.resolve("không-có")), nm, libvirt);
        assertEquals("namnv98", d.name("10.42.0.54"));
        assertEquals("10.42.0.77", d.name("10.42.0.77"));     // thiết bị không báo tên: hiện IP
        assertEquals("iPad", d.name("10.42.1.5"));
        assertEquals("k8s-master", d.name("192.168.122.10"));
        assertEquals("192.168.122.11", d.name("192.168.122.11"));
        assertNull(d.name("8.8.8.8"));
    }
}
