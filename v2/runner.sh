#!/usr/bin/env bash
# results/v2/plan.tsv 를 순서대로 실행한다.
#
#   runner.sh [LIMIT]      LIMIT 만큼만 (생략하면 끝까지)
#
# - 이미 끝난 run_id (state.txt 가 있는 것)는 건너뛴다 → 중간에 멈춰도 다시 실행하면 이어서 돈다
# - 두 개가 동시에 돌지 못하게 잠금을 건다 (v1 에서 동시 실행으로 측정이 오염됐다)
# - 진행 상황을 results/v2/progress.log 에 한 줄씩 남긴다
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

WARM=${WARM:-30}
MEAS=${MEAS:-60}
LIMIT=${1:-0}
PLAN=${PLAN:-results/v2/plan.tsv}
LOG=results/v2/progress.log
LOCK=results/v2/.runner.lock

[ -f "$PLAN" ] || { echo "plan.tsv 없음 — python v2/plan.py 먼저" >&2; exit 1; }
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "다른 runner 가 돌고 있다 ($LOCK). 중단." >&2
  exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

LEFT=$(docker ps -aq --filter "name=vtlab-app-" | wc -l)
if [ "$LEFT" -ne 0 ]; then
  echo "vtlab-app 컨테이너가 $LEFT 개 남아 있다. 정리 후 다시." >&2
  exit 1
fi
docker exec vtlab-db mysql -uroot -plabpass -e "SELECT 1" >/dev/null 2>&1 || { echo "vtlab-db 가 응답하지 않는다." >&2; exit 1; }

TOTAL=$(($(wc -l < "$PLAN") - 1))
DONE=0
RAN=0
tail -n +2 "$PLAN" | while IFS=$'\t' read -r RUN_ID BLOCK REP ORDER MODEL PATHQ CONC CONN SYNC JDK CJ CARRIER; do
  if [ -f "results/v2/runs/$RUN_ID/state.txt" ]; then
    continue
  fi
  if [ "$LIMIT" != "0" ] && [ "$RAN" -ge "$LIMIT" ]; then
    break
  fi
  S=$(date +%s)
  STATE=$(bash v2/run.sh "$RUN_ID" "$MODEL" "$PATHQ" "$CONC" "$CONN" "$SYNC" "$JDK" "$CJ" "$CARRIER" "$WARM" "$MEAS" 1 | tail -1)
  E=$(( $(date +%s) - S ))
  RAN=$((RAN + 1))
  FIN=$(ls results/v2/runs 2>/dev/null | wc -l)
  printf '%s\t%s\t%ss\t%s/%s\t%s %s c%s p%s %s jdk%s cj%s w%s\n' \
    "$(date '+%F %T')" "$RUN_ID" "$E" "$FIN" "$TOTAL" "$STATE" "$MODEL$PATHQ" "$CONC" "$CONN" "$SYNC" "$JDK" "$CJ" "$CARRIER" | tee -a "$LOG"
done
echo "runner 종료 $(date '+%F %T')" | tee -a "$LOG"
