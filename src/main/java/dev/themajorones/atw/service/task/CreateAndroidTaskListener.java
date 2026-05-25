package dev.themajorones.atw.service.task;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import dev.themajorones.atw.service.handler.CreateAndroidTaskHandler;
import dev.themajorones.models.constants.RabbitMqConstant;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CreateAndroidTaskListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAndroidTaskListener.class);

    private final CreateAndroidTaskHandler taskHandler;
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
            taskHandler.handle(command);
            taskMessageAckService.ack(channel, deliveryTag);
        } catch (Exception ex) {
            LOGGER.error("Failed to process create Android task command: {}", message, ex);
            taskMessageAckService.nack(channel, deliveryTag, false);
        }
    }
}
