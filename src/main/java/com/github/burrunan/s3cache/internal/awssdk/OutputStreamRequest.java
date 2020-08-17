package com.github.burrunan.s3cache.internal.awssdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Subscriber;

import software.amazon.awssdk.core.async.AsyncRequestBody;

public class OutputStreamRequest extends OutputStream implements AsyncRequestBody {

    private final long contentLength;
    private final CountDownLatch subscribed;

    private volatile Subscriber<? super ByteBuffer> subscriber;

    public OutputStreamRequest(long contentLength) {
        this.contentLength = contentLength;
        subscribed = new CountDownLatch(1);
    }

    @Override
    public Optional<Long> contentLength() {
        return Optional.of(contentLength);
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s) {
        subscriber = s;
        subscribed.countDown();
    }

    @Override
    public void write(int b) throws IOException {
        waitForSubscriber();
        subscriber.onNext(ByteBuffer.wrap(new byte[] { (byte) b }));
    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) throws IOException {
        waitForSubscriber();
        subscriber.onNext(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void close() {
        subscriber.onComplete();
    }

    private void waitForSubscriber() throws IOException {
        try {
            subscribed.await();
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while waiting for async subscriber", e);
        }
    }
}
