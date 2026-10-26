#!/bin/bash
# Dựng toàn bộ môi trường test cục bộ của docker-compose.yml (Postgres, Oracle, Redis đơn/Cluster/Sentinel,
# Kafka, Schema Registry), chờ healthy rồi nạp dữ liệu mẫu trong seed/. Chạy lại được nhiều lần.
#
#   tools/test-env.sh            # = up + seed + contexts
#   tools/test-env.sh up         # chỉ bật container, chờ healthy
#   tools/test-env.sh seed       # chỉ nạp lại dữ liệu mẫu
#   tools/test-env.sh contexts   # lưu context trỏ vào container: pg-dev, oradev, redis-dev, redis-cluster,
#                                # redis-sentinel, kafka-dev (không đổi context đang dùng)
#   tools/test-env.sh status     # trạng thái container
#   tools/test-env.sh down       # tắt container (giữ volume)
#
# Chỉ đụng tới container local của repo; seed không dùng context nào trong ~/.sqlctx (seed Kafka truyền thẳng
# tham số kết nối), nên không bao giờ nối nhầm sang DB thật.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose --profile redis-cluster --profile redis-sentinel)
JAR="${JAR:-target/sqlctx-1.0.0.jar}"

wait_healthy() {
  local deadline=$((SECONDS + 300)) c s
  for c in "$@"; do
    while :; do
      s=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$c" 2>/dev/null || echo missing)
      [[ $s == healthy || $s == running ]] && break
      ((SECONDS > deadline)) && { echo "Hết giờ chờ $c ($s)" >&2; exit 1; }
      sleep 2
    done
    printf '  %-26s %s\n' "$c" "$s"
  done
}

up() {
  "${COMPOSE[@]}" up -d
  echo "Chờ container healthy..."
  wait_healthy sqlctx-postgres sqlctx-redis sqlctx-redis-cluster sqlctx-redis-sentinel \
               sqlctx-kafka sqlctx-schema-registry sqlctx-oracle
  # sentinel không có healthcheck: chờ nó nhận ra master
  until redis-cli -p 26390 SENTINEL get-master-addr-by-name mymaster 2>/dev/null | grep -q 6390; do sleep 1; done
}

seed() {
  [[ -f $JAR ]] || mvn -q package -DskipTests
  echo "Postgres: seed/postgres.sql (db naviq) + tools/intellij-compare/fixture.sql (db sqlctx_fixture)"
  docker exec -i sqlctx-postgres psql -q -U tester -d naviq -v ON_ERROR_STOP=1 < seed/postgres.sql > /dev/null
  docker exec -i sqlctx-postgres psql -q -U tester -d naviq -v ON_ERROR_STOP=1 < tools/intellij-compare/fixture.sql > /dev/null

  echo "Oracle: seed/oracle.sql (FREEPDB1, user SHOP)"
  docker exec -i sqlctx-oracle sqlplus -s -L system/Oracle123@localhost:1521/FREEPDB1 < seed/oracle.sql > /dev/null

  echo "Redis: seed/redis.txt"
  grep -v '^#' seed/redis.txt | docker exec -i sqlctx-redis redis-cli -a Redis123 --no-auth-warning > /dev/null

  echo "Kafka: seed/kafka.sh"
  local wrapper
  wrapper=$(mktemp)
  trap 'rm -f "$wrapper"' RETURN
  printf '#!/bin/bash\nshift\nexec java -jar %q --dialect=kafka --bootstrap-server=localhost:19092 --schema-registry=http://localhost:18081 "$@"\n' \
    "$PWD/$JAR" > "$wrapper"
  chmod +x "$wrapper"
  SQLCTX="$wrapper" bash seed/kafka.sh
}

contexts() {
  [[ -f $JAR ]] || mvn -q package -DskipTests
  local cur=~/.sqlctx/current_context saved=""
  [[ -f $cur ]] && saved=$(<"$cur")
  # --context + tham số kết nối = lưu (ghi đè nếu đã có); --command để chỉ nối thử rồi thoát
  ctx() { java -jar "$JAR" --context="$1" "${@:3}" --command="$2" > /dev/null && echo "  $1"; }
  ctx pg-dev         "select 1"        --host=127.0.0.1 --port=54329 --dbname=naviq --user=tester --password=x
  ctx oradev         "select 1 from dual" --dialect=oracle --host=127.0.0.1 --port=1521 --dbname=FREEPDB1 --user=naviq --password=Naviq123
  ctx redis-dev      PING              --dialect=redis --host=127.0.0.1 --port=63790 --password=Redis123
  ctx redis-cluster  PING              --dialect=redis --host=127.0.0.1 --port=7001 --password=Redis123
  ctx redis-sentinel PING              --dialect=redis --host=127.0.0.1 --port=26390 --sentinel-master=mymaster --password=Redis123
  ctx kafka-dev      "kafka-topics --list" --dialect=kafka --bootstrap-server=localhost:19092 --schema-registry=http://localhost:18081
  if [[ -n $saved ]]; then printf '%s' "$saved" > "$cur"; fi
}

case "${1:-all}" in
  all)    up; seed; contexts ;;
  contexts) contexts ;;
  up)     up ;;
  seed)   seed ;;
  status) "${COMPOSE[@]}" ps ;;
  down)   "${COMPOSE[@]}" down ;;
  *)      sed -n '2,11p' "$0"; exit 1 ;;
esac
