package com.sqlctx.net;

import com.sqlctx.net.Pcap.Endpoint;
import com.sqlctx.net.Pcap.Packet;
import com.sqlctx.util.I18n;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chế độ chi tiết của sqlctx --net: mỗi gói điều khiển TCP 1 dòng, tắt thì không có. */
class NetAnalyzerTest {

    @BeforeAll
    static void tiếngViệt() {
        I18n.set(I18n.Lang.VI, false);
    }

    private static final Endpoint CLIENT = new Endpoint("10.0.0.5", 51000);
    private static final Endpoint SERVER = new Endpoint("10.0.0.9", 5432);

    /** Kết nối ngắn: bắt tay, 1 gói dữ liệu mỗi chiều, đóng 2 chiều. Thời gian cách nhau đủ xa để không bị coi là gói trùng. */
    private static List<Packet> shortConnection() {
        return List.of(
                Packet.tcp(1.000, false, CLIENT, SERVER, 100, Packet.SYN, 64240, 0, new byte[0]),
                Packet.tcp(1.040, false, SERVER, CLIENT, 500, Packet.SYN | Packet.ACK, 65160, 0, new byte[0]),
                Packet.tcp(1.045, false, CLIENT, SERVER, 101, Packet.ACK, 502, 0, new byte[0]),
                Packet.tcp(1.050, false, CLIENT, SERVER, 101, Packet.ACK, 502, 3, "abc".getBytes()),
                Packet.tcp(1.090, false, SERVER, CLIENT, 501, Packet.ACK, 502, 2, "ok".getBytes()),
                Packet.tcp(1.100, false, CLIENT, SERVER, 104, Packet.FIN | Packet.ACK, 502, 0, new byte[0]),
                Packet.tcp(1.140, false, SERVER, CLIENT, 503, Packet.FIN | Packet.ACK, 502, 0, new byte[0]));
    }

    private static List<String> run(boolean verbose, List<Packet> packets) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter printer = new NetPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), 200);
        printer.verbose(verbose);
        NetAnalyzer a = new NetAnalyzer(printer, new ProcessMap(false), Map.of());
        packets.forEach(a::packet);
        printer.close();
        return buf.toString(StandardCharsets.UTF_8).lines().map(NetPrinter::fold).toList();
    }

    @Test
    void chếĐộChiTiếtVẽSơĐồTuầnTựKèmKhoảngCách() {
        List<String> lines = run(true, shortConnection());
        String all = String.join("\n", lines);
        // mũi tên trái -> phải là chương trình gửi, phải -> trái là đích gửi về; thời gian căn phải
        String[] expected = {"│── syn xin ket noi ─", "│◀─", " syn-ack server dong y ──│   +40 ms", "│── ack bat tay xong",
                "│── fin client dong chieu gui", " fin server dong chieu gui ──│   +40 ms"};
        int at = -1;
        for (String e : expected) {
            int i = all.indexOf(e);
            assertTrue(i > at, "thiếu hoặc sai thứ tự: " + e + "\n" + all);
            at = i;
        }
        assertEquals(1, lines.stream().filter(l -> l.contains("── ack")).count(), "ACK của gói dữ liệu không vẽ thêm");
        // dòng mở in sau bắt tay mang giờ lúc in, không chen giờ cũ (1.000) vào sau 1.045
        int open = all.indexOf("mo postgresql");
        assertTrue(open > all.indexOf("── ack") && !all.substring(open - 120, open).contains("01.000"), all);
    }

    @Test
    void mũiTênVừaĐúngBềNgangCột() {
        for (int w : new int[]{20, 40, 80}) {
            for (boolean up : new boolean[]{true, false}) {
                String line = NetPrinter.arrowLine(new NetPrinter.Arrow(up, "TLS ServerHello", "server trả lời, bắt đầu mã hoá",
                        NetPrinter.Style.TLS, "+235 ms"), w);
                assertEquals(w, NetPrinter.width(line), w + " " + line);
                assertTrue(line.startsWith("│") && line.contains(up ? "▶│" : "│◀"), line);
            }
        }
    }

    @Test
    void tắtChếĐộChiTiếtThìKhôngCóDòngGói() {
        String all = String.join("\n", run(false, shortConnection()));
        assertFalse(all.contains("── syn") || all.contains(" fin "), all);
        assertTrue(all.contains("bat tay 40 ms"), "dòng mở kết nối vẫn có thời gian bắt tay\n" + all);
    }

    @Test
    void synGửiLạiVàBịTừChối() {
        List<String> lines = run(true, List.of(
                Packet.tcp(1.0, false, CLIENT, SERVER, 100, Packet.SYN, 64240, 0, new byte[0]),
                Packet.tcp(2.0, false, CLIENT, SERVER, 100, Packet.SYN, 64240, 0, new byte[0]),
                Packet.tcp(2.1, false, SERVER, CLIENT, 0, Packet.RST | Packet.ACK, 0, 0, new byte[0])));
        String all = String.join("\n", lines);
        assertTrue(all.contains("── syn gui lai lan 2 - chua thay tra loi ─") && all.contains("│    +1.0s"), all);
        assertTrue(all.contains(" rst server cat ngang ──│  +100 ms"), all);
        assertTrue(all.contains("tu choi ket noi"), all);
    }
}
