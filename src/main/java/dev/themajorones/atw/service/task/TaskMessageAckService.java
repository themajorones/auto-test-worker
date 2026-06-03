package dev.themajorones.atw.service.task;

import java.io.IOException;

import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Service;

import com.rabbitmq.client.Channel;

@Service
public class TaskMessageAckService {

    public void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            throw new AmqpException("Unable to acknowledge task message", ex);
        }
    }

    public void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException ex) {
            throw new AmqpException("Unable to reject task message", ex);
        }
    }
}
