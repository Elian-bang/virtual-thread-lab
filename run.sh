#!/usr/bin/env bash
# 조건 하나를 돌린다.
#   run.sh <MODEL> <CONC> <CONN> <SYNC> <WARMUP> <RUN> <PATH> <CPUS>
#   캐리어 수는 LAB_CARRIER 환경변수 (0 = JVM 기본)
#
# 여기서 지키는 것 두 가지.
#  1. 앱이 준비 안 됐으면 부하를 걸지 않는다 — 실패한 측정이 조용히 섞이지 않게.
#  2. 컨테이너 이름 대신 IP 로 붙는다 — 같은 이름을 재사용하면 Docker DNS 가
#     옛 IP 를 잠시 물고 있어서, 직전 조건의 컨테이너에 부하가 걸린다.
#     실제로 DB 를 안 쓰는 /sleep 실행에 connMax=10 이 찍히는 오염이 났다.
set -u
export MSYS_NO_PATHCONV=1

MODEL=$1; CONC=$2; CONN=$3; SYNC=${4:-false}; WARM=${5:-15}; RUN=${6:-30}
PATHQ=${7:-/send}; CPUS=${8:-1}; CARRIER=${LAB_CARRIER:-0}

VIRTUAL=false; if [ "$MODEL" = "VIRTUAL" ]; then VIRTUAL=true; fi
CARRIER_OPTS=""
if [ "$CARRIER" != "0" ]; then
  CARRIER_OPTS="-Djdk.virtualThreadScheduler.parallelism=$CARRIER -Djdk.virtualThreadScheduler.maxPoolSize=$CARRIER"
fi

TAG="$$-${RANDOM}"
NAME="vtlab-app-${TAG}"
LABEL="$MODEL conc=$CONC conn=$CONN sync=$SYNC cpus=$CPUS path=$PATHQ carrier=$CARRIER"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" --network vtlab-net \
  --cpus="$CPUS" --memory=1g \
  -e DB_HOST=vtlab-db -e LAB_MODEL="$MODEL" -e LAB_VIRTUAL="$VIRTUAL" \
  -e LAB_CONN="$CONN" -e LAB_SYNC="$SYNC" -e LAB_POOL=200 \
  -v "$(pwd -W)/app/target":/app \
  eclipse-temurin:21-jre \
  java -XX:+UseSerialGC -Xmx512m -Xss512k -Djdk.tracePinnedThreads=full $CARRIER_OPTS -jar /app/app.jar >/dev/null

IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME" 2>/dev/null || echo "")
if [ -z "$IP" ]; then echo "$LABEL SKIPPED no_ip"; exit 0; fi
BASE="http://${IP}:8080"

READY=no
for i in $(seq 1 90); do
  if docker run --rm --network vtlab-net curlimages/curl:latest \
       -sf -o /dev/null --max-time 10 "${BASE}${PATHQ}?seq=0" 2>/dev/null; then
    READY=yes; break
  fi
  sleep 2
done
if [ "$READY" != "yes" ]; then echo "$LABEL SKIPPED app_not_ready"; exit 0; fi

OUT=$(docker run --rm --network vtlab-net -v "$(pwd -W)/loadgen":/lg -w /lg \
  eclipse-temurin:21-jdk java LoadGen.java "$BASE" "$CONC" "$WARM" "$RUN" "$PATHQ" 2>&1 \
  | grep -E '^(RESULT|FIRSTERR)' | tr '\n' ' ' || echo "RESULT FAILED")

ALIVE=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo false)
OOM=$(docker inspect -f '{{.State.OOMKilled}}' "$NAME" 2>/dev/null || echo false)
if [ "$ALIVE" != "true" ]; then echo "$LABEL DIED oom=$OOM"; exit 0; fi

PIN=$(docker logs "$NAME" 2>&1 | grep -c "onPinned" || true)
echo "$LABEL pinned=$PIN $OUT"
