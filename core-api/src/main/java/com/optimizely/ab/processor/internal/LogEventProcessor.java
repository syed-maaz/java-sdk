/**
 *    Copyright 2019, Optimizely Inc. and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.processor.internal;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.optimizely.ab.common.callback.Callback;
import com.optimizely.ab.common.internal.Assert;
import com.optimizely.ab.common.plugin.PluginSupport;
import com.optimizely.ab.event.EventHandler;
import com.optimizely.ab.event.LogEvent;
import com.optimizely.ab.event.internal.EventFactory;
import com.optimizely.ab.event.internal.payload.EventBatch;
import com.optimizely.ab.processor.AbstractProcessor;
import com.optimizely.ab.processor.EventProcessorBuilder;
import com.optimizely.ab.processor.ProcessingStage;
import com.optimizely.ab.processor.Processor;
import com.optimizely.ab.processor.disruptor.DisruptorBufferConfig;
import com.optimizely.ab.processor.disruptor.DisruptorBufferStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Processor for events.
 */
public class LogEventProcessor<T> extends EventProcessorBuilder<T, EventBatch, LogEvent, LogEventProcessor<T>> implements PluginSupport {
    private static final Logger logger = LoggerFactory.getLogger(LogEventProcessor.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 1024;
    private static final int DEFAULT_MAX_BATCH_SIZE = 50;

    private Integer batchMaxSize;
    private WaitStrategy waitStrategy;
    private ThreadFactory threadFactory;
    private Integer bufferSize;
    private Function<EventBatch, LogEvent> eventFactory = (new EventFactory())::createLogEvent; // TODO do not default
    private BiConsumer<LogEvent, Throwable> logEventExceptionHandler = (logEvent, err) -> {
        logger.error("Error dispatching event: {}", logEvent, err);
    };

    @Override
    public ProcessingStage<EventBatch, LogEvent> getBatchStage() {
        // Default to legacy behavior
        if (batchMaxSize == null || batchMaxSize <= 1) {
            logger.info("Batching is not configured. Events will be dispatched individually"); // TODO warn
            return sink -> new LegacyEventOperator(sink, eventFactory, this::getCallback);
        }

        ThreadFactory threadFactory = getThreadFactory();
        if (threadFactory == null) {
            threadFactory = DaemonThreadFactory.INSTANCE;
        }

        WaitStrategy waitStrategy = getWaitStrategy();
        if (waitStrategy == null) {
            waitStrategy = new BlockingWaitStrategy();
        }

        Integer batchMaxSize = getBatchMaxSize();
        if (batchMaxSize == null) {
            batchMaxSize = DEFAULT_MAX_BATCH_SIZE;
        }

        Integer bufferCapacity = getBufferSize();
        if (bufferCapacity == null) {
            bufferCapacity = DEFAULT_BUFFER_CAPACITY;
        }

        DisruptorBufferStage<EventBatch> buffer = new DisruptorBufferStage<>(DisruptorBufferConfig.builder()
            .batchMaxSize(batchMaxSize)
            .capacity(bufferCapacity)
            .threadFactory(threadFactory)
            .waitStrategy(waitStrategy)
            .build());

        EventBatchMergeStage batchFormatStage = new EventBatchMergeStage(getEventFactory(), this::getCallback);

        return buffer.andThen(batchFormatStage);
    }

    Function<EventBatch, LogEvent> getEventFactory() {
        return eventFactory;
    }

    WaitStrategy getWaitStrategy() {
        return waitStrategy;
    }

    ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    Integer getBatchMaxSize() {
        return batchMaxSize;
    }

    BiConsumer<LogEvent, Throwable> getLogEventExceptionHandler() {
        return logEventExceptionHandler;
    }

    Integer getBufferSize() {
        return bufferSize;
    }

    public LogEventProcessor<T> batchMaxSize(int n) {
        Assert.isTrue(n > 0, "size must be positive");
        batchMaxSize = n;
        return this;
    }

    public LogEventProcessor<T> threadFactory(ThreadFactory threadFactory) {
        this.threadFactory = Assert.notNull(threadFactory, "threadFactory");
        return this;
    }

    public LogEventProcessor<T> eventFactory(Function<EventBatch, LogEvent> eventFactory) {
        this.eventFactory = Assert.notNull(eventFactory, "eventFactory");
        return this;
    }

    public LogEventProcessor<T> eventFactory(EventFactory eventFactory) {
        return eventFactory(Assert.notNull(eventFactory, "eventFactory")::createLogEvent);
    }

    public LogEventProcessor<T> eventHandler(EventHandler eventHandler) {
        return eventHandler(eventHandler, null);
    }

    public LogEventProcessor<T> eventHandler(EventHandler eventHandler, BiConsumer<LogEvent, Throwable> exceptionHandler) {
        return sink(new EventHandlerSink(eventHandler, exceptionHandler));
    }

    /**
     * Configures the ring buffer size
     */
    LogEventProcessor<T> bufferSize(int n) {
        Assert.isTrue(n > 0, "size must be positive");
        bufferSize = n;
        return this;
    }

    /**
     * Configures the wait strategy for ring buffer consumer
     */
    LogEventProcessor<T> waitStrategy(WaitStrategy waitStrategy) {
        this.waitStrategy = waitStrategy;
        return this;
    }

    static class LegacyEventOperator extends AbstractProcessor<EventBatch, LogEvent> {
        private final Function<EventBatch, LogEvent> eventFactory;
        private final Supplier<Callback<EventBatch>> callbackSupplier;

        public LegacyEventOperator(
            Processor<LogEvent> sink,
            Function<EventBatch, LogEvent> eventFactory,
            Supplier<Callback<EventBatch>> callbackSupplier
        ) {
            super(sink);
            this.eventFactory = Assert.notNull(eventFactory, "eventFactory");
            this.callbackSupplier = Assert.notNull(callbackSupplier, "callbackSupplier");
        }

        @Override
        public void process(EventBatch input) {
            if (input == null) {
                return;
            }

            LogEvent output = eventFactory.apply(input);
            if (output == null) {
                return;
            }

            setCallback(output);

            getSink().process(output);
        }

        private void setCallback(LogEvent logEvent) {
            Callback<EventBatch> callback = callbackSupplier.get();
            if (callback != null) {
                logEvent.setCallback(callback);
            }
        }
    }

    /**
     * Adapts an {@link EventHandler} to the {@link Processor} interface.
     */
    static class EventHandlerSink implements Processor<LogEvent> {
        private static final Logger logger = LoggerFactory.getLogger(EventHandlerSink.class);

        private final EventHandler eventHandler;
        private final BiConsumer<LogEvent, Throwable> exceptionHandler;

        EventHandlerSink(
            EventHandler eventHandler,
            BiConsumer<LogEvent, Throwable> exceptionHandler
        ) {
            this.eventHandler = Assert.notNull(eventHandler, "eventHandler");
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        public void process(LogEvent logEvent) {
            handle(logEvent);
        }

        @Override
        public void processBatch(Collection<? extends LogEvent> batch) {
            for (final LogEvent logEvent : batch) {
                handle(logEvent);
            }
        }

        private void handle(LogEvent logEvent) {
            try {
                logger.trace("Invoking {}", eventHandler);

                eventHandler.dispatchEvent(logEvent);

                logger.trace("Finished invoking event handler");
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.accept(logEvent, e);
                } else {
                    logger.warn("Error while dispatching to {}", eventHandler, e);
                    throw new RuntimeException("Failed to invoke EventHandler", e);
                }
            }
        }
    }
}
