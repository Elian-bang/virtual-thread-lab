import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 부하 생성기.
 *
 * <p>앱 컨테이너는 1 CPU 로 묶여 있고 이 생성기는 밖에서 돈다.
 * 같은 자원을 두고 다투면 잰 값이 앱 성능인지 생성기 한계인지 알 수 없다.
 *
 * <p>가상 스레드로 동시 요청을 만든다. 생성기 쪽 스레드가 병목이 되면 안 되기 때문이다.
 * <b>측정 대상이 아니라 측정 도구다.</b>
 *
 * <p>사용: java LoadGen.java <url> <concurrency> <warmupSec> <measureSec> [path]
 */
public class LoadGen {

    public static void main(String[] args) throws Exception {
        String base   = args[0];
        int conc      = Integer.parseInt(args[1]);
        int warmupSec = Integer.parseInt(args[2]);
        int runSec    = Integer.parseInt(args[3]);
        String path   = args.length > 4 ? args[4] : "/send";

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // 워밍업 — JIT 와 커넥션 풀이 데워지기 전 값은 버린다
        run(http, base, path, conc, warmupSec, null);
        get(http, base + "/stat/reset");

        // 본 측정
        long[] lat = new long[4_000_000];
        Result r = run(http, base, path, conc, runSec, lat);

        long[] sorted = Arrays.copyOf(lat, (int) Math.min(r.ok, lat.length));
        Arrays.sort(sorted);

        System.out.printf(
            "RESULT tps=%.1f ok=%d err=%d p50=%.1f p95=%.1f p99=%.1f stat=%s%n",
            r.ok / (double) runSec, r.ok, r.err,
            pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
            get(http, base + "/stat"));
        if (r.err > 0) System.out.println("FIRSTERR " + FIRST_ERR.get());
    }

    static final java.util.concurrent.atomic.AtomicReference<String> FIRST_ERR =
            new java.util.concurrent.atomic.AtomicReference<>();

    record Result(long ok, long err) {}

    private static Result run(HttpClient http, String base, String path, int conc, int seconds, long[] lat)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong ok = new AtomicLong(), err = new AtomicLong(), slot = new AtomicLong();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < conc; i++) {
                final int worker = i;
                pool.submit(() -> {
                    long seq = worker * 1_000_000L;
                    while (!stop.get()) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> res = http.send(
                                    HttpRequest.newBuilder(URI.create(base + path + "?seq=" + (seq++)))
                                            .timeout(Duration.ofSeconds(60)).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            if (res.statusCode() == 200) {
                                long us = (System.nanoTime() - t0) / 1000;
                                long idx = slot.getAndIncrement();
                                if (lat != null && idx < lat.length) lat[(int) idx] = us;
                                ok.incrementAndGet();
                            } else {
                                err.incrementAndGet();
                            }
                        } catch (Exception e) {
                            err.incrementAndGet();
                            FIRST_ERR.compareAndSet(null, e.getClass().getName() + ": " + e.getMessage());
                            // 백오프 없이 재시도하면 느린 앱이 에러 폭풍으로 바뀐다.
                            // 그러면 재는 것이 처리량이 아니라 재시도 속도가 된다.
                            try { Thread.sleep(20); } catch (InterruptedException ie) { break; }
                        }
                    }
                });
            }
            TimeUnit.SECONDS.sleep(seconds);
            stop.set(true);
        }
        return new Result(ok.get(), err.get());
    }

    /** 백분위. us 로 모아 ms 로 돌려준다. */
    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return -1;
        int i = (int) Math.ceil(sorted.length * p / 100.0) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))] / 1000.0;
    }

    /**
     * 앱이 1 core 에서 포화되면 이 호출 자체가 실패한다.
     * 부하가 멎은 뒤 몇 번 다시 시도하고, 그래도 안 되면 빈 값을 남긴다.
     * <b>실패를 성공처럼 기록하지 않는 것이 요점이다.</b>
     */
    private static String get(HttpClient http, String url) {
        for (int i = 0; i < 5; i++) {
            try {
                return http.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body().replaceAll("\s+", "");
            } catch (Exception e) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        return "UNAVAILABLE";
    }
}
