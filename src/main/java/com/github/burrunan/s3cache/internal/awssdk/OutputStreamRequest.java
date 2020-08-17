package com.github.burrunan.s3cache.internal.awssdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import software.amazon.awssdk.core.async.AsyncRequestBody;

public final class OutputStreamRequest extends OutputStream implements AsyncRequestBody {

    private final long contentLength;
    private final AtomicLong demand;
    private final BlockingQueue<String> barrier;

    private Subscriber<? super ByteBuffer> subscriber;

    private volatile boolean cancelled;

    public OutputStreamRequest(long contentLength) {
        this.contentLength = contentLength;
        demand = new AtomicLong();
        barrier = new ArrayBlockingQueue<>(1);
    }

    @Override
    public Optional<Long> contentLength() {
        return Optional.of(contentLength);
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s) {
        subscriber = s;
        s.onSubscribe(new OutputStreamSubscription());
    }

    @Override
    public void write(int b) throws IOException {
        waitForDemand();
        subscriber.onNext(ByteBuffer.wrap(new byte[] { (byte) b }));
    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) throws IOException {
        waitForDemand();
        subscriber.onNext(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void close() {
        subscriber.onComplete();
    }

    private void waitForDemand() throws IOException {
        if (cancelled) {
            throw new IOException("Stream cancelled during write.");
        }
        if (demand.get() == 0) {
            try {
                barrier.take();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted while waiting for async subscriber", e);
            }
        }
        demand.decrementAndGet();
    }

    private class OutputStreamSubscription implements Subscription {
        @Override
        public void request(long n) {
            demand.addAndGet(n);
            barrier.offer("");
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
