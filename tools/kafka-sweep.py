#!/usr/bin/env python3
"""Chạy mọi tool/chế độ của chế độ Kafka qua sqlctx (batch --command) với 1 broker thật, in lệnh nào lỗi.
Mọi thứ tạo ra (topic, group, subject, ACL, transaction) mang tiền tố sweep-<thời điểm> và được dọn ở cuối.
Lệnh ghi chạy với --yes. Dùng với broker thử nghiệm (vd docker-compose.yml của repo), không dùng với cluster thật.

    docker compose up -d kafka schema-registry
    tools/kafka-sweep.py --jar target/sqlctx-1.0.0.jar
"""
import argparse, json, os, re, subprocess, tempfile, time

ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
ap.add_argument('--jar', default='target/sqlctx-1.0.0.jar')
ap.add_argument('--bootstrap', default='localhost:19092')
ap.add_argument('--schema-registry', default='http://localhost:18081', help='"" nếu không có Schema Registry')
ap.add_argument('--broker', default='1', help='id của 1 broker (cho kafka-configs/leader-election/reassign)')
ap.add_argument('-v', '--verbose', action='store_true', help='in output của mọi lệnh')
ARGS = ap.parse_args()

P = 'sweep-' + str(int(time.time()))
T = P + '-t'
G = P + '-g'
TMP = tempfile.mkdtemp(prefix='kafka-sweep-')


def file(name, content):
    path = os.path.join(TMP, name)
    with open(path, 'w') as f:
        f.write(content if isinstance(content, str) else json.dumps(content))
    return path


MOVE = file('move.json', {'topics': [{'topic': T}], 'version': 1})
REASSIGN = file('reassign.json', {'partitions': [{'topic': T, 'partition': 0, 'replicas': [int(ARGS.broker)]}], 'version': 1})
ELECT = file('elect.json', {'partitions': [{'topic': T, 'partition': 0}]})
DELETE = file('delete.json', {'partitions': [{'topic': T, 'partition': 0, 'offset': 1}]})
AVRO = file('click.avsc', '{"type":"record","name":"Click","fields":[{"name":"page","type":"string"}]}')
PROTO = file('click.proto', 'syntax = "proto3"; message Click { string page = 1; int32 n = 2; }')
SR = bool(ARGS.schema_registry)

# (lệnh, stdin cho producer); thứ tự có nghĩa: tạo trước, xem, sửa, xoá sau cùng
STEPS = [
    ('kafka-cluster cluster-id', None),
    ('kafka-metadata-quorum describe --status', None),
    ('kafka-metadata-quorum describe --replication --human-readable', None),
    (f'kafka-topics --create --topic {T} --partitions 3 --replication-factor 1 --config retention.ms=86400000', None),
    (f'kafka-topics --create --topic {T} --partitions 3 --replication-factor 1 --if-not-exists', None),
    ('kafka-topics --list --exclude-internal', None),
    (f'kafka-topics --describe --topic {T}', None),
    ('kafka-topics --describe --under-replicated-partitions', None),
    (f'kafka-topics --alter --topic {T} --partitions 4', None),
    (f'kafka-console-producer --topic {T} --property parse.key=true --property key.separator=: --sync',
     'k1:hello\nk2:xin chào\nk3:3\n'),
    (f'kafka-console-producer --topic {T} --property parse.headers=true --compression-codec zstd', 'h1:v1\tbody\n'),
    (f'kafka-console-consumer --topic {T} --from-beginning --max-messages 4 --timeout-ms 10000 '
     '--property print.key=true --property print.partition=true --property print.offset=true --property print.headers=true', None),
    (f'kafka-console-consumer --topic {T} --partition 0 --offset earliest --max-messages 1 --timeout-ms 10000', None),
    (f'kafka-console-consumer --topic {T} --group {G} --from-beginning --max-messages 2 --timeout-ms 10000', None),
    (f'kafka-console-consumer --include {P}.* --max-messages 1 --from-beginning --timeout-ms 10000 '
     '--isolation-level read_committed', None),
    ('kafka-consumer-groups --list', None),
    ('kafka-consumer-groups --list --state Empty', None),
    (f'kafka-consumer-groups --describe --group {G}', None),
    (f'kafka-consumer-groups --describe --group {G} --members --verbose', None),
    (f'kafka-consumer-groups --describe --group {G} --state', None),
    (f'kafka-consumer-groups --reset-offsets --group {G} --topic {T} --to-earliest', None),
    (f'kafka-consumer-groups --reset-offsets --group {G} --all-topics --shift-by -1 --execute', None),
    (f'kafka-consumer-groups --reset-offsets --group {G} --topic {T}:0 --to-datetime 2020-01-01T00:00:00.000 --execute', None),
    (f'kafka-consumer-groups --reset-offsets --group {G} --topic {T} --by-duration PT1H --execute', None),
    (f'kafka-consumer-groups --delete-offsets --group {G} --topic {T}:0', None),  # cả topic thì group mất hết offset, bị xoá
    (f'kafka-get-offsets --topic {T}', None),
    (f'kafka-get-offsets --topic-partitions {T}:0-1 --time -2', None),
    (f'kafka-get-offsets --topic {T} --time -3', None),
    (f'kafka-delete-records --offset-json-file {DELETE}', None),
    (f'kafka-log-dirs --describe --topic-list {T}', None),
    (f'kafka-configs --describe --topic {T}', None),
    (f'kafka-configs --alter --topic {T} --add-config retention.ms=3600000,cleanup.policy=[delete,compact]', None),
    (f'kafka-configs --alter --topic {T} --delete-config cleanup.policy', None),
    (f'kafka-configs --describe --broker {ARGS.broker}', None),
    (f'kafka-configs --describe --broker {ARGS.broker} --all', None),
    ('kafka-configs --describe --broker-defaults', None),
    (f'kafka-configs --describe --broker-logger {ARGS.broker}', None),
    (f'kafka-configs --alter --user {P}-u --add-config producer_byte_rate=1048576', None),
    ('kafka-configs --describe --entity-type users', None),
    (f'kafka-configs --alter --user {P}-u --delete-config producer_byte_rate', None),
    (f'kafka-acls --add --allow-principal User:{P} --operation Read --topic {T}', None),
    (f'kafka-acls --add --allow-principal User:{P} --consumer --topic {T} --group {G}', None),
    (f'kafka-acls --list --topic {T}', None),
    (f'kafka-acls --list --principal User:{P}', None),
    (f'kafka-acls --remove --allow-principal User:{P} --operation All --topic {T} --force', None),
    (f'kafka-acls --remove --topic {T} --force', None),
    (f'kafka-acls --remove --group {G} --force', None),
    (f'kafka-reassign-partitions --generate --topics-to-move-json-file {MOVE} --broker-list {ARGS.broker}', None),
    (f'kafka-reassign-partitions --execute --reassignment-json-file {REASSIGN}', None),
    (f'kafka-reassign-partitions --verify --reassignment-json-file {REASSIGN}', None),
    ('kafka-reassign-partitions --list', None),
    (f'kafka-reassign-partitions --cancel --reassignment-json-file {REASSIGN}', None),
    (f'kafka-leader-election --election-type preferred --topic {T} --partition 0', None),
    (f'kafka-leader-election --election-type preferred --path-to-json-file {ELECT}', None),
    ('kafka-leader-election --election-type preferred --all-topic-partitions', None),
    ('kafka-transactions list', None),
    (f'kafka-streams-application-reset --application-id {G} --input-topics {T} --dry-run', None),
    (f'kafka-streams-application-reset --application-id {G} --input-topics {T} --to-latest', None),
    (f'kafka-streams-application-reset --application-id {G} --input-topics {T} --by-duration PT1H --dry-run', None),
    (f'kafka-transactions describe-producers --topic {T} --partition 0', None),
    (f'kafka-transactions find-hanging --topic {T}', None),
    (f'kafka-transactions find-hanging --broker-id {ARGS.broker} --max-transaction-timeout 1', None),
]
if SR:
    STEPS += [
        ('schema-registry --list', None),
        (f'schema-registry --register --subject {T}-avro-value --schema-file {AVRO}', None),
        (f'schema-registry --versions --subject {T}-avro-value', None),
        (f'schema-registry --describe --subject {T}-avro-value --version latest', None),
        (f'schema-registry --compatibility --subject {T}-avro-value', None),
        (f'schema-registry --compatibility --subject {T}-avro-value --level FULL', None),
        (f'kafka-avro-console-producer --topic {T}-avro --property value.schema.file={AVRO}', '{"page":"/home"}\n'),
        (f'kafka-avro-console-consumer --topic {T}-avro --from-beginning --max-messages 1 --timeout-ms 10000 '
         '--property print.schema.ids=true', None),
        (f"kafka-json-schema-console-producer --topic {T}-json --property 'value.schema={{\"type\":\"object\"}}'", '{"a":1}\n'),
        (f'kafka-json-schema-console-consumer --topic {T}-json --from-beginning --max-messages 1 --timeout-ms 10000', None),
        (f'kafka-protobuf-console-producer --topic {T}-proto --property value.schema.file={PROTO}', '{"page":"/p","n":2}\n'),
        (f'kafka-protobuf-console-consumer --topic {T}-proto --from-beginning --max-messages 1 --timeout-ms 10000', None),
    ]
    for s in ('avro', 'json', 'proto'):
        STEPS += [(f'schema-registry --delete --subject {T}-{s}-value', None),
                  (f'schema-registry --delete --subject {T}-{s}-value --permanent', None)]
STEPS += [
    (f'kafka-consumer-groups --delete --group {G}', None),
    (f'kafka-topics --delete --topic {P}.*', None),
]

# lỗi mong đợi: phải báo lỗi (exit != 0), không được treo hay ném stack trace
MUST_FAIL = [
    'kafka-topics --create',
    'kafka-topics --list --nope',
    f'kafka-topics --describe --topic {P}-không-có',
    'kafka-leader-election --election-type sideways --all-topic-partitions',
    f'kafka-transactions abort --topic {T} --partition 0 --start-offset 999',
    f'kafka-reassign-partitions --verify --reassignment-json-file {os.path.join(TMP, "không-có.json")}',
    f'kafka-reassign-partitions --execute --reassignment-json-file {file("bad.json", {"partitions": [{"topic": T, "partition": 0, "replicas": [987654]}], "version": 1})}',
    'kafka-streams-application-reset --input-topics x',
]


def run(line, stdin=None):
    cmd = ['java', '-jar', ARGS.jar, '--dialect=kafka', '--bootstrap-server=' + ARGS.bootstrap, '--yes', '--command=' + line]
    if SR:
        cmd.insert(4, '--schema-registry=' + ARGS.schema_registry)
    try:
        p = subprocess.run(cmd, input=stdin or '', capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        return None, 'TIMEOUT'
    return p.returncode, re.sub(r'\x1b\[[0-9;]*m', '', p.stdout + p.stderr).strip()


def main():
    bad = []
    for line, stdin in STEPS:
        code, out = run(line, stdin)
        ok = code == 0 and '(error)' not in out and 'Exception in thread' not in out
        print(('ok   ' if ok else 'FAIL ') + line[:110], flush=True)
        if ARGS.verbose or not ok:
            print('     ' + '\n     '.join(out.splitlines()[-8:]))
        if not ok:
            bad.append(line)
    for line in MUST_FAIL:
        code, out = run(line)
        ok = code not in (0, None) and 'Exception in thread' not in out and '\tat ' not in out
        print(('ok   ' if ok else 'FAIL ') + '(phải lỗi) ' + line[:100], flush=True)
        if ARGS.verbose or not ok:
            print('     ' + '\n     '.join(out.splitlines()[-4:]))
        if not ok:
            bad.append(line)
    print(f'\n{len(STEPS) + len(MUST_FAIL)} lệnh, {len(bad)} lỗi')
    return 1 if bad else 0


raise SystemExit(main())
