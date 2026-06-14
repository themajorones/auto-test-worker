package dev.themajorones.atw.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.themajorones.models.queue.RabbitMqTopology;

@Configuration
@EnableRabbit
public class RabbitMqConfig {

    @Bean
    public DirectExchange directExchange() {
        return RabbitMqTopology.directExchange();
    }

    @Bean
    public Queue createAndroidQueue() {
        return RabbitMqTopology.androidQueue();
    }

    @Bean
    public Binding createAndroidBinding(Queue createAndroidQueue, DirectExchange directExchange) {
        return RabbitMqTopology.androidBinding(createAndroidQueue, directExchange);
    }

    @Bean
    public Queue installApkQueue() {
        return RabbitMqTopology.artifactQueue();
    }

    @Bean
    public Binding installApkBinding(Queue installApkQueue, DirectExchange directExchange) {
        return RabbitMqTopology.artifactBinding(installApkQueue, directExchange);
    }
}
