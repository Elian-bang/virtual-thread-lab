# -*- coding: utf-8 -*-
"""raw.tsv 를 조건별 표로 접는다. 3회 반복의 중앙값과 폭을 같이 낸다.
   한 번만 잰 값을 평균처럼 보이게 쓰지 않기 위해서다."""
import io, re, sys, json, statistics as st
from collections import defaultdict

rows = []
for line in io.open('results/raw.tsv', encoding='utf-8'):
    line = line.strip()
    if 'RESULT' not in line:
        if line: rows.append(('BAD', line))
        continue
    d = dict(re.findall(r'(\w+)=([^\s]+)', line.split('RESULT')[0]))
    r = dict(re.findall(r'(\w+)=([\d.]+)', line.split('RESULT')[1]))
    m = re.search(r'stat=(\{.*?\})', line)
    stat = {}
    if m:
        try: stat = json.loads(m.group(1))
        except Exception: pass
    d['model'] = line.split()[0]
    d.update({k: float(v) for k, v in r.items()})
    d['threads'] = stat.get('peakThreadCount', -1)
    d['connWaitMs'] = stat.get('connAcquireMeanMs', -1)
    d['block'] = dict(re.findall(r'(block)=(\w+)', line)).get('block', '?')
    rows.append(('OK', d))

bad = [r for k, r in rows if k == 'BAD']
ok  = [r for k, r in rows if k == 'OK']

g = defaultdict(list)
for d in ok:
    key = (d['block'], d['path'], d['model'], d['conc'], d['conn'], d['carrier'], d['sync'])
    g[key].append(d)

out = io.open('results/집계.md', 'w', encoding='utf-8')
def w(s=''): out.write(s + '\n')

w('# 집계')
w()
w('`results/raw.tsv` 를 조건별로 접었다. **중앙값**과 3회의 **최소~최대**를 같이 적는다.')
w('한 번만 잰 값을 평균처럼 보이게 쓰지 않기 위해서다.')
w()
if bad:
    w('## 버린 실행')
    w()
    for b in bad: w('- `' + b + '`')
    w()

def med(v): return st.median(v) if v else -1
def fmt(v): return ('%.1f' % v) if v >= 0 else '—'

for blk, title in [('A', 'A. 순수 I/O — DB 를 안 건드린다'),
                   ('B', 'B. DB 접촉 — 커넥션 풀을 바꿔가며'),
                   ('C', 'C. 캐리어 수 스윕 (CPU 는 1개 고정)'),
                   ('D', 'D. synchronized 의 영향')]:
    keys = sorted([k for k in g if k[0] == blk], key=lambda k: (k[2], float(k[3]), float(k[4]), float(k[5]), k[6]))
    if not keys: continue
    w('## ' + title)
    w()
    w('| 모델 | 동시 | 커넥션 | 캐리어 | sync | n | TPS(중앙) | TPS 범위 | p50 | p95 | p99 | 스레드 | 커넥션대기 | pinned |')
    w('|---|---:|---:|---:|---|---:|---:|---|---:|---:|---:|---:|---:|---:|')
    for k in keys:
        v = g[k]
        tps = [d['tps'] for d in v]
        w('| %s | %s | %s | %s | %s | %d | **%s** | %s~%s | %s | %s | %s | %d | %s | %d |' % (
            k[2], k[3], k[4], ('기본' if k[5] == '0' else k[5]), k[6], len(v),
            fmt(med(tps)), fmt(min(tps)), fmt(max(tps)),
            fmt(med([d['p50'] for d in v])), fmt(med([d['p95'] for d in v])),
            fmt(med([d['p99'] for d in v])),
            int(med([d['threads'] for d in v])),
            fmt(med([d['connWaitMs'] for d in v])),
            int(med([float(d.get('pinned', 0)) for d in v]))))
    w()
out.close()
print('조건 %d개 / 실행 %d개 / 버린 실행 %d개' % (len(g), len(ok), len(bad)))
