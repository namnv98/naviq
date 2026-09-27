package com.sqlctx.datasource;

import com.sqlctx.schema.Dialect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Lưu/đọc các "context" đặt tên (kiểu kubeconfig của kubectx) ra 1 file INI đơn giản, không cần thêm thư
 * viện YAML/JSON:
 * <pre>
 * [dev]
 * dialect=postgres
 * host=127.0.0.1
 * port=54329
 * dbname=naviq
 * user=tester
 * password=x
 * </pre>
 * File chứa MẬT KHẨU DẠNG THƯỜNG (không mã hoá) - giống hệt rủi ro của việc gõ --password= trên dòng lệnh,
 * chỉ khác là không lộ ra lịch sử shell/ps aux. Tự đặt quyền 600 (chỉ chủ file đọc/ghi được) lúc tạo, và
 * CẢNH BÁO (không chặn) nếu phát hiện quyền file bị mở hơn mức đó lúc đọc.
 */
public class ConnectionProfileStore {

    private static volatile boolean migrationChecked = false;

    /** Thư mục chứa file cấu hình - hiện lên banner khởi động của CLI để người dùng biết context lưu ở đâu. */
    public static Path configDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".sqlctx");
        if (!migrationChecked) {
            migrationChecked = true;
            migrateFromOldConfigDirIfNeeded(dir);
        }
        return dir;
    }

    /**
     * Đổi tên dự án naviq -> sqlctx (2026-09-27): tự chuyển ~/.naviq cũ sang ~/.sqlctx MỘT LẦN DUY NHẤT
     * nếu thấy còn ~/.naviq (context/mật khẩu đã lưu từ trước) mà ~/.sqlctx chưa tồn tại - tránh người
     * dùng cũ mất sạch context đã lưu chỉ vì đổi tên thư mục config.
     */
    private static void migrateFromOldConfigDirIfNeeded(Path newDir) {
        Path oldDir = Path.of(System.getProperty("user.home"), ".naviq");
        try {
            if (Files.exists(oldDir) && !Files.exists(newDir)) {
                Files.move(oldDir, newDir);
                System.err.println("Đã tự chuyển config cũ \"" + oldDir + "\" sang \"" + newDir
                        + "\" (đổi tên dự án naviq -> sqlctx).");
            }
        } catch (IOException ignored) {
            // Không di chuyển được (khác filesystem, quyền...) - không chặn khởi động, coi như bắt đầu config mới.
        }
    }

    private static Path configFile() {
        return configDir().resolve("connections.conf");
    }

    private static Path lastContextFile() {
        return configDir().resolve("current_context");
    }

    /** Tên context lần trước dùng thành công - đọc lại khi khởi động KHÔNG gõ gì (không --context=, không --host=...). */
    public static Optional<String> getLastContextName() {
        try {
            Path file = lastContextFile();
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String name = Files.readString(file).strip();
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void setLastContextName(String name) {
        try {
            Path file = lastContextFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, name);
        } catch (IOException ignored) {
            // Không ghi được thì lần sau chỉ đơn giản là không tự nối lại được - không phải lỗi nghiêm trọng.
        }
    }

    public static List<ConnectionProfile> loadAll() {
        Path file = configFile();
        if (!Files.exists(file)) {
            return List.of();
        }
        warnIfPermissionsTooOpen(file);

        List<ConnectionProfile> profiles = new ArrayList<>();
        String name = null;
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(file)) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (name != null) {
                        profiles.add(toProfile(name, fields));
                    }
                    name = line.substring(1, line.length() - 1).strip();
                    fields = new LinkedHashMap<>();
                } else {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        fields.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                    }
                }
            }
            if (name != null) {
                profiles.add(toProfile(name, fields));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return profiles;
    }

    public static Optional<ConnectionProfile> find(String name) {
        return loadAll().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Ghi đè nếu đã có profile cùng tên, thêm mới nếu chưa - giữ nguyên các profile khác và thứ tự. */
    public static void save(ConnectionProfile profile) throws IOException {
        List<ConnectionProfile> profiles = new ArrayList<>(loadAll());
        profiles.removeIf(p -> p.name().equalsIgnoreCase(profile.name()));
        profiles.add(profile);

        Path file = configFile();
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (ConnectionProfile p : profiles) {
            sb.append("[").append(p.name()).append("]\n");
            sb.append("dialect=").append(p.dialect().name().toLowerCase()).append("\n");
            sb.append("host=").append(p.host()).append("\n");
            sb.append("port=").append(p.port()).append("\n");
            sb.append("dbname=").append(p.dbname()).append("\n");
            sb.append("user=").append(p.user()).append("\n");
            sb.append("password=").append(p.password()).append("\n\n");
        }
        Files.writeString(file, sb.toString());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Hệ thống file không phải POSIX (vd Windows) - bỏ qua, không có gì để đặt quyền tương đương đơn giản.
        }
    }

    private static ConnectionProfile toProfile(String name, Map<String, String> f) {
        Dialect dialect = "oracle".equalsIgnoreCase(f.getOrDefault("dialect", "postgres"))
                ? Dialect.ORACLE : Dialect.POSTGRES;
        return new ConnectionProfile(name, dialect, f.get("host"), f.get("port"), f.get("dbname"),
                f.get("user"), f.get("password"));
    }

    private static void warnIfPermissionsTooOpen(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            boolean tooOpen = perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.OTHERS_READ);
            if (tooOpen) {
                System.err.println("WARNING: " + file + " chứa mật khẩu dạng thường và đang cho phép "
                        + "người khác đọc được (quyền " + PosixFilePermissions.toString(perms) + ") - nên chạy: "
                        + "chmod 600 " + file);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Không đọc được permission (vd không phải POSIX filesystem) - không cảnh báo được, bỏ qua.
        }
    }
}
