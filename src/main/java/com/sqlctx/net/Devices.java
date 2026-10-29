package com.sqlctx.net;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IP → tên thiết bị khác đang đi mạng qua máy này (điện thoại vào hotspot, máy ảo...), đọc từ bảng cấp IP (DHCP) của
 * dnsmasq: hotspot tự dựng, hotspot của NetworkManager, mạng ảo libvirt. Thiết bị không báo tên thì hiện IP.
 */
final class Devices {

    private static final List<Path> LEASES = List.of(Path.of("/run/hotspot/leases"),
            Path.of("/var/lib/misc/dnsmasq.leases"));
    private static final Pattern LIBVIRT_IP = Pattern.compile("\"ip-address\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LIBVIRT_HOST = Pattern.compile("\"hostname\"\\s*:\\s*\"([^\"]+)\"");
    private static final long RELOAD_NANOS = 2_000_000_000L;

    private final List<Path> leases;
    private final Path networkManager;
    private final Path libvirt;
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private long lastLoad;

    Devices() {
        this(LEASES, Path.of("/var/lib/NetworkManager"), Path.of("/var/lib/libvirt/dnsmasq"));
    }

    Devices(List<Path> leases, Path networkManager, Path libvirt) {
        this.leases = leases;
        this.networkManager = networkManager;
        this.libvirt = libvirt;
    }

    /** Tên thiết bị cho IP có trong bảng DHCP; null nếu IP không phải thiết bị được máy này cấp IP. */
    String name(String ip) {
        if (lastLoad == 0 || System.nanoTime() - lastLoad > RELOAD_NANOS) {
            reload();
        }
        return names.get(ip);
    }

    private void reload() {
        lastLoad = System.nanoTime();
        List<Path> files = new ArrayList<>(leases);
        files.addAll(list(networkManager, "dnsmasq-*.leases"));
        for (Path f : files) {
            for (String line : read(f).split("\n")) {
                // dnsmasq: <hết hạn> <mac> <ip> <tên|*> <client-id>
                String[] cols = line.trim().split("\\s+");
                if (cols.length >= 4) {
                    names.put(NetFormat.compactIp(cols[2]), "*".equals(cols[3]) ? cols[2] : cols[3]);
                }
            }
        }
        for (Path f : list(libvirt, "*.status")) {
            // libvirt: mảng JSON [{"ip-address": ..., "hostname": ...}, ...]
            for (String entry : read(f).split("}")) {
                Matcher ip = LIBVIRT_IP.matcher(entry);
                Matcher host = LIBVIRT_HOST.matcher(entry);
                if (ip.find()) {
                    names.put(NetFormat.compactIp(ip.group(1)), host.find() ? host.group(1) : ip.group(1));
                }
            }
        }
    }

    private static List<Path> list(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, glob)) {
            s.forEach(out::add);
        } catch (IOException | SecurityException ignored) {
        }
        return out;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException | SecurityException e) {
            return "";
        }
    }
}
