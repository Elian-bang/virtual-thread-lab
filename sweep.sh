#!/usr/bin/env bash
# 본 측정. 조건당 3회. 결과는 results/raw.tsv 에 누적한다.
set -u
export MSYS_NO_PATHCONV=1
OUT=results/raw.tsv
: > "$OUT"
W=15; R=20

log() { echo "$1" | tee -a "$OUT"; }

# A) 순수 I/O — 드라이버를 빼고 가상 스레드 자체를 본다
for rep in 1 2 3; do
  for M in PLATFORM CF VIRTUAL; do
    for C in 100 500 2000; do
      log "$(./run.sh $M $C 50 false $W $R /sleep 1) rep=$rep block=A"
    done
  done
done

# B) DB 접촉 — 커넥션 풀을 바꿔가며. H2 재정의판
for rep in 1 2 3; do
  for M in PLATFORM CF VIRTUAL; do
    for C in 100 1000; do
      for P in 10 50; do
        log "$(./run.sh $M $C $P false $W $R /send 1) rep=$rep block=B"
      done
    done
  done
done

# C) 캐리어 스윕 — CPU 는 1개 고정. 캐리어 병목의 직접 증거
for rep in 1 2 3; do
  for WK in 1 8 32; do
    log "$(LAB_CARRIER=$WK ./run.sh VIRTUAL 100 50 false $W $R /send 1) rep=$rep block=C carrier=$WK"
  done
done

# D) synchronized — H3
for rep in 1 2 3; do
  for S in false true; do
    log "$(./run.sh VIRTUAL 100 50 $S $W $R /send 1) rep=$rep block=D"
  done
done
echo "SWEEP DONE"
