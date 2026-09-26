package dev.elian.vt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * 측정 지표 전용 서버 (포트 8081).
 *
 * <p>v2 시험 실행에서 앱이 포화되면 {@code /stat} 수집이 실패했다. {@code /stat} 도 같은 Tomcat
 * 요청 스레드를 쓰는데, 전역 락 조건에서 그 스레드 200개가 모두 막혀 있었다.
 * 지표 수집이 측정 대상과 같은 자원을 두고 다투면 안 되므로, 전용 플랫폼 스레드 하나로 따로 띄운다.
 */
@Component
public class StatServer {

    private final StatController stats;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    public StatServer(StatController stats) {
        this.stats = stats;
    }

    @PostConstruct
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(8081), 16);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stat-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/stat/reset", ex -> write(ex, "\"" + stats.reset() + "\""));
        server.createContext("/stat", ex -> write(ex, mapper.writeValueAsString(stats.stat())));
        server.start();
    }

    @PreDestroy
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private static void write(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
