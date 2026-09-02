package dev.elian.vt;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * <p>부하 생성기가 조건 끝날 때마다 한 번 호출한다.
 * 여러 엔드포인트를 긁으면 시점이 어긋나서 값이 서로 안 맞는다.
 */
@RestController
public class StatController {

    private final MeterRegistry registry;
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    public StatController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/stat")
    public Map<String, Object> stat() {
        Map<String, Object> m = new LinkedHashMap<>();

        // "스레드를 늘리지 않았다" 는 주장의 근거
        m.put("threadCount", threads.getThreadCount());
        m.put("peakThreadCount", threads.getPeakThreadCount());

        // H2 의 핵심 지표 — 커넥션을 얻으려고 기다린 시간
        Timer acquire = registry.find("hikaricp.connections.acquire").timer();
        m.put("connAcquireCount", acquire == null ? 0 : acquire.count());
        m.put("connAcquireMeanMs", acquire == null ? 0.0 : acquire.mean(TimeUnit.MILLISECONDS));
        m.put("connAcquireMaxMs", acquire == null ? 0.0 : acquire.max(TimeUnit.MILLISECONDS));

        // 커넥션이 실제로 모자랐는지
        m.put("connPending", gauge("hikaricp.connections.pending"));
        m.put("connActive", gauge("hikaricp.connections.active"));
        m.put("connMax", gauge("hikaricp.connections.max"));
        return m;
    }

    /**
     * 요청을 실제로 무엇이 처리하는지 확인한다.
     *
     * <p>모델 전환이 됐다고 <b>믿고</b> 재면 안 된다. 스레드 이름을 직접 본다.
     * 가상 스레드는 이름이 없고 {@code isVirtual()} 이 true 다.
     */
    @GetMapping("/whoami")
    public Map<String, Object> whoami() {
        Thread t = Thread.currentThread();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("virtual", t.isVirtual());
        m.put("name", t.getName());
        m.put("platformThreads", threads.getThreadCount());
        return m;
    }

    @GetMapping("/stat/reset")
    public String reset() {
        threads.resetPeakThreadCount();
        return "ok";
    }

    private double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? -1 : g.value();
    }
}
