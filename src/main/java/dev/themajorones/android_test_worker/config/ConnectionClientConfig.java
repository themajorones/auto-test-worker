package dev.themajorones.android_test_worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import dev.themajorones.models.client.AdbClient;
import dev.themajorones.models.client.DockerClient;

@Configuration
public class ConnectionClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
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
