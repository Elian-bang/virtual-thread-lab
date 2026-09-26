import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * JFR 기록에서 jdk.VirtualThreadPinned 이벤트를 요약한다.
 *
 * <p>v1 은 로그의 "onPinned" 줄 수를 셌다. 발생 횟수가 아니었고 원본도 지워졌다.
 * v2 는 이벤트마다 지속시간과 스택을 읽어, 횟수 · 총 pinned 시간 · 지속시간 분포 ·
 * 스택 서명별 상위 목록을 JSON 한 줄로 낸다.
 *
 * <p>스택 서명 = "java.* / jdk.* / sun.* 가 아닌 첫 프레임" 을 포함한 상위 프레임.
 * pinning 을 일으킨 쪽(드라이버 · 앱 · 라이브러리)이 어디인지 보려는 것이다.
 *
 * <p>사용: {@code java PinnedSummary.java <rec.jfr> [fromEpochMs] [toEpochMs]}
 * 측정창 [from, to) 안에서 시작한 이벤트만 센다 — 워밍업과 종료 과정을 뺀다.
 */
public class PinnedSummary {
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        long fromMs = args.length > 1 ? Long.parseLong(args[1]) : 0;
        long toMs = args.length > 2 ? Long.parseLong(args[2]) : Long.MAX_VALUE;

        long count = 0;
        double totalMs = 0;
        List<Double> durations = new ArrayList<>();
        Map<String, long[]> bySig = new HashMap<>();     // [count, totalMicros]
        Map<String, String> reasons = new HashMap<>();
        long firstAll = Long.MAX_VALUE, lastAll = Long.MIN_VALUE, allCount = 0;

        for (RecordedEvent e : RecordingFile.readAllEvents(p)) {
            if (!e.getEventType().getName().equals("jdk.VirtualThreadPinned")) continue;
            long at = e.getStartTime().toEpochMilli();
            allCount++;
            firstAll = Math.min(firstAll, at);
            lastAll = Math.max(lastAll, at);
            if (at < fromMs || at >= toMs) continue;
            double ms = e.getDuration().toNanos() / 1e6;
            count++;
            totalMs += ms;
            durations.add(ms);

            String sig = signature(e);
            long[] a = bySig.computeIfAbsent(sig, k -> new long[2]);
            a[0]++;
            a[1] += (long) (ms * 1000);

            // JDK 24+ 이벤트는 pinning 이유를 필드로 가진다
            if (e.hasField("pinnedReason")) {
                reasons.merge(String.valueOf(e.getValue("pinnedReason")), "1", (x, y) -> String.valueOf(Long.parseLong(x) + 1));
            }
        }

        durations.sort(Double::compare);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "{\"fromMs\":%d,\"toMs\":%d,\"allEvents\":%d,\"firstEventMs\":%d,\"lastEventMs\":%d,\"pinnedCount\":%d,\"pinnedTotalMs\":%.1f,\"pinnedP50Ms\":%.3f,\"pinnedP99Ms\":%.3f,\"pinnedMaxMs\":%.3f,",
                fromMs, toMs == Long.MAX_VALUE ? -1 : toMs, allCount, allCount == 0 ? -1 : firstAll, allCount == 0 ? -1 : lastAll, count, totalMs, pct(durations, 50), pct(durations, 99), durations.isEmpty() ? 0 : durations.get(durations.size() - 1)));
        sb.append("\"reasons\":{");
        int i = 0;
        for (var r : reasons.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(esc(r.getKey())).append("\":").append(r.getValue());
        }
        sb.append("},\"topStacks\":[");
        List<Map.Entry<String, long[]>> top = new ArrayList<>(bySig.entrySet());
        top.sort(Comparator.comparingLong((Map.Entry<String, long[]> x) -> x.getValue()[1]).reversed());
        for (int k = 0; k < Math.min(5, top.size()); k++) {
            if (k > 0) sb.append(',');
            var t = top.get(k);
            sb.append(String.format(Locale.ROOT, "{\"count\":%d,\"totalMs\":%.1f,\"stack\":\"%s\"}",
                    t.getValue()[0], t.getValue()[1] / 1000.0, esc(t.getKey())));
        }
        sb.append("]}");
        System.out.println(sb);
    }

    /** 첫 비-JDK 프레임까지 + 그 뒤 3개. 없으면 상위 8개. */
    static String signature(RecordedEvent e) {
        if (e.getStackTrace() == null) return "(no stack)";
        List<RecordedFrame> frames = e.getStackTrace().getFrames();
        List<String> names = new ArrayList<>();
        int firstApp = -1;
        for (int i = 0; i < frames.size(); i++) {
            RecordedFrame f = frames.get(i);
            String cls = f.getMethod().getType().getName();
            names.add(cls + "." + f.getMethod().getName());
            if (firstApp < 0 && !(cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun."))) {
                firstApp = i;
            }
        }
        int end = firstApp < 0 ? Math.min(8, names.size()) : Math.min(names.size(), firstApp + 4);
        int begin = firstApp < 0 ? 0 : Math.max(0, firstApp - 2);
        return String.join(" <- ", names.subList(begin, end));
    }

    static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    static String esc(String s) {
        return s.replace("\\", "/").replace("\"", "'");
    }
}
