package com.sqlctx.cli.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExplainFormatterTest {

    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";
    private static final String SUMMARY = "\u001b[1;35m";
    private static final String GRAY = "\u001b[90m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[1;36m";
    private static final String RED = "\u001b[1;31m";

    @Test
    void dòngNhãnThường() {
        assertEquals("  " + YELLOW + "Filter:" + RESET + " x > 1",
                ExplainFormatter.colorizeLine("  Filter: x > 1"));
    }

    @Test
    void dòngNhãnTổngKếtNổiBậtHơn() {
        assertEquals(SUMMARY + "Planning Time:" + RESET + " 0.123 ms",
                ExplainFormatter.colorizeLine("Planning Time: 0.123 ms"));
    }

    @Test
    void khungAsciiCủaOracleGiữNguyênKhôngTôMàu() {
        String line = "| Id  | Operation         | Name  |";
        assertEquals(line, ExplainFormatter.colorizeLine(line));
    }

    @Test
    void dòngViềnToànDấuGạchGiữNguyên() {
        String line = "-----+-----------+-------";
        assertEquals(line, ExplainFormatter.colorizeLine(line));
    }

    @Test
    void tênNodeKhôngMũiTênĐượcTôCyan() {
        assertEquals("  " + CYAN + "Seq Scan on users" + RESET,
                ExplainFormatter.colorizeLine("  Seq Scan on users"));
    }

    @Test
    void tênNodeCóMũiTênCâyGiữMờMũiTênNổiBậtTên() {
        assertEquals("  " + DIM + "->" + RESET + " " + CYAN + "Seq Scan on users" + RESET,
                ExplainFormatter.colorizeLine("  ->  Seq Scan on users"));
    }

    @Test
    void costVàActualTimeĐượcTôMờGrayTáchKhỏiTênNode() {
        String line = "Seq Scan on users (cost=0.00..1.10 rows=10 width=40)";
        String expected = CYAN + "Seq Scan on users " + RESET
                + GRAY + "(cost=0.00..1.10 rows=10 width=40)" + RESET;
        assertEquals(expected, ExplainFormatter.colorizeLine(line));
    }

    @Test
    void neverExecutedĐượcTôĐỏNổiBật() {
        String line = "  Sub Plan (never executed)";
        String expected = "  " + CYAN + "Sub Plan " + RED + "(never executed)" + RESET + RESET;
        assertEquals(expected, ExplainFormatter.colorizeLine(line));
    }
}
