package dev.openfeature.contrib.providers.flagd.resolver.process.storage;

import dev.openfeature.contrib.providers.flagd.resolver.process.model.FeatureFlag;
import dev.openfeature.contrib.providers.flagd.resolver.process.model.FlagParser;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.Connector;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.StreamPayload;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.extern.java.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.logging.Level;

/**
 * Feature flag storage.
 */
@Log
@SuppressFBWarnings(value = {"EI_EXPOSE_REP"},
        justification = "Feature flag comes as a Json configuration, hence they must be exposed")
public class FlagStore implements Storage {
    private final ReentrantReadWriteLock sync = new ReentrantReadWriteLock();
    private final ReadLock readLock = sync.readLock();
    private final WriteLock writeLock = sync.writeLock();

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final BlockingQueue<StorageState> stateBlockingQueue = new LinkedBlockingQueue<>(1);
    private final Map<String, FeatureFlag> flags = new HashMap<>();

    private final Connector connector;

    public FlagStore(final Connector connector) {
        this.connector = connector;
    }

    /**
     * Initialize storage layer.
     */
    public void init() {
        connector.init();
        Thread streamer = new Thread(() -> {
            try {
                streamerListener(connector);
            } catch (InterruptedException e) {
                log.log(Level.WARNING, "connection listener failed", e);
            }
        });
        streamer.setDaemon(true);
        streamer.start();
    }

    /**
     * Shutdown storage layer.
     */
    public void shutdown() {
        if (shutdown.getAndSet(true)) {
            return;
        }

        connector.shutdown();
    }

    /**
     * Retrieve flag for the given key.
     */
    public FeatureFlag getFlag(final String key) {
        readLock.lock();
        try {
            return flags.get(key);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Retrieve blocking queue to check storage status.
     */
    public BlockingQueue<StorageState> getStateQueue() {
        return stateBlockingQueue;
    }

    private void streamerListener(final Connector connector) throws InterruptedException {
        final BlockingQueue<StreamPayload> streamPayloads = connector.getStream();

        while (!shutdown.get()) {
            final StreamPayload take = streamPayloads.take();
            switch (take.getType()) {
                case DATA:
                    try {
                        Map<String, FeatureFlag> flagMap = FlagParser.parseString(take.getData());
                        writeLock.lock();
                        try {
                            flags.clear();
                            flags.putAll(flagMap);
                        } finally {
                            writeLock.unlock();
                        }
                        if (!stateBlockingQueue.offer(StorageState.OK)) {
                            log.log(Level.WARNING, "Failed to convey OK satus, queue is full");
                        }
                    } catch (Throwable e) {
                        // catch all exceptions and avoid stream listener interruptions
                        log.log(Level.WARNING, "Invalid flag sync payload from connector", e);
                        if (!stateBlockingQueue.offer(StorageState.STALE)) {
                            log.log(Level.WARNING, "Failed to convey STALE satus, queue is full");
                        }
                    }
                    break;
                case ERROR:
                    if (!stateBlockingQueue.offer(StorageState.ERROR)) {
                        log.log(Level.WARNING, "Failed to convey ERROR satus, queue is full");
                    }
                    break;
                default:
                    log.log(Level.INFO, String.format("Payload with unknown type: %s", take.getType()));
            }
        }

        log.log(Level.INFO, "Shutting down store stream listener");
    }

}