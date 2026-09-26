# -*- coding: utf-8 -*-
"""v2 원자료(results/v2/runs/*)를 읽어 제외 규칙을 적용하고, 조건별 표와 가설 판정을 만든다.

판정 기준은 docs/설계-v2.md 에 측정 전에 고정한 것만 쓴다.
출력: results/v2/집계-v2.md · results/v2/runs.csv
"""
import csv
import io
import json
import os
import random
import statistics as st
import sys

RUNS = 'results/v2/runs'
PLAN = os.environ.get('PLAN', 'results/v2/plan.tsv')
B = 10000
rng = random.Random(7)


def read_kv(path):
    d = {}
    if os.path.exists(path):
        for line in io.open(path, encoding='utf-8'):
            if '=' in line:
                k, v = line.rstrip('\n').split('=', 1)
                d[k] = v
    return d


def read_json_after_brace(path):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return None
    s = io.open(path, encoding='utf-8').read().strip()
    try:
        return json.loads(s[s.index('{'):])
    except Exception:
        return None


def cpu_stat(path):
    d = {}
    if os.path.exists(path):
        for line in io.open(path, encoding='utf-8'):
            p = line.split()
            if len(p) == 2 and p[1].isdigit():
                d[p[0]] = int(p[1])
    return d


plan = list(csv.DictReader(io.open(PLAN, encoding='utf-8'), delimiter='\t'))
rows, excluded, pending = [], [], []
for pl in plan:
    rid = pl['run_id']
    o = os.path.join(RUNS, rid)
    if not os.path.exists(os.path.join(o, 'state.txt')):
        pending.append(rid)
        continue
    state = io.open(os.path.join(o, 'state.txt'), encoding='utf-8').read().strip()
    lg = read_json_after_brace(os.path.join(o, 'loadgen.out'))
    who = read_json_after_brace(os.path.join(o, 'whoami.json'))
    pin = read_json_after_brace(os.path.join(o, 'pinned.json'))
    stat = (lg or {}).get('stat') if lg else None
    reason = None
    if state != 'ok':
        reason = 'state=' + state
    elif not isinstance(stat, dict):
        reason = 'stat 없음'
    elif not who:
        reason = 'whoami 없음'
    else:
        want_virtual = pl['model'] == 'VIRTUAL'
        if bool(who.get('virtual')) != want_virtual:
            reason = 'whoami 모델 불일치'
        elif not str(who.get('javaVersion', '')).startswith(pl['jdk'] + '.'):
            reason = 'whoami Java 불일치 (%s)' % who.get('javaVersion')
        elif str(who.get('mysqlDriver')) != pl['cj']:
            reason = 'whoami 드라이버 불일치 (%s)' % who.get('mysqlDriver')
        elif lg.get('err', 0) > 0:
            reason = '측정창 오류 %d' % lg['err']
    if reason:
        excluded.append((rid, reason))
        continue
    cb, ca = cpu_stat(os.path.join(o, 'cpu_before')), cpu_stat(os.path.join(o, 'cpu_after'))
    r = dict(pl)
    r.update({
        'tps': lg['tps'], 'p50': lg['p50'], 'p95': lg['p95'], 'p99': lg['p99'], 'max': lg['max'],
        'perSecCv': lg['perSecCv'], 'drained': lg['drained'],
        'cpuUtil': stat.get('cpuUtil'), 'gcMs': stat.get('gcMs'), 'threads': stat.get('peakThreadCount'),
        'connWaitMs': stat.get('connAcquireMeanMs'),
        'appTotalMs': stat.get('appTotalMeanMs'), 'appDbMs': stat.get('appDbMeanMs'),
        'appChannelMs': stat.get('appChannelMeanMs'), 'appLockMs': stat.get('appLockWaitMeanMs'),
        'appOtherMs': stat.get('appOtherMeanMs'),
        'pinnedCount': (pin or {}).get('pinnedCount'), 'pinnedMs': (pin or {}).get('pinnedTotalMs'),
        'throttledMs': (ca.get('throttled_usec', 0) - cb.get('throttled_usec', 0)) / 1000.0 if ca and cb else None,
    })
    rows.append(r)

KEY = ('block', 'model', 'path', 'conc', 'conn', 'sync', 'jdk', 'cj', 'carrier')
groups = {}
for r in rows:
    groups.setdefault(tuple(r[k] for k in KEY), []).append(r)
planned = {}
for pl in plan:
    planned.setdefault(tuple(pl[k] for k in KEY), 0)
    planned[tuple(pl[k] for k in KEY)] += 1


def boot_median(v):
    if len(v) < 2:
        return (v[0], v[0]) if v else (None, None)
    ms = sorted(st.median(rng.choices(v, k=len(v))) for _ in range(B))
    return ms[int(0.025 * B)], ms[int(0.975 * B) - 1]


def boot_ratio(a, b):
    if len(a) < 2 or len(b) < 2:
        return None, None
    rs = sorted(st.median(rng.choices(a, k=len(a))) / st.median(rng.choices(b, k=len(b))) for _ in range(B))
    return rs[int(0.025 * B)], rs[int(0.975 * B) - 1]


def cv(v):
    return st.pstdev(v) / st.mean(v) if len(v) > 1 and st.mean(v) else 0.0


def cell(block, model, path, conc, conn, sync, jdk, cj, carrier):
    k = (block, model, path, str(conc), str(conn), sync, str(jdk), cj, str(carrier))
    return groups.get(k, [])


def usable(g, key):
    """5회 중 2회 이상 빠지면 판정에 쓰지 않는다."""
    return len(g) >= planned.get(key, 5) - 1 and len(g) >= 2


out = io.open(os.environ.get('OUTMD', 'results/v2/집계-v2.md'), 'w', encoding='utf-8')
w = lambda s='': out.write(s + '\n')
w('# 집계 v2')
w()
w('`v2/analyze.py` 가 `results/v2/runs/*` 원자료에서 만든다. 손으로 고치지 않는다.')
w('중앙값 [부트스트랩 95% 신뢰구간]. 판정 기준은 [`docs/설계-v2.md`](../../docs/설계-v2.md).')
w()
w('- 계획 %d회 · 끝남 %d회 · 분석 사용 %d회 · 제외 %d회 · 남음 %d회' % (len(plan), len(rows) + len(excluded), len(rows), len(excluded), len(pending)))
w()
if excluded:
    w('## 제외한 실행')
    w()
    for rid, why in excluded:
        w('- `%s` — %s' % (rid, why))
    w()

w('## 조건별')
w()
w('| 블록 | 모델 | 경로 | 동시 | 풀 | 락 | JDK | CJ | 캐리어 | n | TPS | TPS 변동 | p50 ms | p99 ms | CPU | 앱 DB ms | 앱 외부대기 ms | 앱 기타 ms | pinned 총 ms | 스레드 |')
w('|---|---|---|---:|---:|---|---:|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
for k in sorted(groups, key=lambda k: (k[0], k[2], k[1], int(k[3]), int(k[4]), k[5], k[6], k[7], int(k[8]))):
    g = groups[k]
    t = [r['tps'] for r in g]
    lo, hi = boot_median(t)
    c = cv(t)
    med = lambda f: st.median([r[f] for r in g if r[f] is not None]) if any(r[f] is not None for r in g) else None
    fmt = lambda v, d=1: '—' if v is None else ('%.' + str(d) + 'f') % v
    w('| %s | %s | %s | %s | %s | %s | %s | %s | %s | %d | **%s** [%s, %s] | %s%s | %s | %s | %s | %s | %s | %s | %s | %s |' % (
        k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], ('기본' if k[8] == '0' else k[8]), len(g),
        fmt(st.median(t)), fmt(lo), fmt(hi), fmt(c * 100), '% 불안정' if c > 0.10 else '%',
        fmt(med('p50')), fmt(med('p99')), fmt(med('cpuUtil'), 2), fmt(med('appDbMs')), fmt(med('appChannelMs')),
        fmt(med('appOtherMs')), fmt(med('pinnedMs')), fmt(med('threads'), 0)))
w()


def verdict_line(name, text):
    w('- **%s** — %s' % (name, text))


w('## 가설 판정')
w()


def tps(g):
    return [r['tps'] for r in g]


def stable(g):
    return cv(tps(g)) <= 0.10


# H-pin
w('### H-pin — 드라이버 monitor pinning 이 주원인인가')
w()
w('| 동시 | 조건 | G = PLATFORM/VIRTUAL [95%] | VIRTUAL pinned 총 ms (중앙) |')
w('|---:|---|---|---:|')
hpin = []
for conc in (100, 1000):
    base_p = cell('E1', 'PLATFORM', '/send', conc, 50, 'NONE', 21, '8.3.0', 0)
    base_v = cell('E1', 'VIRTUAL', '/send', conc, 50, 'NONE', 21, '8.3.0', 0)
    res = {}
    for label, blk, jdk, cj in (('JDK21·CJ8.3 (기준)', 'E1', 21, '8.3.0'), ('JDK21·CJ9.7', 'E2', 21, '9.7.0'),
                                ('JDK25·CJ8.3', 'E2', 25, '8.3.0'), ('JDK25·CJ9.7', 'E2', 25, '9.7.0')):
        gp = cell(blk, 'PLATFORM', '/send', conc, 50, 'NONE', jdk, cj, 0)
        gv = cell(blk, 'VIRTUAL', '/send', conc, 50, 'NONE', jdk, cj, 0)
        lo, hi = boot_ratio(tps(gp), tps(gv))
        g = st.median(tps(gp)) / st.median(tps(gv)) if gp and gv else None
        pm = st.median([r['pinnedMs'] for r in gv if r['pinnedMs'] is not None]) if gv and any(r['pinnedMs'] is not None for r in gv) else None
        res[label] = (g, lo, hi, pm, stable(gp) and stable(gv) if gp and gv else False, len(gp) >= 4 and len(gv) >= 4)
        w('| %d | %s | %s [%s, %s] | %s |' % (conc, label, '—' if g is None else '%.2f' % g,
                                            '—' if lo is None else '%.2f' % lo, '—' if hi is None else '%.2f' % hi,
                                            '—' if pm is None else '%.0f' % pm))
    b = res['JDK21·CJ8.3 (기준)']
    ok_all = all(v[0] is not None and v[5] for v in res.values())
    if not ok_all:
        hpin.append((conc, '판정 불가 (조건 결손)'))
        continue
    if not all(v[4] for v in res.values()):
        hpin.append((conc, '판정 불가 (불안정 조건 포함)'))
        continue
    decided = []
    for label in ('JDK21·CJ9.7', 'JDK25·CJ8.3'):
        g, lo, hi, pm, _, _ = res[label]
        smaller = g < b[0] and hi < b[1]
        pin_gone = pm is not None and b[3] is not None and b[3] > 0 and pm < 0.05 * b[3]
        decided.append(smaller and pin_gone)
    hpin.append((conc, '지지' if all(decided) else '반증'))
w()
for conc, v in hpin:
    verdict_line('동시 %d' % conc, v)
w()

# H-mutex / H-sync-pin
w('### H-mutex · H-sync-pin — 락 범위')
w()
w('| 락 | JDK | VIRTUAL/PLATFORM [95%] |')
w('|---|---:|---|')
ratios = {}
for s in ('GLOBAL_ALL', 'GLOBAL_JDBC'):
    for jdk in (21, 25):
        gp = cell('E3', 'PLATFORM', '/send', 100, 50, s, jdk, '9.7.0', 0)
        gv = cell('E3', 'VIRTUAL', '/send', 100, 50, s, jdk, '9.7.0', 0)
        lo, hi = boot_ratio(tps(gv), tps(gp))
        rr = st.median(tps(gv)) / st.median(tps(gp)) if gp and gv else None
        ratios[(s, jdk)] = (rr, lo, hi, (stable(gp) and stable(gv)) if gp and gv else False, len(gp) >= 4 and len(gv) >= 4)
        w('| %s | %d | %s [%s, %s] |' % (s, jdk, '—' if rr is None else '%.2f' % rr,
                                         '—' if lo is None else '%.2f' % lo, '—' if hi is None else '%.2f' % hi))
w()
ga = [ratios[('GLOBAL_ALL', j)] for j in (21, 25)]
if all(x[0] is not None and x[3] and x[4] for x in ga):
    verdict_line('H-mutex', '지지' if all(x[1] <= 1 <= x[2] for x in ga) else '반증')
else:
    verdict_line('H-mutex', '판정 불가')
g21, g25 = ratios[('GLOBAL_JDBC', 21)], ratios[('GLOBAL_JDBC', 25)]
if all(x[0] is not None and x[3] and x[4] for x in (g21, g25)):
    verdict_line('H-sync-pin', '지지' if (g21[0] < g25[0] and g21[2] < g25[1]) else '반증')
else:
    verdict_line('H-sync-pin', '판정 불가')
w()

# H-carrier
w('### H-carrier — 캐리어 민감도')
w()
w('| CJ | 캐리어 1 TPS | 캐리어 32 TPS | 32/1 [95%] |')
w('|---|---:|---:|---|')
cr = {}
for cj in ('8.3.0', '9.7.0'):
    g1 = cell('E4', 'VIRTUAL', '/send', 100, 50, 'NONE', 21, cj, 1)
    g32 = cell('E4', 'VIRTUAL', '/send', 100, 50, 'NONE', 21, cj, 32)
    lo, hi = boot_ratio(tps(g32), tps(g1))
    rr = st.median(tps(g32)) / st.median(tps(g1)) if g1 and g32 else None
    cr[cj] = (rr, lo, hi, (stable(g1) and stable(g32)) if g1 and g32 else False, len(g1) >= 4 and len(g32) >= 4)
    w('| %s | %s | %s | %s [%s, %s] |' % (cj, '—' if not g1 else '%.1f' % st.median(tps(g1)),
                                          '—' if not g32 else '%.1f' % st.median(tps(g32)),
                                          '—' if rr is None else '%.2f' % rr, '—' if lo is None else '%.2f' % lo,
                                          '—' if hi is None else '%.2f' % hi))
w()
a, b = cr['8.3.0'], cr['9.7.0']
if all(x[0] is not None and x[3] and x[4] for x in (a, b)):
    verdict_line('H-carrier', '지지' if (a[0] > b[0] and a[1] > b[2]) else '반증')
else:
    verdict_line('H-carrier', '판정 불가')
out.close()

with io.open('results/v2/runs.csv', 'w', encoding='utf-8', newline='') as f:
    if rows:
        wr = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        wr.writeheader()
        wr.writerows(rows)
print('끝남 %d · 사용 %d · 제외 %d · 남음 %d → results/v2/집계-v2.md' % (len(rows) + len(excluded), len(rows), len(excluded), len(pending)))
