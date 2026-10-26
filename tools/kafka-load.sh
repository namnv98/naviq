#!/bin/bash
# Tải giả lập cho sqlctx-kafka (docker-compose.yml) để xem \stats: ghi liên tục vào vài topic, có group đọc kịp, group
# đọc không kịp (lag tăng) và group đã dừng (lag đứng yên, không có consumer). Mọi tiến trình chạy trong container
# sqlctx-kafka, mọi thứ tạo ra mang tiền tố "load-". Chỉ dùng với broker thử nghiệm.
#
#   tools/kafka-load.sh start    # bật (chạy tới khi stop)
#   tools/kafka-load.sh stop     # tắt mọi producer/consumer
#   tools/kafka-load.sh clean    # stop + xoá topic/group load-*
#   tools/kafka-load.sh status
#
# Xem: sqlctx --context=kafka-dev  rồi gõ  \stats 2
set -euo pipefail

C=sqlctx-kafka
BIN=/opt/kafka/bin
BS=localhost:9092
# mỗi partition giữ tối đa ~100MB, segment 20MB: chạy lâu không làm đầy đĩa
LIMITS="--config retention.bytes=104857600 --config segment.bytes=20971520"

k() { docker exec "$C" "$@"; }
bg() { docker exec -d "$C" sh -c "$1"; }

topic() { k $BIN/kafka-topics.sh --bootstrap-server $BS --create --if-not-exists --topic "$1" --partitions "$2" $LIMITS > /dev/null; }
produce() { # topic, msg/s, cỡ message
  bg "$BIN/kafka-producer-perf-test.sh --topic $1 --num-records 2000000000 --throughput $2 --record-size $3 \
      --producer-props bootstrap.servers=$BS linger.ms=20 > /dev/null 2>&1"
}
consume() { # topic, group
  bg "$BIN/kafka-console-consumer.sh --bootstrap-server $BS --topic $1 --group $2 > /dev/null 2>&1"
}

pids() { k sh -c 'ps -eo pid,args | grep -E "[l]oad-" | awk "{print \$1}"' 2>/dev/null || true; }

start() {
  if [[ -n $(pids) ]]; then
    echo "Đang chạy rồi (tools/kafka-load.sh stop để tắt)."
    exit 0
  fi
  topic load-clicks 12
  topic load-events 6
  topic load-payments 3
  topic load-audit 3

  produce load-clicks 15000 200     # đều, lớn nhất
  produce load-events 4000 500
  # payments: theo đợt - 20s ghi 3000 msg/s, nghỉ 15s
  bg "while true; do timeout 20 $BIN/kafka-producer-perf-test.sh --topic load-payments --num-records 2000000000 \
      --throughput 3000 --record-size 300 --producer-props bootstrap.servers=$BS > /dev/null 2>&1; sleep 15; done # load-"
  produce load-audit 50 1000

  consume load-clicks load-analytics   # 2 member, đọc kịp
  consume load-clicks load-analytics
  consume load-payments load-billing
  # archiver: mỗi lượt đọc 3000 message rồi thoát (rebalance liên tục), chậm hơn tốc độ ghi 4000 msg/s -> lag tăng
  bg "while true; do $BIN/kafka-console-consumer.sh --bootstrap-server $BS --topic load-events --group load-archiver \
      --max-messages 3000 > /dev/null 2>&1; sleep 1; done # load-"
  # audit: đọc 15s rồi dừng hẳn -> lag đứng yên, cảnh báo "không có consumer"
  bg "timeout 15 $BIN/kafka-console-consumer.sh --bootstrap-server $BS --topic load-audit --group load-audit > /dev/null 2>&1 # load-"

  echo "Đã bật tải: load-clicks 15k msg/s, load-events 4k, load-payments 3k theo đợt, load-audit 50."
  echo "Group: load-analytics (2 member), load-billing, load-archiver (đọc không kịp), load-audit (dừng sau 15s)."
  echo "Xem: sqlctx --context=kafka-dev  rồi  \\stats 2      Tắt: tools/kafka-load.sh stop"
}

stop() {
  local p
  p=$(pids)
  if [[ -n $p ]]; then
    # giết vòng lặp sh trước để nó không bật lại java
    k sh -c "kill $(echo $p) 2>/dev/null; sleep 2; kill -9 $(echo $p) 2>/dev/null" || true
  fi
  echo "Đã tắt tải."
}

clean() {
  stop
  echo "Chờ consumer rời group (tối đa ~45s)..."
  for g in $(k $BIN/kafka-consumer-groups.sh --bootstrap-server $BS --list 2>/dev/null | grep '^load-'); do
    for _ in $(seq 1 15); do
      out=$(k $BIN/kafka-consumer-groups.sh --bootstrap-server $BS --delete --group $g 2>&1 || true)
      [[ $out == *successful* || $out == *"does not exist"* ]] && break
      sleep 4
    done
  done
  k $BIN/kafka-topics.sh --bootstrap-server $BS --delete --topic 'load-.*' 2>/dev/null || true
  echo "Đã xoá topic/group load-*."
}

case "${1:-}" in
  start)  start ;;
  stop)   stop ;;
  clean)  clean ;;
  status) n=$(pids | wc -w); echo "$n tiến trình tải đang chạy" ;;
  *)      sed -n '2,11p' "$0"; exit 1 ;;
esac
