package dev.elian.vt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Knobs.class)
public class Config {

    /**
     * CF 모델 전용 풀. 다른 모델에서도 빈은 만들어지지만 쓰이지 않는다.
     * 크기를 플랫폼 풀과 같게 둬서, CF 의 차이가 "비동기라서"인지
     * "풀이 커서"인지 헷갈리지 않게 한다.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService cfPool(Knobs knobs) {
        return Executors.newFixedThreadPool(knobs.poolSize());
    }
}
