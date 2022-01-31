package io.micronaut.configuration.kafka.annotation

import io.micronaut.configuration.kafka.ConsumerRegistry
import io.micronaut.context.ApplicationContext
import io.micronaut.messaging.annotation.MessageBody
import io.micronaut.runtime.server.EmbeddedServer
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.testcontainers.containers.KafkaContainer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ConcurrentSkipListSet

import static io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST
import static io.micronaut.configuration.kafka.config.AbstractKafkaConfiguration.EMBEDDED_TOPICS

class ConsumerRegistrySpec extends Specification {

    @Shared
    @AutoCleanup
    KafkaContainer kafkaContainer = new KafkaContainer()

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer

    @Shared
    @AutoCleanup
    ApplicationContext context

    void setupSpec() {
        kafkaContainer.start()
        embeddedServer = ApplicationContext.run(EmbeddedServer,
                ['kafka.bootstrap.servers'    : kafkaContainer.bootstrapServers,
                 'micrometer.metrics.enabled' : true,
                 'endpoints.metrics.sensitive': false,
                 (EMBEDDED_TOPICS)            : ['fruits']])
        context = embeddedServer.applicationContext
    }

    void 'test consumer registry'() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 30, delay: 1)
        ConsumerRegistry registry = context.getBean(ConsumerRegistry)
        BicycleClient client = context.getBean(BicycleClient)
        BicycleListener listener = context.getBean(BicycleListener)

        when:
        Consumer consumer = registry.getConsumer('bicycle-client')

        then:
        consumer

        when:
        Set<String> consumerIds = registry.consumerIds

        then:
        consumerIds.contains 'bicycle-client'

        when:
        Set<String> subscription = registry.getConsumerSubscription('bicycle-client')

        then:
        subscription
        subscription.size() == 1
        subscription[0] == 'bicycles'

        when:
        client.send 'Raleigh', 'Professional'

        then:
        conditions.eventually {
            listener.bicycles.size() == 1
            listener.bicycles[0] == 'Professional'
        }

        when:
        Set<TopicPartition> topicPartitions = registry.getConsumerAssignment('bicycle-client')

        then:
        topicPartitions
        topicPartitions.size() == 1
        topicPartitions[0].topic() == 'bicycles'
        topicPartitions[0].partition() == 0
    }

    @KafkaClient
    static interface BicycleClient {
        @Topic('bicycles')
        void send(@KafkaKey String make, @MessageBody String model)
    }

    @KafkaListener(clientId = 'bicycle-client', offsetReset = EARLIEST)
    static class BicycleListener {

        Set<String> bicycles = new ConcurrentSkipListSet<>()

        @Topic('bicycles')
        void receive(@MessageBody String model) {
            bicycles << model
        }
    }
}
