package com.naviq.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Khởi tạo java.util.logging bằng file logging.properties trong classpath (src/main/resources/) -
 * KHÔNG cần truyền JVM arg "-Djava.util.logging.config.file=..." khi chạy, vì logging.properties
 * mặc định của JDK phải nạp qua flag đó - ở đây tự đọc thủ công qua
 * LogManager.readConfiguration(InputStream) để chỉ cần gọi init() 1 dòng đầu main() là xong.
 * <p>
 * Log ghi ra file (~/naviq-debug.log, xem logging.properties) - KHÔNG ra console, vì CLI đang
 * chạy tương tác qua JLine, in log lẫn vào màn hình sẽ phá layout terminal.
 */
public final class LoggingConfig {

    private LoggingConfig() {
    }

    public static void init() {
        try (InputStream in = LoggingConfig.class.getResourceAsStream("/logging.properties")) {
            if (in == null) {
                System.err.println("[LoggingConfig] Không tìm thấy /logging.properties trong classpath - "
                        + "dùng cấu hình logging mặc định của JVM.");
                return;
            }
            LogManager.getLogManager().readConfiguration(in);
        } catch (IOException e) {
            System.err.println("[LoggingConfig] Lỗi đọc logging.properties: " + e.getMessage());
        }
    }

    /**
     * Tiện ích lấy Logger đúng chuẩn (tên logger = tên class gọi tới) - dùng thay vì gọi
     * Logger.getLogger(X.class.getName()) lặp lại ở từng nơi.
     */
    public static Logger of(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}