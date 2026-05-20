package dev.themajorones.android_test_worker.service.task;

import java.util.List;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import dev.themajorones.models.constants.RabbitMqConstant;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class TaskCommandListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskCommandListener.class);

    private final List<TaskHandler> handlers;
    private final TaskMessageAckService taskMessageAckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = RabbitMqConstant.Queue.Android.NAME, ackMode = "MANUAL")
    public void listen(
        String message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        try {
            TaskCommandEnvelope command = objectMapper.readValue(message, TaskCommandEnvelope.class);
            TaskHandler handler = handlers.stream()
                .filter(candidate -> candidate.supports(command.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No handler for task type " + command.getType()));
            handler.handle(command);
            taskMessageAckService.ack(channel, deliveryTag);
        } catch (Exception ex) {
            LOGGER.error("Failed to process task command: {}", message, ex);
            taskMessageAckService.nack(channel, deliveryTag, false);
        }
    }
}
