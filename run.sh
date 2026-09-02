#!/usr/bin/env bash
# 조건 하나를 돌린다.
#   run.sh <MODEL> <CONC> <CONN> <SYNC> <WARMUP> <RUN> <PATH> <CPUS>
#   캐리어 수는 환경변수 LAB_CARRIER 로 준다 (0 = JVM 기본 = nproc)
#
# 앱이 안 떴는데 부하를 걸면 실패한 측정이 조용히 데이터에 섞인다.
# 그래서 준비 확인에 실패하면 여기서 멈춘다.
set -u
export MSYS_NO_PATHCONV=1

MODEL=$1; CONC=$2; CONN=$3; SYNC=${4:-false}; WARM=${5:-15}; RUN=${6:-30}
PATHQ=${7:-/send}; CPUS=${8:-1}; CARRIER=${LAB_CARRIER:-0}

VIRTUAL=false; if [ "$MODEL" = "VIRTUAL" ]; then VIRTUAL=true; fi
CARRIER_OPTS=""
if [ "$CARRIER" != "0" ]; then
  CARRIER_OPTS="-Djdk.virtualThreadScheduler.parallelism=$CARRIER -Djdk.virtualThreadScheduler.maxPoolSize=$CARRIER"
fi

docker rm -f vtlab-app >/dev/null 2>&1 || true
docker run -d --name vtlab-app --network vtlab-net \
  --cpus="$CPUS" --memory=1g \
  -e DB_HOST=vtlab-db -e LAB_MODEL="$MODEL" -e LAB_VIRTUAL="$VIRTUAL" \
  -e LAB_CONN="$CONN" -e LAB_SYNC="$SYNC" -e LAB_POOL=200 \
  -v "$(pwd -W)/app/target":/app \
  eclipse-temurin:21-jre \
  java -XX:+UseSerialGC -Xmx768m -Djdk.tracePinnedThreads=full $CARRIER_OPTS -jar /app/app.jar >/dev/null

# 준비 확인 — 실제 측정 경로가 200 을 돌려줄 때까지
READY=no
for i in $(seq 1 90); do
  if docker run --rm --network vtlab-net curlimages/curl:latest \
       -sf -o /dev/null "http://vtlab-app:8080${PATHQ}?seq=0" 2>/dev/null; then
    READY=yes; break
  fi
  sleep 2
done

if [ "$READY" != "yes" ]; then
  echo "$MODEL conc=$CONC conn=$CONN sync=$SYNC cpus=$CPUS path=$PATHQ SKIPPED app_not_ready"
  docker logs vtlab-app 2>&1 | tail -5 >&2
  docker rm -f vtlab-app >/dev/null 2>&1 || true
  exit 0
fi

OUT=$(docker run --rm --network vtlab-net -v "$(pwd -W)/loadgen":/lg -w /lg \
  eclipse-temurin:21-jdk java LoadGen.java http://vtlab-app:8080 "$CONC" "$WARM" "$RUN" "$PATHQ" 2>&1 \
  | grep '^RESULT' || echo "RESULT FAILED")

PIN=$(docker logs vtlab-app 2>&1 | grep -c "onPinned" || true)
echo "$MODEL conc=$CONC conn=$CONN sync=$SYNC cpus=$CPUS path=$PATHQ carrier=$CARRIER pinned=$PIN $OUT"
docker rm -f vtlab-app >/dev/null 2>&1 || true
