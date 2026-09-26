package dev.elian.vt;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세 모델을 한 엔드포인트 뒤에 둔다.
 *
 * <p>모델을 바꿔도 <b>URL·요청·작업이 전부 같다.</b>
 * 그래야 측정된 차이가 모델 차이라고 말할 수 있다.
 */
@RestController
public class SendController {

    private final SendService service;
    private final Knobs knobs;
    private final ExecutorService cfPool;
    private final Channel channel;
    private final Phases phases;
    private final int targets;

    public SendController(SendService service, Knobs knobs, ExecutorService cfPool, Channel channel, Phases phases,
                          @org.springframework.beans.factory.annotation.Value("${lab.targets:1000}") int targets) {
        this.service = service;
        this.knobs = knobs;
        this.cfPool = cfPool;
        this.channel = channel;
        this.phases = phases;
        this.targets = targets;
    }

    @GetMapping("/send")
    public ResponseEntity<String> send(@RequestParam(defaultValue = "0") long seq) {
        long id = (seq % targets) + 1;
        long t0 = System.nanoTime();

        if (knobs.model() == Model.CF) {
            // 비동기지만 결국 같은 플랫폼 풀 위에서 돈다.
            // join 으로 기다리므로 요청 스레드도 함께 묶인다 — 그게 이 모델의 실체다.
            CompletableFuture.runAsync(() -> service.send(id), cfPool).join();
        } else {
            // PLATFORM 과 VIRTUAL 은 Tomcat 이 무엇으로 요청을 받느냐만 다르다.
            service.send(id);
        }
        phases.total(System.nanoTime() - t0);
        return ResponseEntity.ok("ok");
    }

    /**
     * <b>대조군 — DB 를 건드리지 않는다.</b>
     *
     * <p>{@code /send} 는 JDBC 를 쓴다. 그런데 1 core 컨테이너에서는 가상 스레드의
     * 캐리어가 <b>1개</b>뿐이라, 드라이버가 캐리어를 pin 하면 DB 작업 전체가 직렬화된다.
     * 그러면 잰 것이 "가상 스레드의 성능"이 아니라 "드라이버의 pinning" 이 된다.
     *
     * <p>이 엔드포인트는 순수 I/O 대기만 한다. 여기서 모델 간 차이가 나면 그건
     * <b>가상 스레드 자체의 차이</b>다. {@code /send} 와의 격차가 드라이버 몫이다.
     */
    @GetMapping("/sleep")
    public ResponseEntity<String> sleep() {
        long t0 = System.nanoTime();
        channel.send(knobs.channelMs());
        long n = System.nanoTime() - t0;
        phases.channel(n);
        phases.total(n);
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/knobs")
    public Knobs knobs() {
        return knobs;
    }
}
