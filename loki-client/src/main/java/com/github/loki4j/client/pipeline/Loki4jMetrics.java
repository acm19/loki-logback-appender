package com.github.loki4j.client.pipeline;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;

import com.github.loki4j.client.util.Cache.UnboundAtomicMapCache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Counter.Builder;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Provides an ability to report logging performance metrics using Micrometer framework
 */
public class Loki4jMetrics {

    private Timer appendTimer;
    private Timer encodeTimer;
    private Timer sendTimer;

    private DistributionSummary eventsEncodedSummary;
    private DistributionSummary bytesSentSummary;

    private Counter batchesEncodedCounter;
    private Counter batchesSentCounter;
    private Counter droppedEventsCounter;

    private Builder encodeErrorsCounterBuilder;
    private final UnboundAtomicMapCache<String, Counter> encodeErrorsCounterCache = new UnboundAtomicMapCache<>();

    private Builder retryErrorsCounterBuilder;
    private final UnboundAtomicMapCache<String, Counter> retryErrorsCounterCache = new UnboundAtomicMapCache<>();

    private Builder sendErrorsCounterBuilder;
    private final UnboundAtomicMapCache<String, Counter> sendErrorsCounterCache = new UnboundAtomicMapCache<>();

    public Loki4jMetrics(String appenderName, Supplier<Long> unsentEvents) {
        var tags = Arrays.asList(
            Tag.of("appender", appenderName));

        appendTimer = Timer
            .builder("loki4j.append.time")
            .description("Time for a single event append operation")
            .tags(tags)
            .register(Metrics.globalRegistry);

        encodeTimer = Timer
            .builder("loki4j.encode.time")
            .description("Time for a batch encode operation")
            .tags(tags)
            .register(Metrics.globalRegistry);

        sendTimer = Timer
            .builder("loki4j.send.time")
            .description("Time for a HTTP send operation")
            .tags(tags)
            .register(Metrics.globalRegistry);

        eventsEncodedSummary = DistributionSummary
            .builder("loki4j.encode.events")
            .description("Number of log events processed by encoder")
            .tags(tags)
            .register(Metrics.globalRegistry);

        bytesSentSummary = DistributionSummary
            .builder("loki4j.send.bytes")
            .description("Size of batches sent to Loki")
            .baseUnit("bytes")
            .tags(tags)
            .register(Metrics.globalRegistry);

        batchesEncodedCounter = Counter
            .builder("loki4j.encode.batches")
            .description("Number of batches processed by encoder")
            .tags(tags)
            .register(Metrics.globalRegistry);

        batchesSentCounter = Counter
            .builder("loki4j.send.batches")
            .description("Number of batches sent to Loki")
            .tags(tags)
            .register(Metrics.globalRegistry);

        droppedEventsCounter = Counter
            .builder("loki4j.drop.events")
            .description("Number of events dropped due to backpressure settings")
            .tags(tags)
            .register(Metrics.globalRegistry);

        Gauge
            .builder("loki4j.unsent.events", () -> unsentEvents.get())
            .description("Current number of accepted but not yet sent events")
            .tags(tags)
            .register(Metrics.globalRegistry);

        encodeErrorsCounterBuilder = Counter
            .builder("loki4j.encode.errors")
            .description("Number of errors occurred while encoding batches")
            .tags(tags);

        retryErrorsCounterBuilder = Counter
            .builder("loki4j.retry.errors")
            .description("Number of errors occurred while retrying to send failed batches to Loki")
            .tags(tags);

        sendErrorsCounterBuilder = Counter
            .builder("loki4j.send.errors")
            .description("Number of errors occurred while sending batches to Loki")
            .tags(tags);
    }

    private void recordTimer(Timer timer, long startedNs) {
        timer.record(Duration.ofNanos(System.nanoTime() - startedNs));
    }

    public void eventAppended(long startedNs, boolean dropped) {
        recordTimer(appendTimer, startedNs);
        if (dropped) droppedEventsCounter.increment();
    }
    
    public void batchEncoded(long startedNs, int count) {
        recordTimer(encodeTimer, startedNs);
        eventsEncodedSummary.record(count);
        batchesEncodedCounter.increment();
    }

    public void batchEncodeFailed(Supplier<String> failure) {
        incrementErrorCounter(encodeErrorsCounterBuilder, encodeErrorsCounterCache, failure);
    }

    public void sendRetryFailed(Supplier<String> failure) {
        incrementErrorCounter(retryErrorsCounterBuilder, retryErrorsCounterCache, failure);
    }

    public void batchSendFailed(Supplier<String> failure) {
        incrementErrorCounter(sendErrorsCounterBuilder, sendErrorsCounterCache, failure);
    }

    public void batchSent(long startedNs, int bytesCount) {
        recordTimer(sendTimer, startedNs);
        bytesSentSummary.record(bytesCount);
        batchesSentCounter.increment();
    }

    private void incrementErrorCounter(Builder builder, UnboundAtomicMapCache<String, Counter> counterCache, Supplier<String> failure) {
        var failKey = failure.get();
        var errorCounter = counterCache.get(failKey, () -> {
            return builder
                .tag("reason", failKey)
                .register(Metrics.globalRegistry);
        });
        errorCounter.increment();
    }

}
