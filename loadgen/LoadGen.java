import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 부하 생성기 (v2).
 *
 * <p>closed-loop 이다. worker N 개가 요청을 하나씩, 쉬지 않고 보낸다. "동시 N" 은 이 N 이다.
 *
 * <p><b>v1 의 결함과 고친 것</b>
 * <ul>
 *   <li>v1 은 측정 종료 신호 뒤에 끝난 진행 중 요청까지 성공으로 세고 측정 시간으로 나눴다.
 *       지연이 긴 조건에서 TPS 가 부풀려졌다. v2 는 <b>측정창 [t0, t1) 안에서 완료된 요청만</b> 센다.
 *       창이 끝난 뒤 완료된 요청은 {@code drained} 로 따로 남긴다.</li>
 *   <li>지연 백분위는 창 안에서 끝난 요청 전체로 낸다 (창 안에서 시작한 수는 startedInWindow 로 따로). 지연이 창보다 긴 조건에서도 표본이 비지 않는다. p99.9 와 max 를 추가했다.</li>
 *   <li>창이 끝나면 진행 중 요청을 최대 5초만 기다리고 끝낸다. 결과에는 창 안의 요청만 쓴다.</li>
 *   <li>지표는 앱의 전용 서버(8081)에서 받는다. 앱이 포화돼도 수집이 막히지 않는다.</li>
 *   <li>초 단위 완료 수를 남겨, 창 안에서 처리량이 안정적이었는지(CV) 본다.</li>
 *   <li>앱 구간 지표 초기화와 수집을 창 경계에 맞춘다. 워밍업이 섞이지 않는다.</li>
 * </ul>
 *
 * <p>사용: {@code java LoadGen.java <url> <concurrency> <warmupSec> <measureSec> [path]}
 * <p>출력: 한 줄 {@code RESULT {json}}.
 */
public class LoadGen {

    static final AtomicReference<String> FIRST_ERR = new AtomicReference<>();
    static final AtomicReference<String> STAT = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        String base = args[0];
        int conc = Integer.parseInt(args[1]);
        int warmupSec = Integer.parseInt(args[2]);
        int runSec = Integer.parseInt(args[3]);
        String path = args.length > 4 ? args[4] : "/send";
        String statBase = base.replace(":8080", ":8081");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        long[] lat = new long[4_000_000];
        AtomicLong latN = new AtomicLong();
        AtomicLong okIn = new AtomicLong();
        AtomicLong errIn = new AtomicLong();
        AtomicLong drained = new AtomicLong();
        AtomicLong errAfter = new AtomicLong();
        AtomicLong startedIn = new AtomicLong();
        AtomicLongArray perSec = new AtomicLongArray(runSec);
        AtomicBoolean stop = new AtomicBoolean(false);

        long start = System.nanoTime();
        long t0 = start + TimeUnit.SECONDS.toNanos(warmupSec);
        long t1 = t0 + TimeUnit.SECONDS.toNanos(runSec);
        long resetAt;
        long t0EpochMs = System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(t0 - System.nanoTime());

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        {
            for (int i = 0; i < conc; i++) {
                final long seq0 = i * 1_000_000L;
                pool.submit(() -> {
                    long seq = seq0;
                    while (!stop.get()) {
                        long s = System.nanoTime();
                        boolean ok;
                        try {
                            HttpResponse<Void> res = http.send(
                                    HttpRequest.newBuilder(URI.create(base + path + "?seq=" + (seq++)))
                                            .timeout(Duration.ofSeconds(120)).GET().build(),
                                    HttpResponse.BodyHandlers.discarding());
                            ok = res.statusCode() == 200;
                            if (!ok) FIRST_ERR.compareAndSet(null, "HTTP " + res.statusCode());
                        } catch (Exception ex) {
                            ok = false;
                            FIRST_ERR.compareAndSet(null, ex.getClass().getName() + ": " + ex.getMessage());
                        }
                        long e = System.nanoTime();
                        if (e >= t0 && e < t1) {
                            if (ok) {
                                okIn.incrementAndGet();
                                int sec = (int) ((e - t0) / 1_000_000_000L);
                                if (sec >= 0 && sec < runSec) perSec.incrementAndGet(sec);
                                long idx = latN.getAndIncrement();
                                if (idx < lat.length) lat[(int) idx] = (e - s) / 1000;
                                if (s >= t0) startedIn.incrementAndGet();
                            } else {
                                errIn.incrementAndGet();
                            }
                        } else if (e >= t1) {
                            if (ok) drained.incrementAndGet();
                            else errAfter.incrementAndGet();
                        }
                        if (!ok) {
                            // 백오프 없이 재시도하면 느린 앱이 에러 폭풍으로 바뀐다
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException ie) {
                                return;
                            }
                        }
                    }
                });
            }
            sleepUntil(t0);
            resetAt = System.nanoTime();
            get(http, statBase + "/stat/reset");
            sleepUntil(t1);
            STAT.set(get(http, statBase + "/stat"));
            stop.set(true);
            // 결과에는 창 안의 요청만 쓴다. 진행 중 요청이 끝나기를 오래 기다리지 않는다 (최대 5초)
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        int n = (int) Math.min(latN.get(), lat.length);
        long[] sorted = Arrays.copyOf(lat, n);
        Arrays.sort(sorted);

        double mean = 0;
        for (int i = 0; i < runSec; i++) mean += perSec.get(i);
        mean /= runSec;
        double sq = 0;
        for (int i = 0; i < runSec; i++) sq += Math.pow(perSec.get(i) - mean, 2);
        double cv = mean == 0 ? -1 : Math.sqrt(sq / runSec) / mean;

        StringBuilder ps = new StringBuilder("[");
        for (int i = 0; i < runSec; i++) {
            if (i > 0) ps.append(',');
            ps.append(perSec.get(i));
        }
        ps.append(']');

        String fe = FIRST_ERR.get();
        String st = STAT.get();
        System.out.printf(Locale.ROOT,
                "RESULT {\"tps\":%.2f,\"ok\":%d,\"err\":%d,\"drained\":%d,\"errAfter\":%d,\"latSamples\":%d,"
                        + "\"p50\":%.2f,\"p95\":%.2f,\"p99\":%.2f,\"p999\":%.2f,\"max\":%.2f,"
                        + "\"perSecCv\":%.4f,\"perSec\":%s,\"resetLagMs\":%.1f,\"t0EpochMs\":%d,\"startedInWindow\":%d,\"firstErr\":%s,\"stat\":%s}%n",
                okIn.get() / (double) runSec, okIn.get(), errIn.get(), drained.get(), errAfter.get(), n,
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), pct(sorted, 99.9),
                n == 0 ? -1.0 : sorted[n - 1] / 1000.0,
                cv, ps, (resetAt - t0) / 1e6, t0EpochMs, startedIn.get(),
                fe == null ? "null" : quote(fe),
                st == null ? "null" : st);
        System.out.flush();
        System.exit(0);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "/").replace("\"", "'") + "\"";
    }

    private static void sleepUntil(long nanos) throws InterruptedException {
        long d = nanos - System.nanoTime();
        if (d > 0) TimeUnit.NANOSECONDS.sleep(d);
    }

    /** 백분위. us 로 모아 ms 로 돌려준다. */
    private static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return -1;
        int i = (int) Math.ceil(sorted.length * p / 100.0) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))] / 1000.0;
    }

    /** 앱이 포화되면 이 호출이 실패할 수 있다. 몇 번 다시 시도하고, 그래도 안 되면 null 을 남긴다. */
    private static String get(HttpClient http, String url) {
        for (int i = 0; i < 5; i++) {
            try {
                String b = http.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body().trim();
                return b.startsWith("{") ? b : quote(b);
            } catch (Exception e) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        return null;
    }
}
