# -*- coding: utf-8 -*-
"""설계-v2.md 의 실험 블록을 실행 계획으로 펼친다.

반복마다 47개 조건을 무작위 순서로 한 바퀴 돈다 (시드 고정).
출력: results/v2/plan.tsv  —  run_id  block  rep  order  model  path  conc  conn  sync  jdk  cj  carrier
"""
import io
import os
import random

SEED = 20260914
REPS = 5

conds = []  # (block, model, path, conc, conn, sync, jdk, cj, carrier)

# E1 기준 재측정 — JDK 21 · CJ 8.3.0 · NONE
for m in ('PLATFORM', 'VIRTUAL'):
    for c in (100, 500, 2000):
        conds.append(('E1', m, '/sleep', c, 50, 'NONE', 21, '8.3.0', 0))
for m in ('PLATFORM', 'CF', 'VIRTUAL'):
    for c in (100, 1000):
        for p in (10, 50):
            conds.append(('E1', m, '/send', c, p, 'NONE', 21, '8.3.0', 0))

# E2 원인 제거 — /send · 풀 50 · NONE (E1 과 겹치는 JDK21·CJ8.3.0 칸 제외)
for m in ('PLATFORM', 'VIRTUAL'):
    for c in (100, 1000):
        for jdk in (21, 25):
            for cj in ('8.3.0', '9.7.0'):
                if jdk == 21 and cj == '8.3.0':
                    continue
                conds.append(('E2', m, '/send', c, 50, 'NONE', jdk, cj, 0))

# E3 락 범위 — /send · 동시 100 · 풀 50 · CJ 9.7.0
for m in ('PLATFORM', 'VIRTUAL'):
    for s in ('GLOBAL_ALL', 'GLOBAL_JDBC'):
        for jdk in (21, 25):
            conds.append(('E3', m, '/send', 100, 50, s, jdk, '9.7.0', 0))

# E4 캐리어 — VIRTUAL /send · 동시 100 · 풀 50 · JDK 21
for w in (1, 2, 4, 8, 16, 32):
    conds.append(('E4', 'VIRTUAL', '/send', 100, 50, 'NONE', 21, '8.3.0', w))
for w in (1, 8, 32):
    conds.append(('E4', 'VIRTUAL', '/send', 100, 50, 'NONE', 21, '9.7.0', w))

assert len(conds) == 47, len(conds)
assert len(set(conds)) == 47, 'duplicate condition'

rng = random.Random(SEED)
os.makedirs('results/v2', exist_ok=True)
with io.open('results/v2/plan.tsv', 'w', encoding='utf-8', newline='\n') as f:
    f.write('run_id\tblock\trep\torder\tmodel\tpath\tconc\tconn\tsync\tjdk\tcj\tcarrier\n')
    n = 0
    for rep in range(1, REPS + 1):
        order = conds[:]
        rng.shuffle(order)
        for i, (b, m, pth, c, p, s, jdk, cj, w) in enumerate(order, 1):
            n += 1
            run_id = 'r%d-%02d-%s' % (rep, i, b)
            f.write('\t'.join(map(str, (run_id, b, rep, i, m, pth, c, p, s, jdk, cj, w))) + '\n')
print('조건 %d개 × 반복 %d = 실행 %d회 → results/v2/plan.tsv' % (len(conds), REPS, n))
