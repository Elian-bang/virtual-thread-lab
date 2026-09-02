#!/usr/bin/env bash
# 조건 하나를 돌린다.  사용: run.sh <MODEL> <CONC> <CONN> <SYNC> [WARMUP] [RUN]
set -e
MODEL=$1; CONC=$2; CONN=$3; SYNC=${4:-false}; WARM=${5:-15}; RUN=${6:-30}; PATHQ=${7:-/send}; CPUS=${8:-1}
VIRTUAL=false; [ "$MODEL" = "VIRTUAL" ] && VIRTUAL=true

docker rm -f vtlab-app >/dev/null 2>&1 || true
docker run -d --name vtlab-app --network vtlab-net \
  --cpus="$CPUS" --memory=1g \
  -e DB_HOST=vtlab-db -e LAB_MODEL="$MODEL" -e LAB_VIRTUAL="$VIRTUAL" \
  -e LAB_CONN="$CONN" -e LAB_SYNC="$SYNC" -e LAB_POOL=200 \
  -v "$(pwd -W)/app/target":/app \
  eclipse-temurin:21-jre \
  java -XX:+UseSerialGC -Xmx768m -Djdk.tracePinnedThreads=full -jar /app/app.jar >/dev/null

for i in $(seq 1 60); do
  if docker run --rm --network vtlab-net curlimages/curl:latest -sf http://vtlab-app:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done

OUT=$(MSYS_NO_PATHCONV=1 docker run --rm --network vtlab-net -v "$(pwd -W)/loadgen":/lg -w /lg \
  eclipse-temurin:21-jdk java LoadGen.java http://vtlab-app:8080 "$CONC" "$WARM" "$RUN" "$PATHQ" 2>&1 | grep '^RESULT' || echo "RESULT FAILED")

PIN=$(docker logs vtlab-app 2>&1 | grep -c "onPinned" || true)
echo "$MODEL conc=$CONC conn=$CONN sync=$SYNC cpus=$CPUS path=$PATHQ pinned=$PIN $OUT"
docker rm -f vtlab-app >/dev/null 2>&1 || true
