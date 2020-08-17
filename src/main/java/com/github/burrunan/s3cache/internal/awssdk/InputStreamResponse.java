package com.github.burrunan.s3cache.internal.awssdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;

public final class InputStreamResponse<T> extends InputStream
        implements AsyncResponseTransformer<T, InputStreamResponse<T>> {

    private static final ByteBuffer EOS = ByteBuffer.wrap(new byte[0]);
    private static final Subscription CLOSED = new Subscription() {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    };

    private final CompletableFuture<InputStreamResponse<T>> future = new CompletableFuture<>();
    private final ByteBuf buf = Unpooled.buffer();
    private final BlockingQueue<ByteBuffer> readQueue = new LinkedBlockingDeque<>();
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();

    private volatile T response;
    private volatile Throwable readError;
    private volatile boolean complete;

    public T response() {
        return response;
    }

    @Override
    public CompletableFuture<InputStreamResponse<T>> prepare() {
        return future;
    }

    @Override
    public void onResponse(T response) {
        this.response = response;
        future.complete(this);
    }

    @Override
    public void onStream(SdkPublisher<ByteBuffer> publisher) {
        publisher.subscribe(new Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Subscription s) {
                if (subscription.compareAndSet(null, s)) {
                    s.request(1);
                } else {
                    // Already closed so cancel the stream.
                    s.cancel();
                }
            }

            @Override
            public void onNext(ByteBuffer byteBuffer) {
                if (byteBuffer.hasRemaining()) {
                    readQueue.add(byteBuffer);
                } else {
                    subscription.get().request(1);
                }
            }

            @Override
            public void onError(Throwable t) {
                readError = t;
                readQueue.add(EOS);
            }

            @Override
            public void onComplete() {
                readQueue.add(EOS);
            }
        });
    }

    @Override
    public void exceptionOccurred(Throwable error) {
        if (response == null) {
            future.completeExceptionally(error);
        } else {
            readError = error;
            readQueue.add(EOS);
        }
    }

    @Override
    public int read() throws IOException {
        if (complete) {
            return -1;
        }

        if (buf.isReadable()) {
            return buf.readByte() & 0xff;
        }

        final ByteBuffer buffer;
        try {
            buffer = readQueue.take();
        } catch (InterruptedException e) {
            throw new IOException("Could not read response", e);
        }
        if (readError != null) {
            throw new IOException("Could not read response", readError);
        }
        if (buffer == EOS) {
            complete = true;
            return -1;
        }

        subscription.get().request(1);
        buf.discardSomeReadBytes();
        buf.writeBytes(buffer);
        return buf.readByte() & 0xff;
    }

    @Override
    public void close() {
        if (subscription.compareAndSet(null, CLOSED)) {
            // No subscription yet, when it comes in we'll cancel it above.
            return;
        }
        Subscription subscription = this.subscription.get();
        if (this.subscription.compareAndSet(subscription, CLOSED)) {
            subscription.cancel();
        }
    }
}
