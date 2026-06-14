package dev.themajorones.atw.config;

import java.time.Duration;
import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import dev.themajorones.models.client.AdbClient;
import dev.themajorones.models.client.DockerClient;

@Configuration
public class ConnectionClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    public DockerClient dockerClient(RestClient.Builder restClientBuilder) {
        return new DockerClient(restClientBuilder);
    }

    @Bean
    public AdbClient adbClient() {
        return new AdbClient();
    }
}
