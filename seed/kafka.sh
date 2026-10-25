#!/bin/bash
# Dữ liệu mẫu cho sqlctx-kafka + sqlctx-schema-registry (docker-compose.yml), chạy lại được nhiều lần (làm trống rồi ghi lại):
#   orders        3 partition, message JSON có key, group "billing" đọc dở (có lag)
#   payments      Avro qua Schema Registry (subject payments-value)
#   users-json    JSON Schema
#   events-proto  Protobuf (ghi bằng tool của Confluent trong container Schema Registry - sqlctx chỉ đọc protobuf)
# Cần context kafka-dev:  sqlctx --context=kafka-dev --dialect=kafka --bootstrap-server=localhost:19092 \
#                                --schema-registry=http://localhost:18081 --command='kafka-topics --list'
set -e
SQLCTX="${SQLCTX:-sqlctx}"
run() { "$SQLCTX" --context=kafka-dev --yes --command="$1"; }

# Không xoá rồi tạo lại topic cùng tên: bản cũ của script làm vậy và broker đã tự xoá offset của group billing ~18
# phút sau (nghi lượt dọn định kỳ coi offset trên topic mới là của topic cũ đã xoá). Tạo nếu chưa có, rồi làm trống
# bằng kafka-delete-records.
run "kafka-topics --create --topic orders --partitions 3 --replication-factor 1 --config retention.ms=604800000 --if-not-exists" > /dev/null
for t in payments users-json events-proto; do
  run "kafka-topics --create --topic $t --partitions 1 --if-not-exists" > /dev/null
done
EMPTY=$(mktemp)
trap 'rm -f "$EMPTY"' EXIT
cat > "$EMPTY" <<'JSON'
{"partitions": [
  {"topic": "orders", "partition": 0, "offset": -1}, {"topic": "orders", "partition": 1, "offset": -1},
  {"topic": "orders", "partition": 2, "offset": -1}, {"topic": "payments", "partition": 0, "offset": -1},
  {"topic": "users-json", "partition": 0, "offset": -1}, {"topic": "events-proto", "partition": 0, "offset": -1}
]}
JSON
run "kafka-delete-records --offset-json-file $EMPTY" > /dev/null
run "kafka-consumer-groups --delete --group billing" > /dev/null 2>&1 || true
for s in payments-value users-json-value events-proto-value; do
  curl -s -X DELETE "http://localhost:18081/subjects/$s" > /dev/null || true
  curl -s -X DELETE "http://localhost:18081/subjects/$s?permanent=true" > /dev/null || true
done

printf '%s\n' \
  $'1\t{"order_id":1,"customer":"An","status":"paid","total":120.50}' \
  $'2\t{"order_id":2,"customer":"Binh","status":"new","total":75.00}' \
  $'3\t{"order_id":3,"customer":"Chi","status":"shipped","total":310.00}' \
  $'4\t{"order_id":4,"customer":"Dung","status":"cancelled","total":15.99}' \
  $'5\t{"order_id":5,"customer":"Em","status":"paid","total":980.00}' \
  $'6\t{"order_id":6,"customer":"An","status":"new","total":42.00}' \
  | run "kafka-console-producer --topic orders --property parse.key=true"
# group billing đọc 4/6 message -> còn lag 2
run "kafka-console-consumer --topic orders --group billing --from-beginning --max-messages 4" > /dev/null

printf '%s\n' '{"id":1,"amount":120.5,"method":"card"}' '{"id":2,"amount":75.0,"method":"cash"}' \
  | run "kafka-avro-console-producer --topic payments --property value.schema='{\"type\":\"record\",\"name\":\"Payment\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"amount\",\"type\":\"double\"},{\"name\":\"method\",\"type\":\"string\"}]}'"

printf '%s\n' '{"name":"An","age":31}' '{"name":"Binh","age":27}' \
  | run "kafka-json-schema-console-producer --topic users-json --property value.schema='{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}'"

printf '%s\n' '{"id":7,"kind":"click","tags":["a","b"]}' '{"id":8,"kind":"view","tags":[]}' \
  | docker exec -i sqlctx-schema-registry kafka-protobuf-console-producer --bootstrap-server sqlctx-kafka:9092 \
      --topic events-proto --property schema.registry.url=http://localhost:8081 \
      --property value.schema='syntax = "proto3"; message Event { int32 id = 1; string kind = 2; repeated string tags = 3; }' \
      > /dev/null 2>&1

echo "Xong: orders, payments (avro), users-json (json schema), events-proto (protobuf), group billing."
