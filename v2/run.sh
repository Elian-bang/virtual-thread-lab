#!/usr/bin/env bash
# v2 — 조건 하나를 실행하고, 원자료를 전부 파일로 남긴다.
#
#   run.sh <RUN_ID> <MODEL> <PATH> <CONC> <CONN> <SYNC> <JDK> <CJ> <CARRIER> <WARM> <MEAS> <JFR>
#
#   MODEL   PLATFORM | CF | VIRTUAL
#   PATH    /sleep | /send
#   SYNC    NONE | GLOBAL_ALL | GLOBAL_JDBC
#   JDK     21 | 25
#   CJ      8.3.0 | 9.7.0        (dist/app-cj<CJ>.jar)
#   CARRIER 0 = JVM 기본, N = parallelism 과 maxPoolSize 를 N 으로 고정
#   JFR     1 = jdk.VirtualThreadPinned 를 threshold 0 으로 기록
#
# 남기는 파일 (results/v2/runs/<RUN_ID>/)
#   meta.env      조건 · 이미지 다이제스트 · 시각
#   whoami.json   앱이 스스로 보고한 모델 · Java 버전 · 드라이버 버전
#   loadgen.out   부하 생성기 RESULT 한 줄 (JSON)
#   cpu_before / cpu_after     앱 컨테이너 cgroup cpu.stat
#   db_before / db_after       MySQL GLOBAL STATUS 일부
#   others.txt    같은 시각 다른 컨테이너 CPU 사용 (잡음 기록)
#   gc.log  app.log  rec.jfr  pinned.txt  state.txt
set -u
export MSYS_NO_PATHCONV=1

RUN_ID=$1; MODEL=$2; PATHQ=$3; CONC=$4; CONN=$5; SYNC=$6; JDK=$7; CJ=$8; CARRIER=$9
WARM=${10}; MEAS=${11}; JFR=${12}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ROOTW=$(cd "$ROOT" && pwd -W)
OUT="$ROOT/results/v2/runs/$RUN_ID"
OUTW="$ROOTW/results/v2/runs/$RUN_ID"
mkdir -p "$OUT"

NAME="vtlab-app-$RUN_ID"
VIRTUAL=false; [ "$MODEL" = "VIRTUAL" ] && VIRTUAL=true
JAR="app-cj$CJ.jar"
[ -f "$ROOT/dist/$JAR" ] || { echo "no_jar" > "$OUT/state.txt"; exit 0; }

JOPTS="-XX:+UseSerialGC -Xmx512m -Xss512k -Xlog:gc:file=/out/gc.log"
if [ "$CARRIER" != "0" ]; then
  JOPTS="$JOPTS -Djdk.virtualThreadScheduler.parallelism=$CARRIER -Djdk.virtualThreadScheduler.maxPoolSize=$CARRIER"
fi
if [ "$JFR" = "1" ]; then
  JOPTS="$JOPTS -XX:StartFlightRecording=filename=/out/rec.jfr,jdk.VirtualThreadPinned#enabled=true,jdk.VirtualThreadPinned#threshold=0ms,jdk.VirtualThreadPinned#stackTrace=true"
fi

APPIMG="eclipse-temurin:$JDK-jre"
{
  echo "run_id=$RUN_ID"; echo "model=$MODEL"; echo "path=$PATHQ"; echo "conc=$CONC"; echo "conn=$CONN"
  echo "sync=$SYNC"; echo "jdk=$JDK"; echo "cj=$CJ"; echo "carrier=$CARRIER"; echo "warm=$WARM"; echo "meas=$MEAS"; echo "jfr=$JFR"
  echo "app_image=$(docker image inspect -f '{{index .RepoDigests 0}}' "$APPIMG" 2>/dev/null)"
  echo "loadgen_image=$(docker image inspect -f '{{index .RepoDigests 0}}' eclipse-temurin:21-jdk 2>/dev/null)"
  echo "db_image=$(docker inspect -f '{{.Image}}' vtlab-db 2>/dev/null)"
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jvm_opts=$JOPTS"
} > "$OUT/meta.env"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" --network vtlab-net --cpus=1 --memory=1g \
  -e DB_HOST=vtlab-db -e LAB_MODEL="$MODEL" -e LAB_VIRTUAL="$VIRTUAL" \
  -e LAB_CONN="$CONN" -e LAB_SYNC="$SYNC" -e LAB_POOL=200 \
  -v "$ROOTW/dist":/app:ro -v "$OUTW":/out \
  "$APPIMG" java $JOPTS -jar "/app/$JAR" >/dev/null 2>"$OUT/docker_run.err" \
  || { echo "start_failed" > "$OUT/state.txt"; exit 0; }

IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME" 2>/dev/null)
[ -n "$IP" ] || { echo "no_ip" > "$OUT/state.txt"; exit 0; }
BASE="http://$IP:8080"

READY=no
for i in $(seq 1 90); do
  if docker run --rm --network vtlab-net curlimages/curl:latest -sf --max-time 5 "$BASE/whoami" > "$OUT/whoami.json" 2>/dev/null; then
    READY=yes; break
  fi
  sleep 2
done
[ "$READY" = "yes" ] || { docker logs "$NAME" > "$OUT/app.log" 2>&1; echo "not_ready" > "$OUT/state.txt"; exit 0; }

CG() { docker exec "$NAME" cat /sys/fs/cgroup/cpu.stat 2>/dev/null; }
DBS() {
  docker exec vtlab-db mysql -uroot -plabpass -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_select','Com_update','Innodb_data_fsyncs','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Threads_connected','Innodb_buffer_pool_wait_free')" 2>/dev/null
}

CG > "$OUT/cpu_before"; DBS > "$OUT/db_before"
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' > "$OUT/others_before.txt" 2>/dev/null

docker run --rm --network vtlab-net --cpus=4 -v "$ROOTW/loadgen":/lg -w /lg \
  eclipse-temurin:21-jdk java LoadGen.java "$BASE" "$CONC" "$WARM" "$MEAS" "$PATHQ" 2>"$OUT/loadgen.err" \
  | grep '^RESULT' > "$OUT/loadgen.out"

CG > "$OUT/cpu_after"; DBS > "$OUT/db_after"
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' > "$OUT/others_after.txt" 2>/dev/null

ALIVE=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo false)
OOM=$(docker inspect -f '{{.State.OOMKilled}}' "$NAME" 2>/dev/null || echo unknown)
EXITC=$(docker inspect -f '{{.State.ExitCode}}' "$NAME" 2>/dev/null || echo unknown)

# 정상 종료로 JFR 을 파일에 쓰게 한다
docker stop -t 30 "$NAME" >/dev/null 2>&1
docker logs "$NAME" > "$OUT/app.log" 2>&1

if [ "$JFR" = "1" ] && [ -s "$OUT/rec.jfr" ]; then
  # 측정창 시작 이후 이벤트만 요약한다. 원본 JFR 은 KEEP_JFR=1 일 때만 남긴다 (실행당 MB 단위)
  # python 은 Windows 프로그램이라 /c/... 경로를 못 연다 — Windows 경로를 넘긴다.
  # 읽지 못하면 0 으로 두지 않는다 (v2 시험 실행에서 0 이 들어가 창 필터가 통째로 꺼졌다)
  T0=$(python "$ROOTW/v2/field.py" "$OUTW/loadgen.out" t0EpochMs 2>"$OUT/field.err")
  case "$T0" in
    ''|*[!0-9]*)
      echo '{"error":"t0EpochMs unreadable"}' > "$OUT/pinned.json" ;;
    *)
      docker run --rm -v "$ROOTW/v2":/v2:ro -v "$OUTW":/out eclipse-temurin:25-jdk \
        java /v2/PinnedSummary.java /out/rec.jfr "$T0" "$((T0 + MEAS * 1000))" > "$OUT/pinned.json" 2>"$OUT/pinned.err" ;;
  esac
  [ "${KEEP_JFR:-0}" = "1" ] || rm -f "$OUT/rec.jfr"
fi

if [ "$ALIVE" != "true" ]; then
  echo "died oom=$OOM exit=$EXITC" > "$OUT/state.txt"
elif [ ! -s "$OUT/loadgen.out" ]; then
  echo "no_result" > "$OUT/state.txt"
else
  echo "ok" > "$OUT/state.txt"
fi
echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/meta.env"
cat "$OUT/state.txt"
