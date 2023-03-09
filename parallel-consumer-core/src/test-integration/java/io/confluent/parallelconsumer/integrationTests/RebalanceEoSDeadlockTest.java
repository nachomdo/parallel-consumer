package io.confluent.parallelconsumer.integrationTests;

/*-
 * Copyright (C) 2020-2022 Confluent, Inc.
 */

import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelEoSStreamProcessor;
import io.confluent.parallelconsumer.integrationTests.utils.KafkaClientUtils;
import io.confluent.parallelconsumer.internal.PCModule;
import io.confluent.parallelconsumer.state.WorkManager;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import pl.tlinkowski.unij.api.UniLists;
import pl.tlinkowski.unij.api.UniSets;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static io.confluent.parallelconsumer.ParallelConsumerOptions.ProcessingOrder.PARTITION;
import static io.confluent.parallelconsumer.integrationTests.utils.KafkaClientUtils.GroupOption.REUSE_GROUP;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;

/**
 * Originally created to reproduce the bug #541 https://github.com/confluentinc/parallel-consumer/issues/541
 * <p>
 * This test reproduces the potential deadlock situation when a rebalance occurs
 * using EoS with transactional producer configuration.
 * The solution aims to avoid the deadlock by reordering the acquisition of locks
 * in the {@link io.confluent.parallelconsumer.internal.AbstractParallelEoSStreamProcessor#onPartitionsRevoked(Collection)} method.
 *
 * @author Nacho Munoz
 */
@Slf4j
class RebalanceEoSDeadlockTest extends BrokerIntegrationTest<String, String> {

    private static final String PC_CONTROL = "pc-control";
    Consumer<String, String> consumer;
    Producer<String, String> producer;

    CountDownLatch rebalanceLatch;
    private long sleepTimeMs = 0L;

    ParallelEoSStreamProcessor<String, String> pc;

    {
        super.numPartitions = 2;
    }

    @BeforeEach
    void setup() {
        rebalanceLatch = new CountDownLatch(1);
        setupTopic();

        producer = getKcu().createNewProducer(KafkaClientUtils.ProducerMode.TRANSACTIONAL);
        consumer = getKcu().createNewConsumer(KafkaClientUtils.GroupOption.NEW_GROUP);
        var pcOptions = ParallelConsumerOptions.<String,String>builder()
                .commitMode(ParallelConsumerOptions.CommitMode.PERIODIC_TRANSACTIONAL_PRODUCER)
                .consumer(consumer)
                .produceLockAcquisitionTimeout(Duration.ofMinutes(2))
                .producer(producer)
                .ordering(PARTITION) // just so we dont need to use keys
                .build();

        var module = new PCModule<>(pcOptions){
            @Override
            public WorkManager<String,String> workManager(){
                var wm = Mockito.spy(super.workManager());
                Mockito.when(wm.isDirty()).thenReturn(true);
                return wm;
            }
        };

        pc = new ParallelEoSStreamProcessor<>(pcOptions, module) {
            @Override
            protected boolean isTimeToCommitNow() {
                return true;
            }
            @Override
            protected void commitOffsetsThatAreReady() throws TimeoutException, InterruptedException {
                final var threadName = Thread.currentThread().getName();
                if (threadName.contains(PC_CONTROL)) {
                    log.info("Delaying pc-control thread {}ms to force the potential deadlock on rebalance", sleepTimeMs);
                    Thread.sleep(sleepTimeMs);
                } else if (injectRebalanceError) {
                    log.info("Injecting rebalance error");
                    throw new RuntimeException("Injected rebalance error");
                }

                super.commitOffsetsThatAreReady();
            }
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                try {
                    super.onPartitionsRevoked(partitions);
                } finally {
                    rebalanceStatusCleared = !this.rebalanceInProgress.get();
                    rebalanceLatch.countDown();
                }
            }
        };

        pc.subscribe(UniSets.of(topic));
    }

    // check that rebalance status is clear even if an error occurs
    private boolean rebalanceStatusCleared = false;

    // randomly inject an error during the rebalance
    private boolean injectRebalanceError = false;

    @SneakyThrows
    @ParameterizedTest
    @ValueSource(longs = {500L, 1000L, 3000L})
    void noDeadlockOnRevoke(long sleepTimeMs) {
        this.sleepTimeMs = sleepTimeMs;
        this.injectRebalanceError = Math.random() > 0.5;
        var numberOfRecordsToProduce = 100L;
        var count = new AtomicLong();

        getKcu().produceMessages(topic, numberOfRecordsToProduce);

        // consume some records
        pc.poll(recordContexts -> {
            count.getAndIncrement();
            log.debug("Processed record, count now {} - offset: {}", count, recordContexts.offset());
        });

        await().timeout(Duration.ofSeconds(30)).untilAtomic(count,is(greaterThan(1L)));
        log.debug("Records are getting consumed");

        // cause rebalance
        final Duration newPollTimeout = Duration.ofSeconds(5);
        log.debug("Creating new consumer in same group and subscribing to same topic set with a no record timeout of {}, expect this phase to take entire timeout...", newPollTimeout);
        try (var newConsumer = getKcu().createNewConsumer(REUSE_GROUP)) {
            newConsumer.subscribe(UniLists.of(topic));
            newConsumer.poll(newPollTimeout);

            if (!rebalanceLatch.await(30, TimeUnit.SECONDS)) {
                Assertions.fail("Rebalance did not occur");
            }
            Assertions.assertTrue(rebalanceStatusCleared, "Rebalance status was not cleared");
            log.debug("Test finished");
        }
    }
}
