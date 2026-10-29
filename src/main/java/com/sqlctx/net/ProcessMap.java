package com.sqlctx.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cổng local → tên chương trình đang dùng nó. Ưu tiên eBPF (bpftrace móc vào kernel ngay lúc chương trình mở kết nối
 * TCP / gửi UDP nên không sót kể cả kết nối chỉ sống vài micro giây); không có bpftrace thì tra {@code ss -tuanp}
 * (có thể sót kết nối đóng quá nhanh).
 */
final class ProcessMap {

    private static final String BPF_SCRIPT = """
            fexit:tcp_v4_connect, fexit:tcp_v6_connect {
              printf("tcp %d %d %s\\n", args.sk->__sk_common.skc_num, pid, curtask->group_leader->comm);
            }
            fexit:udp_sendmsg, fexit:udpv6_sendmsg /!@seen[args.sk->__sk_common.skc_num, pid]/ {
              @seen[args.sk->__sk_common.skc_num, pid] = 1;
              printf("udp %d %d %s\\n", args.sk->__sk_common.skc_num, pid, curtask->group_leader->comm);
            }
            END { clear(@seen); }
            """;
    private static final Pattern SS_PROCESS = Pattern.compile("users:\\(\\(\"([^\"]+)\",pid=(\\d+)");

    private final boolean enabled;
    /** Chương trình (và PID, 0 nếu không biết) đang dùng 1 cổng. */
    record Owner(String name, int pid) {
    }

    private final Map<String, Owner> fromBpf = new ConcurrentHashMap<>();   // "tcp:54321" -> curl
    private final Map<String, Owner> fromSs = new ConcurrentHashMap<>();    // "tcp:10.0.0.5:54321" / "tcp:*:22"
    private final Set<String> local = ConcurrentHashMap.newKeySet();
    private final Devices devices = new Devices();
    private volatile Process bpf;
    private long lastRefresh;

    ProcessMap(boolean enabled) {
        this.enabled = enabled;
        loadLocalAddresses();
        if (enabled) {
            startBpf();
            refreshSs(true);
        }
    }

    boolean usingBpf() {
        return bpf != null && bpf.isAlive();
    }

    boolean isLocal(String ip) {
        return local.contains(ip);
    }

    private void loadLocalAddresses() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    local.add(NetFormat.compactIp(a.getHostAddress()));
                }
            }
        } catch (SocketException ignored) {
        }
        local.add("127.0.0.1");
        local.add("::1");
    }

    private void startBpf() {
        if (!NetCli.onPath("bpftrace")) {
            return;
        }
        // Canh chừng: sqlctx chết (kể cả kill -9) thì bpftrace cũng tắt theo, không để hook sót lại trong kernel.
        String watchdog = "bpftrace -B line -e \"$1\" & B=$!; while kill -0 " + ProcessHandle.current().pid()
                + " 2>/dev/null && kill -0 $B 2>/dev/null; do sleep 1; done; kill -INT $B 2>/dev/null; wait $B";
        try {
            bpf = new ProcessBuilder(NetCli.sudo("sh", "-c", watchdog, "sqlctx-net-bpf", BPF_SCRIPT))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return;
        }
        CountDownLatch attached = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(bpf.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Attached")) {
                        attached.countDown();
                    }
                    String[] parts = line.split(" ", 4);
                    if (parts.length == 4 && parts[1].chars().allMatch(Character::isDigit)) {
                        fromBpf.put(parts[0] + ":" + parts[1], new Owner(parts[3].strip(), parseInt(parts[2])));
                    }
                }
            } catch (IOException ignored) {
            }
            attached.countDown();
        }, "net-bpf");
        reader.setDaemon(true);
        reader.start();
        try {
            attached.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!bpf.isAlive()) {
            bpf = null;
        }
    }

    void stop() {
        Process p = bpf;
        if (p != null && p.isAlive()) {
            // báo watchdog dừng bpftrace ngay (tự nó cũng sẽ dừng trong 1s khi sqlctx thoát)
            try {
                new ProcessBuilder(NetCli.sudo("pkill", "-INT", "-f", "kill -0 " + ProcessHandle.current().pid() + " "))
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                        .waitFor(2, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
            }
        }
    }

    private void refreshSs(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefresh < 50) {
            return;
        }
        lastRefresh = now;
        try {
            Process p = new ProcessBuilder(NetCli.sudo("ss", "-tuanpH")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                if (fromSs.size() > 50_000) {
                    fromSs.clear();
                }
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = SS_PROCESS.matcher(line);
                    String[] parts = line.trim().split("\\s+");
                    if (!m.find() || parts.length < 5) {
                        continue;
                    }
                    String addr = parts[4];
                    int colon = addr.lastIndexOf(':');
                    String host = addr.substring(0, colon).replace("[", "").replace("]", "").replaceAll("%.*$", "")
                            .replaceFirst("^::ffff:", "");
                    if (host.equals("0.0.0.0") || host.equals("*") || host.equals("::")) {
                        host = "*";
                    }
                    fromSs.put(parts[0] + ":" + normalize(host) + ":" + addr.substring(colon + 1),
                            new Owner(m.group(1), parseInt(m.group(2))));
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ss có thể in IPv6 khác cách viết (vd ::ffff:, số 0 đầu) - quy về đúng dạng Pcap dùng. */
    private static String normalize(String ip) {
        if (!ip.contains(":")) {
            return ip;
        }
        try {
            return NetFormat.compactIp(InetAddress.getByName(ip).getHostAddress());
        } catch (IOException e) {
            return ip;
        }
    }

    /**
     * Tên chương trình của đầu nào là của máy này; không phải thì tên thiết bị khác (trong ngoặc vuông) đi mạng qua máy
     * này, vd điện thoại vào hotspot; null nếu không biết.
     */
    String lookup(String proto, Pcap.Endpoint... endpoints) {
        Owner o = owner(proto, endpoints);
        return o == null ? null : o.name();
    }

    /** Như {@link #lookup} nhưng kèm PID. */
    Owner owner(String proto, Pcap.Endpoint... endpoints) {
        if (!enabled) {
            return null;
        }
        Owner program = program(proto, endpoints);
        if (program != null) {
            return program;
        }
        for (Pcap.Endpoint e : endpoints) {
            String device = local.contains(e.ip()) ? null : devices.name(e.ip());
            if (device != null) {
                return new Owner("[" + device + "]", 0);
            }
        }
        return null;
    }

    private Owner program(String proto, Pcap.Endpoint... endpoints) {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean anyLocal = false;
            for (Pcap.Endpoint e : endpoints) {
                if (e.port() == 0 || !local.contains(e.ip())) {
                    continue;
                }
                anyLocal = true;
                Owner name = fromBpf.get(proto + ":" + e.port());
                if (name == null) {
                    name = fromSs.get(proto + ":" + e.ip() + ":" + e.port());
                }
                if (name == null) {
                    name = fromSs.get(proto + ":*:" + e.port());
                }
                if (name != null) {
                    return name;
                }
            }
            if (!anyLocal) {
                return null;
            }
            refreshSs(false);
        }
        return null;
    }
}
