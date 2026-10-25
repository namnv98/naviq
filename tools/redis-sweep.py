#!/usr/bin/env python3
"""Chạy MỌI lệnh/lệnh con của server qua sqlctx (batch --command), mỗi lệnh trên dữ liệu mẫu dựng lại từ đầu.
Tham số mẫu sinh từ COMMAND DOCS: bắt buộc thì điền giá trị hợp lý, tuỳ chọn thì bỏ, oneof lấy nhánh đầu,
multiple lấy 1 lần. Ra: samples.tsv (tên, dòng lệnh), results.json (exit code, output) và danh sách lệnh lỗi.

CHỈ chạy với 1 Redis dùng xong bỏ: trước mỗi lệnh script chạy FLUSHALL, FUNCTION FLUSH, REPLICAOF NO ONE...

    docker run -d --name sqlctx-sweep -p 63791:6379 redis:7.4
    sqlctx --dialect=redis --host=127.0.0.1 --port=63791 --command=PING   # tạo cache COMMAND DOCS ~/.sqlctx/redis-docs
    tools/redis-sweep.py --jar target/sqlctx-1.0.0.jar
    docker rm -f sqlctx-sweep
"""
import argparse, glob, json, os, re, subprocess

ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
ap.add_argument('--jar', default='target/sqlctx-1.0.0.jar')
ap.add_argument('--container', default='sqlctx-sweep', help='container Redis dùng xong bỏ (để dựng dữ liệu mẫu)')
ap.add_argument('--port', default='63791', help='cổng của container đó trên máy')
ap.add_argument('--docs', help='file COMMAND DOCS đã cache (mặc định: file mới nhất trong ~/.sqlctx/redis-docs)')
ap.add_argument('--out', default='.', help='thư mục ghi samples.tsv, results.json')
ARGS = ap.parse_args()

JAR = ARGS.jar
DOCS_FILE = ARGS.docs or max(glob.glob(os.path.expanduser('~/.sqlctx/redis-docs/*.json')), key=os.path.getmtime)
DOCS = json.load(open(DOCS_FILE))['docs']
CLI = ['docker', 'exec', '-i', ARGS.container, 'redis-cli']

KEY_BY_GROUP = {'string': 's', 'bitmap': 's', 'hyperloglog': 'hll', 'hash': 'h', 'list': 'l', 'set': 'st',
                'sorted-set': 'z', 'geo': 'g', 'stream': 'x'}
SCRIPT = 'return 1'
SHA = 'e0e1f9fabfc9d4800c877a703b823ac0578ff8db'  # sha1("return 1")

FIXTURE = r'''REPLICAOF NO ONE
CLIENT UNPAUSE
ACL DELUSER tmpuser
FUNCTION FLUSH
FLUSHALL
SET s 10
HSET h f v
RPUSH l e e2
SADD st m e
ZADD z 1 m 2 n
GEOADD g 1 1 m
PFADD hll a
XADD x 0-1 f v
XGROUP CREATE x g1 0
XREADGROUP GROUP g1 c1 STREAMS x >
SCRIPT LOAD "return 1"
FUNCTION LOAD "#!lua name=lib\nredis.register_function('f', function() return 1 end)"
'''

SKIP = {'SHUTDOWN': 'tắt server'}


def q(v):
    return '"' + v.replace('\\', '\\\\').replace('"', '\\"') + '"' if re.search(r'[\s"\']', v) else v


def value(cmd, a, group):
    n, t = a['name'], a['type']
    if t == 'key':
        return KEY_BY_GROUP.get(group, 's')
    if t == 'pattern':
        return '*'
    if t == 'unix-time':
        return '4102444800'
    if t in ('integer', 'double'):
        return {'db': '1', 'index': '0', 'index1': '0', 'index2': '1', 'destination-db': '1'}.get(n, '1')
    # string
    special = {
        'script': SCRIPT, 'sha1': SHA, 'function': 'f', 'library-name': 'lib', 'field': 'f', 'value': 'v',
        'member': 'm', 'element': 'e', 'pivot': 'e', 'channel': 'c', 'shardchannel': 'c', 'consumer': 'c1',
        'parameter': 'maxmemory', 'section': 'server', 'password': 'x', 'message': 'hi', 'threshold': '100',
        'min-idle-time': '0', 'host': '127.0.0.1', 'ip:port': '1.2.3.4:5', 'command': 'get',
        'function-code': "#!lua name=lib2\nredis.register_function('f2', function() return 2 end)",
    }
    if n == 'group':
        return 'g2' if cmd == 'XGROUP CREATE' else 'g1'
    if n == 'username':
        return 'tmpuser' if cmd in ('ACL SETUSER', 'ACL DELUSER') else 'default'
    if n in ('id', 'last-id'):
        return {'XREADGROUP': '>', 'XREAD': '0', 'XSETID': '0-5', 'XGROUP SETID': '0', 'XGROUP CREATE': '0'}.get(cmd, '0-1')
    if n in ('start', 'end') and group == 'stream':
        return {'XAUTOCLAIM': '0-0'}.get(cmd, '-' if n == 'start' else '+')
    if n in ('min', 'max'):
        if 'LEX' in cmd:
            return '-' if n == 'min' else '+'
        return '0' if n == 'min' else '-1'
    if cmd == 'CONFIG SET' and n == 'value':
        return '0'
    return special.get(n, 'x')


def args(cmd, arglist, group):
    out = []
    for a in arglist:
        if 'optional' in a.get('flags', []):
            continue
        t = a['type']
        if t == 'pure-token':
            out.append(q(a['token']) if a.get('token') else '""')
            continue
        if a.get('token'):
            out.append(a['token'])
        if t == 'oneof':
            out += args(cmd, [dict(a['arguments'][0], flags=[])], group)
        elif t == 'block':
            out += args(cmd, a['arguments'], group)
        else:
            out.append(q(value(cmd, a, group)))
    return out


def samples():
    for name in sorted(DOCS):
        d = DOCS[name]
        subs = d.get('subcommands') or {}
        if subs:
            for sub in sorted(subs):
                sd = subs[sub]
                full = name.upper() + ' ' + sub.split('|')[1].upper()
                yield full, ' '.join([full] + args(full, sd.get('arguments', []), sd.get('group', d.get('group'))))
        else:
            yield name.upper(), ' '.join([name.upper()] + args(name.upper(), d.get('arguments', []), d.get('group')))


def main():
    rows = list(samples())
    with open(os.path.join(ARGS.out, 'samples.tsv'), 'w') as f:
        for n, line in rows:
            f.write(n + '\t' + line + '\n')
    results = []
    for i, (n, line) in enumerate(rows):
        if n in SKIP:
            results.append({'name': n, 'line': line, 'skipped': SKIP[n]})
            continue
        subprocess.run(CLI, input=FIXTURE, capture_output=True, text=True)
        try:
            p = subprocess.run(['java', '-jar', JAR, '--dialect=redis', '--host=127.0.0.1', '--port=' + ARGS.port, '--yes',
                                '--command=' + line], capture_output=True, text=True, timeout=6)
            out = re.sub(r'\x1b\[[0-9;]*m', '', p.stdout + p.stderr).strip()
            results.append({'name': n, 'line': line, 'exit': p.returncode, 'out': out[-600:]})
        except subprocess.TimeoutExpired:
            results.append({'name': n, 'line': line, 'timeout': True})
        if i % 50 == 0:
            print(i, '/', len(rows), flush=True)
    json.dump(results, open(os.path.join(ARGS.out, 'results.json'), 'w'), ensure_ascii=False, indent=1)
    bad = [r for r in results if r.get('timeout') or r.get('exit', 0) != 0 or '(error)' in r.get('out', '')]
    print('xong', len(results), 'lệnh,', len(bad), 'lỗi/timeout (lỗi do tham số mẫu chưa hợp lý cũng tính):')
    for r in bad:
        print('  ' + r['line'][:80], '->', 'TIMEOUT' if r.get('timeout') else r['out'].splitlines()[-1][:120] if r['out'] else r['exit'])


main()
