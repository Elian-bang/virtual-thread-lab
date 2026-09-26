package dev.elian.vt;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정에 필요한 값만 한 번에 뽑는다.
 *
 * <p>v2: 모든 값을 {@code /stat/reset} 이후 <b>구간값</b>으로 낸다.
 * v1 은 커넥션 대기가 앱 시작부터 누적이라 워밍업·준비 확인 요청이 섞였다.
 * CPU 시간과 GC 시간을 추가했다 — v1 은 "CPU 병목이 아니다" 를 재지 않고 추론했다.
 */
@RestController
public class StatController {

    private final MeterRegistry registry;
    private final Phases phases;
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private volatile long baseWallNanos = System.nanoTime();
    private volatile long baseCpuNanos;
    private volatile long baseGcCount;
    private volatile long baseGcMs;
    private volatile long baseAcqCount;
    private volatile double baseAcqTotalMs;

    public StatController(MeterRegistry registry, Phases phases) {
        this.registry = registry;
        this.phases = phases;
    }

    @GetMapping("/stat")
    public Map<String, Object> stat() {
        Map<String, Object> m = new LinkedHashMap<>();
        double wallMs = (System.nanoTime() - baseWallNanos) / 1e6;

        // "스레드를 늘리지 않았다" 의 근거 — 플랫폼 스레드만 센다 (캐리어·JVM 내부 포함)
        m.put("threadCount", threads.getThreadCount());
        m.put("peakThreadCount", threads.getPeakThreadCount());

        // CPU — 1 core 쿼터에서 CPU 가 포화였는지 직접 본다
        double cpuMs = (os.getProcessCpuTime() - baseCpuNanos) / 1e6;
        m.put("windowMs", round(wallMs));
        m.put("cpuMs", round(cpuMs));
        m.put("cpuUtil", round(cpuMs / Math.max(1, wallMs)));   // 1.0 = 코어 하나를 꽉 씀
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());

        // GC
        long[] gc = gc();
        m.put("gcCount", gc[0] - baseGcCount);
        m.put("gcMs", gc[1] - baseGcMs);

        // 커넥션 획득 — 구간값
        Timer acquire = registry.find("hikaricp.connections.acquire").timer();
        long acqCount = acquire == null ? 0 : acquire.count();
        double acqTotalMs = acquire == null ? 0 : acquire.totalTime(TimeUnit.MILLISECONDS);
        long dc = acqCount - baseAcqCount;
        m.put("connAcquireCount", dc);
        m.put("connAcquireMeanMs", round(dc == 0 ? 0 : (acqTotalMs - baseAcqTotalMs) / dc));
        m.put("connMax", gauge("hikaricp.connections.max"));

        m.putAll(phases.snapshot());
        return m;
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami() {
        Thread t = Thread.currentThread();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("virtual", t.isVirtual());
        m.put("name", t.getName());
        m.put("javaVersion", Runtime.version().toString());
        m.put("platformThreads", threads.getThreadCount());
        m.put("mysqlDriver", driverVersion());
        return m;
    }

    @GetMapping("/stat/reset")
    public String reset() {
        threads.resetPeakThreadCount();
        baseWallNanos = System.nanoTime();
        baseCpuNanos = os.getProcessCpuTime();
        long[] gc = gc();
        baseGcCount = gc[0];
        baseGcMs = gc[1];
        Timer acquire = registry.find("hikaricp.connections.acquire").timer();
        baseAcqCount = acquire == null ? 0 : acquire.count();
        baseAcqTotalMs = acquire == null ? 0 : acquire.totalTime(TimeUnit.MILLISECONDS);
        phases.reset();
        return "ok";
    }

    private static long[] gc() {
        long c = 0, ms = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, b.getCollectionCount());
            ms += Math.max(0, b.getCollectionTime());
        }
        return new long[]{c, ms};
    }

    private static String driverVersion() {
        try {
            return String.valueOf(Class.forName("com.mysql.cj.Constants").getField("CJ_VERSION").get(null));
        } catch (Exception e) {
            return "unknown";
        }
    }

    private double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? -1 : g.value();
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
