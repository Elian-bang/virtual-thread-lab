#!/usr/bin/env bash
# conditions.txt 의 <from>~<to> 줄만 돌린다.
# 백그라운드 프로세스를 못 믿어서, 포그라운드에서 조각으로 나눠 돌린다.
# 스윕이 두 개 겹치면 값이 전부 못 쓰게 되는데, 겹쳤는지 알아채기가 어렵다.
set -u
FROM=$1; TO=$2

LEFT=$(docker ps -aq --filter "name=vtlab-app" | wc -l)
if [ "$LEFT" -ne 0 ]; then echo "vtlab-app 컨테이너가 $LEFT 개 남아있다. 중단." >&2; exit 1; fi

sed -n "${FROM},${TO}p" conditions.txt | while read -r M C P S PQ WK BLK REP; do
  LINE=$(LAB_CARRIER=$WK ./run.sh "$M" "$C" "$P" "$S" 15 20 "$PQ" 1)
  echo "$LINE rep=$REP block=$BLK" | tee -a results/raw.tsv
done
