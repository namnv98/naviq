#!/bin/bash
# Cập nhật bản COMMAND DOCS/INFO đóng gói sẵn (src/main/resources/redis/fallback-commands.json.gz). sqlctx chỉ
# dùng bản này khi server không trả COMMAND DOCS (Redis < 7.0, hoặc ACL chặn lệnh COMMAND) - bình thường cây lệnh
# lấy từ chính server lúc kết nối. Lấy từ Valkey (giấy phép BSD) thay vì Redis >= 7.4 (RSALv2/SSPL).
#   tools/redis-fallback/update.sh [image]     mặc định valkey/valkey:9.0
set -e
cd "$(dirname "$0")/../.."
IMAGE="${1:-valkey/valkey:9.0}"
NAME=sqlctx-fallback-$$
docker run -d --rm --name "$NAME" "$IMAGE" >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1' EXIT
until docker exec "$NAME" valkey-cli PING >/dev/null 2>&1; do sleep 0.5; done
VERSION=$(docker exec "$NAME" valkey-cli INFO server | grep -E '^valkey_version:' | cut -d: -f2 | tr -d '\r')
docker exec "$NAME" valkey-cli --json COMMAND DOCS > /tmp/$NAME-docs.json
docker exec "$NAME" valkey-cli --json COMMAND INFO > /tmp/$NAME-info.json
python3 - "$VERSION" /tmp/$NAME-docs.json /tmp/$NAME-info.json <<'PY'
import gzip, json, sys
version, docs, info = sys.argv[1], json.load(open(sys.argv[2])), json.load(open(sys.argv[3]))
out = {'source': 'valkey ' + version, 'docs': docs, 'info': info}
with gzip.open('src/main/resources/redis/fallback-commands.json.gz', 'wt', encoding='utf-8') as f:
    json.dump(out, f, separators=(',', ':'))
print(f'valkey {version}: {len(docs)} lệnh')
PY
rm -f /tmp/$NAME-docs.json /tmp/$NAME-info.json
