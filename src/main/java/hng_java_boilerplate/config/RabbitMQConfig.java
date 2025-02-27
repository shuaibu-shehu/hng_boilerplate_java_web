package hng_java_boilerplate.config;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.queue.email}")
    private String emailQueueName;

    @Value("${rabbitmq.queue.concat}")
    private String videoConcat;

    @Value("${rabbitmq.queue.finishedConcat}")
    private String finishedConcatJob;


    @Bean
    public Queue emailQueue() {
        return new Queue(emailQueueName, true);
    }

    @Bean
    public Queue videoConcatQueue(){return new Queue(videoConcat, true);}

    @Bean
    public Queue finishedConcatQueue(){return new Queue(finishedConcatJob, true);}
}
