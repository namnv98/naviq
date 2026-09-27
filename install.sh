#!/bin/bash
# Build + cài sqlctx thành lệnh CLI thường (gõ "sqlctx" thay vì "java -jar target/sqlctx-1.0.0.jar").
# Không cần sudo - cài vào ~/.local (share + bin), theo đúng chuẩn XDG cho user hiện tại.
set -e
cd "$(dirname "$0")"

mvn -q clean package -DskipTests

INSTALL_DIR="$HOME/.local/share/sqlctx"
BIN_DIR="$HOME/.local/bin"

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp target/sqlctx-1.0.0.jar "$INSTALL_DIR/sqlctx.jar"

cat > "$BIN_DIR/sqlctx" << 'EOF'
#!/bin/bash
exec java -jar "$HOME/.local/share/sqlctx/sqlctx.jar" "$@"
EOF
chmod +x "$BIN_DIR/sqlctx"

echo "Đã cài xong: $INSTALL_DIR/sqlctx.jar"
echo "Gõ 'sqlctx' để chạy."
if ! echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
    echo "LƯU Ý: $BIN_DIR chưa nằm trong PATH - thêm dòng sau vào ~/.bashrc hoặc ~/.zshrc:"
    echo "    export PATH=\"\$PATH:$BIN_DIR\""
fi
