package com.naviq.cli.anchor;


import org.jline.terminal.Terminal;

@FunctionalInterface
public interface AnchorStrategy {
    /**
     * Trả về vị trí (1-based row/col) để vẽ menu. null = huỷ vẽ.
     */
    Anchor resolve(Terminal terminal);
}