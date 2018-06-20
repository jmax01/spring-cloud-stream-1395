package org.springframework.cloud.stream;

import static java.util.stream.Collectors.*;
import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.MinimalKafkaCloudStreamTest.TestConfig;
import org.springframework.cloud.stream.MinimalKafkaCloudStreamTest.TestSpringBootApplicationConfig;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.annotation.Publisher;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;

@SuppressWarnings("javadoc")
// @formatter:off
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                classes = { TestSpringBootApplicationConfig.class,TestConfig.class},
                properties={
                "spring.main.banner-mode=off"
                ,"spring.autoconfigure.exclude=org.springframework.cloud.stream.test.binder.TestSupportBinderAutoConfiguration"
                ,"spring.cloud.stream.kafka.binder.autoCreateTopics=false"
                ,"spring.cloud.stream.bindings.testPublisherTarget.binder=kafka"
                ,"spring.cloud.stream.bindings.testPublisherTarget.destination=input"
                ,"spring.cloud.stream.bindings.testPublisherTarget.producer.headerMode=raw"
                ,"spring.cloud.stream.bindings.testPublisherTarget.contentType=text/plain"
                ,"spring.cloud.stream.bindings.input.binder=kafka"
                ,"spring.cloud.stream.bindings.input.destination=input"
                ,"spring.cloud.stream.bindings.input.consumer.headerMode=raw"
                ,"spring.cloud.stream.bindings.input.contentType=text/plain"
                ,"spring.cloud.stream.bindings.output.binder=kafka"
                ,"spring.cloud.stream.bindings.output.destination=output"
                ,"spring.cloud.stream.bindings.output.consumer.headerMode=raw"
                ,"spring.cloud.stream.bindings.output.contentType=text/plain"
                })
//@formatter:on
public class MinimalKafkaCloudStreamTest {

    /** The Constant SPRING_KAFKA_BOOTSTRAP_SERVERS_KEY. */
    public static final String SPRING_KAFKA_BOOTSTRAP_SERVERS_KEY = "spring.kafka.bootstrap-servers";

    /** The Constant SPRING_CLOUD_STREAM_KAFKA_BINDERS_ZKNODES_KEY. */
    public static final String SPRING_CLOUD_STREAM_KAFKA_BINDERS_ZKNODES_KEY = "spring.cloud.stream.kafka.binder.zkNodes";

    /** The Constant SPRING_CLASS_RULE. */
    @ClassRule
    public static final SpringClassRule SPRING_CLASS_RULE = new SpringClassRule();

    /** The spring method rule. */
    @Rule
    public final SpringMethodRule springMethodRule = new SpringMethodRule();

    @ClassRule
    public static final KafkaEmbedded KAFKA_EMBEDDED = new KafkaEmbedded(1, false, 1, "input", "output");

    @BeforeClass
    public static void populateSystemProperties() {

        System.setProperty(SPRING_KAFKA_BOOTSTRAP_SERVERS_KEY, KAFKA_EMBEDDED.getBrokersAsString());

        System.setProperty(SPRING_CLOUD_STREAM_KAFKA_BINDERS_ZKNODES_KEY,
                KAFKA_EMBEDDED.getZookeeperConnectionString());

    }

    @Autowired
    TestPublisher testPublisher;

    @Autowired
    TestStreamListenerConsumer testStreamListenerConsumer;

    @Autowired
    RetryTemplate retryTemplate;

    @Test
    public void test() throws InterruptedException {

        // wait for kafka
        TimeUnit.MILLISECONDS.sleep(1000);

        this.testPublisher.publish("PayloadSentToTestPublisher", "HeaderValueSentToTestPublisher");

        final String expectedValue = "PayloadSentToTestPublisher.[TestPublisher.HeaderArg[HeaderValueSentToTestPublisher]].[TestStreamListenerSendToProcessor.messageHeaders[HeaderValueSentToTestPublisher]]";

        this.retryTemplate.execute(rc -> {

            final String actual = this.testStreamListenerConsumer.getMessageReceived()
                .get();

            if (actual != null) {

                System.out.println("test.retryCallback#actual: " + actual);

                // org.junit.ComparisonFailure: expected:<...ssor.messageHeaders[[HeaderValueSentToTestPublisher]]]> but
                // was:<...ssor.messageHeaders[[null]]]>
                assertEquals(expectedValue, actual);

                return true;
            }
            throw new IllegalStateException();
        });
    }

    @SpringBootApplication
    @Configuration
    static class TestSpringBootApplicationConfig {}

    @TestConfiguration
    @EnableBinding({ Processor.class, TestPublisherTargetBinding.class })
    @Import(KafkaBinderConfiguration.class)
    static class TestConfig {

        @Bean
        TestStreamListenerSendToProcessor testStreamListenerSendToProcessor() {
            return new TestStreamListenerSendToProcessor();
        }

        @Bean
        TestStreamListenerConsumer testStreamListenerConsumer() {
            return new TestStreamListenerConsumer();
        }

        @Bean
        TestPublisher testPublisher() {
            return new TestPublisher();
        }

        @Bean
        RetryTemplate retryTemplate() {

            final RetryTemplate retryTemplate = new RetryTemplate();

            final long timeoutInMilliseconds = 10000;

            final ExponentialRandomBackOffPolicy backOffPolicy = new ExponentialRandomBackOffPolicy();
            retryTemplate.setBackOffPolicy(backOffPolicy);

            final TimeoutRetryPolicy timeoutRetryPolicy = new TimeoutRetryPolicy();
            timeoutRetryPolicy.setTimeout(timeoutInMilliseconds);
            retryTemplate.setRetryPolicy(timeoutRetryPolicy);

            return retryTemplate;
        }

    }

    static class TestStreamListenerSendToProcessor {

        @StreamListener(Sink.INPUT)
        @SendTo(Source.OUTPUT)
        String process(String input, MessageHeaders messageHeaders) {

            System.out.println("\nTestStreamListenerSendToProcessor.send#input: " + input);
            // headerToAppendToPayload header is missing!
            System.out.println("TestStreamListenerSendToProcessor.send#MessageHeaders: " + messageHeaders.entrySet()
                .stream()
                .map(Object::toString)
                .collect(joining("\n\t", "\n\t", "\n")));

            final String headerToAppendToPayload = messageHeaders.get("headerToAppendToPayload", String.class);

            return input + ".[TestStreamListenerSendToProcessor.messageHeaders[" + headerToAppendToPayload + "]]";
        }
    }

    static interface TestPublisherTargetBinding {

        String TEST_PUBLISHER_TARGET_BINDING_NAME = "testPublisherTarget";

        @Output(TEST_PUBLISHER_TARGET_BINDING_NAME)
        MessageChannel output();

    }

    static class TestPublisher {

        @Publisher(channel = TestPublisherTargetBinding.TEST_PUBLISHER_TARGET_BINDING_NAME)
        @Payload("#return+'.[TestPublisher.HeaderArg['+#args.headerToAppendToPayload+']]'")
        public String publish(String payloadToPublish, @Header String headerToAppendToPayload) {

            System.out.println("TestPublisher.publish#payloadToPublish: " + payloadToPublish);
            System.out.println("TestPublisher.publish#headerToAppendToPayload: " + headerToAppendToPayload);
            return payloadToPublish;
        }
    }

    static class TestStreamListenerConsumer {

        final AtomicReference<String> messageReceived = new AtomicReference<>();

        @StreamListener(Source.OUTPUT)
        void listen(String fromOutput) throws Exception {

            System.out.println("TestStreamListenerConsumer.listen#fromOutput: " + fromOutput);

            this.messageReceived.compareAndSet(null, fromOutput);

        }

        AtomicReference<String> getMessageReceived() {
            return this.messageReceived;
        }
    }
}