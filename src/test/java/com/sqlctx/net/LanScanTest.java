package com.sqlctx.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sqlctx --scan: đọc trả lời NetBIOS/mDNS và đoán loại thiết bị từ manh mối. */
class LanScanTest {

    @Test
    void đọcTrảLờiNbstatCủaMáyWindows() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[56]); // header 12 + tên 34 + type/class/ttl/len 10
        b.write(4);
        nbName(b, "DESKTOP-OEUK65F", 0x00, false);
        nbName(b, "WORKGROUP", 0x00, true);
        nbName(b, "DESKTOP-OEUK65F", 0x20, false);
        nbName(b, "NAMNV", 0x03, false);
        LanProbes.NetBios nb = LanProbes.parseNbstat(b.toByteArray());
        assertEquals("DESKTOP-OEUK65F", nb.name());
        assertEquals("WORKGROUP", nb.workgroup());
        assertEquals("NAMNV", nb.user());
        assertTrue(nb.fileShare());
        assertNull(LanProbes.parseNbstat(new byte[20]));
    }

    private static void nbName(ByteArrayOutputStream b, String name, int suffix, boolean group) {
        b.writeBytes(String.format("%-15s", name).getBytes(StandardCharsets.US_ASCII));
        b.write(suffix);
        b.write(group ? 0x84 : 0x04);
        b.write(0);
    }

    @Test
    void đọcTênNgượcDịchVụVàModelTừGóiMdns() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[]{0, 0, (byte) 0x84, 0, 0, 0, 0, 3, 0, 0, 0, 0}); // 3 bản ghi trả lời
        record(b, "13.168.168.192.in-addr.arpa", 12, dnsName("iPhone-3.local"));
        record(b, "_services._dns-sd._udp.local", 12, dnsName("_remotepairing._tcp.local"));
        byte[] txt = "model=iPhone15,2".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(txt.length);
        t.writeBytes(txt);
        record(b, "iPhone-3._device-info._tcp.local", 16, t.toByteArray());

        LanProbes.Mdns d = new LanProbes.Mdns();
        LanProbes.parseMdns(b.toByteArray(), d);
        assertEquals("iPhone-3", d.hostname);
        assertEquals(List.of("_remotepairing._tcp"), List.copyOf(d.services));
        assertEquals("iPhone15,2", d.model);
    }

    private static void record(ByteArrayOutputStream b, String name, int type, byte[] data) {
        b.writeBytes(dnsName(name));
        b.writeBytes(new byte[]{0, (byte) type, 0, 1, 0, 0, 0, 120, 0, (byte) data.length});
        b.writeBytes(data);
    }

    private static byte[] dnsName(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String l : name.split("\\.")) {
            b.write(l.length());
            b.writeBytes(l.getBytes(StandardCharsets.UTF_8));
        }
        b.write(0);
        return b.toByteArray();
    }

    @Test
    void đoánLoạiThiếtBị() {
        assertEquals(LanScan.Kind.IPHONE, kind(h -> h.ports = List.of(49152, 62078)));
        assertEquals(LanScan.Kind.WINDOWS, kind(h -> h.ttl = 128));
        assertEquals(LanScan.Kind.CAMERA, kind(h -> h.vendor = "Hangzhou Hikvision Digital Technology Co.,Ltd."));
        assertEquals(LanScan.Kind.ATTENDANCE, kind(h -> h.ports = List.of(4370, 8000)));
        assertEquals(LanScan.Kind.ROUTER, kind(h -> h.vendor = "TP-Link Systems Inc."));
        assertEquals(LanScan.Kind.GATEWAY, kind(h -> h.gateway = true));
        // card WiFi laptop + TTL 64 = Linux, kể cả khi tên có chữ "Desktop-PC" hay có xrdp (cổng 3389)
        assertEquals(LanScan.Kind.LINUX, kind(h -> {
            h.vendor = "AzureWave Technology Inc.";
            h.ttl = 64;
            h.ports = List.of(3389);
            h.mdns = new LanProbes.Mdns();
            h.mdns.hostname = "honglv-HP-Pro-Tower-280-G9-E-PCI-Desktop-PC";
        }));
        assertEquals(LanScan.Kind.PC_NO_PING, kind(h -> h.vendor = "AzureWave Technology Inc."));
        assertEquals(LanScan.Kind.MAC, kind(h -> h.netbios = new LanProbes.NetBios("BACHS-MAC-MINI", null, null, false)));
        assertEquals(LanScan.Kind.PHONE_PRIVATE, kind(h -> h.randomMac = true));
        assertEquals(LanScan.Kind.UNKNOWN, kind(h -> h.upnp = Map.of()));
    }

    private static LanScan.Kind kind(java.util.function.Consumer<LanScan.Host> setup) {
        LanScan.Host h = new LanScan.Host();
        h.ip = "192.168.1.10";
        h.mac = "9c:c7:d3:aa:af:52";
        setup.accept(h);
        return LanScan.guess(h);
    }

    @Test
    void macNgẫuNhiênVàĐổiIp() {
        assertTrue(LanProbes.randomMac("6e:b9:c4:5a:34:c6"));
        assertFalse(LanProbes.randomMac("9c:c7:d3:ac:8a:12"));
        assertEquals("192.168.168.30", LanScan.toIp(LanScan.toInt("192.168.168.30")));
        assertEquals("30.168.168.192.in-addr.arpa", LanProbes.reverseName("192.168.168.30"));
        assertEquals(List.of("AirPlay, Handoff,", "ScreenShare"), LanScan.wrap("AirPlay, Handoff, ScreenShare", 18));
    }

    @Test
    void đọcPhiênBảnWindowsVàTênMáyTừNtlmChallenge() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(200).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[10]); // rác phía trước (header SMB2, SPNEGO...)
        int at = b.position();
        byte[] name = "DESKTOP-1JB35BP".getBytes(StandardCharsets.UTF_16LE);
        byte[] domain = "CORP".getBytes(StandardCharsets.UTF_16LE);
        int infoLen = 4 + name.length + 4 + domain.length + 4;
        b.put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII)).putInt(2)
                .put(new byte[28])                                         // target name, flags, challenge, reserved
                .putShort((short) infoLen).putShort((short) infoLen).putInt(56)
                .put(new byte[]{10, 0}).putShort((short) 26100).put(new byte[]{0, 0, 0, 15});
        b.putShort((short) 3).putShort((short) name.length).put(name);
        b.putShort((short) 4).putShort((short) domain.length).put(domain);
        b.putInt(0);
        LanProbes.Smb smb = LanProbes.parseNtlmChallenge(java.util.Arrays.copyOf(b.array(), b.position()));
        assertEquals("DESKTOP-1JB35BP", smb.dnsName());
        assertEquals("CORP", smb.dnsDomain());
        assertEquals("Windows 11 24H2 / Server 2025 (10.0.26100)", smb.os());
        assertEquals(at, 10);
        assertNull(LanProbes.parseNtlmChallenge(new byte[80]));
    }

    @Test
    void rútGọnTênTrongChứngChỉ() {
        String dn = "1.2.840.113549.1.9.1=#16,CN=www.example.com,OU=IT,O=VNPTT,L=Earth,ST=HANOI,C=VN";
        assertEquals("www.example.com · VNPTT", LanScan.certFields(dn, false));
        assertEquals("www.example.com · VNPTT · IT · Earth · HANOI · VN", LanScan.certFields(dn, true));
    }

    @Test
    void máyCóSmbWindowsThậtLàWindowsDùChặnPing() {
        assertEquals(LanScan.Kind.WINDOWS, kind(h -> {
            h.vendor = "CLOUD NETWORK TECHNOLOGY SINGAPORE PTE. LTD.";
            h.smb = new LanProbes.Smb(10, 0, 26100, "DESKTOP-1JB35BP", null, null, null);
        }));
    }

    @Test
    void chiTiếtMộtDòngGomManhMốiVàVừaBềNgang() {
        com.sqlctx.util.I18n.set(com.sqlctx.util.I18n.Lang.VI, false);
        LanScan.Host h = new LanScan.Host();
        h.ip = "192.168.168.23";
        h.mac = "30:68:93:4d:e3:7a";
        h.web = List.of(new LanProbes.Web(80, "Login", "TP-LINK HTTPD/1.0", ""));
        h.cert = new LanProbes.Cert("CN=omadaeap.net,C=HK", "CN=omadaeap.net,C=HK", List.of(), "2039-12-31", true, false);
        h.netbios = new LanProbes.NetBios("AP", "WORKGROUP", null, true);
        String all = NetPrinter.fold(LanScan.clue(h, 200));
        assertEquals("\"login\" tp-link httpd · tls omadaeap.net · workgroup · chia se file", all);
        for (int w : new int[]{20, 30, 45}) {
            assertTrue(NetPrinter.width(LanScan.clue(h, w)) <= w, w + ": " + LanScan.clue(h, w));
        }
        // CN chỉ là mã băm (camera Hikvision) thì bỏ; không có gì thì rỗng
        h = new LanScan.Host();
        h.cert = new LanProbes.Cert("CN=b368bdf833c4648608dd1d345825d453", "", List.of(), "2029-05-30", true, false);
        assertEquals("", LanScan.clue(h, 80));
    }

    @Test
    void danhSáchMỗiMáy1HàngChữThườngĐủCột() {
        com.sqlctx.util.I18n.set(com.sqlctx.util.I18n.Lang.VI, false);
        LanScan.Host h = new LanScan.Host();
        h.ip = "192.168.168.14";
        h.mac = "9c:c7:d3:aa:b0:10";
        h.vendor = "AzureWave Technology Inc.";
        h.ttl = 128;
        h.rtt = 12.4;
        h.ports = List.of(139, 445);
        h.netbios = new LanProbes.NetBios("DESKTOP-ETL08N5", "WORKGROUP", null, true);
        h.smb = new LanProbes.Smb(10, 0, 26100, "DESKTOP-ETL08N5", null, "desktop-etl08n5", null);
        List<String> cols = LanScan.gridColumns();
        List<String> row = LanScan.gridRows(List.of(h)).getFirst();
        assertEquals(cols.size(), row.size());
        Map<String, String> r = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            r.put(cols.get(i), row.get(i));
        }
        assertEquals("DESKTOP-ETL08N5", r.get("TÊN"));
        assertEquals("Windows 11 24H2", r.get("LOẠI"));
        assertEquals("AzureWave Technology Inc.", r.get("HÃNG"));
        assertEquals("12", r.get("PING ms"));
        assertEquals("netbios:139 smb:445", r.get("CỔNG MỞ"));
        assertEquals("WORKGROUP · chia sẻ file", r.get("NetBIOS"));
        assertTrue(r.get("SMB").startsWith("Windows 11 24H2"), r.get("SMB"));
        assertEquals("", r.get("TLS"));
        assertFalse(String.join("", row).contains("\u001b"), "không mã màu");
    }

    @Test
    void đọcSnmpGetResponseSysDescrVàSysName() {
        // SNMP response: SEQUENCE{ ver=0, community="public", GetResponse{ reqid, 0, 0, varbinds{
        //   {1.3.6.1.2.1.1.1.0 = "RouterOS"}, {1.3.6.1.2.1.1.5.0 = "gw"} }}}
        byte[] descrOid = {0x2B, 6, 1, 2, 1, 1, 1, 0};
        byte[] nameOid = {0x2B, 6, 1, 2, 1, 1, 5, 0};
        byte[] vb1 = seq(cat(tlv(0x06, descrOid), tlv(0x04, "RouterOS".getBytes())));
        byte[] vb2 = seq(cat(tlv(0x06, nameOid), tlv(0x04, "gw".getBytes())));
        byte[] pdu = tlv(0xA2, cat(tlv(0x02, new byte[]{1}), tlv(0x02, new byte[]{0}), tlv(0x02, new byte[]{0}), seq(cat(vb1, vb2))));
        byte[] msg = seq(cat(tlv(0x02, new byte[]{0}), tlv(0x04, "public".getBytes()), pdu));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        LanProbes.berWalk(msg, 0, msg.length, out);
        assertEquals("RouterOS", out.get("1.3.6.1.2.1.1.1.0"));
        assertEquals("gw", out.get("1.3.6.1.2.1.1.5.0"));
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] tlv(int tag, byte[] content) {
        byte[] r = new byte[2 + content.length];
        r[0] = (byte) tag;
        r[1] = (byte) content.length; // chỉ test nội dung ngắn < 128
        System.arraycopy(content, 0, r, 2, content.length);
        return r;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    @Test
    void đọcMọiTxtCủaMdns() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[]{0, 0, (byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0}); // 1 bản ghi trả lời
        byte[] t1 = "md=Chromecast".getBytes(StandardCharsets.UTF_8);
        byte[] t2 = "fn=TV phong khach".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream txt = new ByteArrayOutputStream();
        txt.write(t1.length); txt.writeBytes(t1);
        txt.write(t2.length); txt.writeBytes(t2);
        record(b, "TV._googlecast._tcp.local", 16, txt.toByteArray());
        LanProbes.Mdns d = new LanProbes.Mdns();
        LanProbes.parseMdns(b.toByteArray(), d);
        assertEquals("Chromecast", d.model);
        assertEquals("TV phong khach", d.txt.get("fn"));
    }
}
