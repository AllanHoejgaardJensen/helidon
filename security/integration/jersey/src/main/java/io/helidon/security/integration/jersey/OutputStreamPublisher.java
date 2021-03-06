/*
 * Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.security.integration.jersey;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.helidon.common.reactive.Flow;

/**
 * Output stream that {@link Flow.Publisher publishes} any data written to it as {@link ByteBuffer} events.
 */
@SuppressWarnings("WeakerAccess")
class OutputStreamPublisher extends OutputStream implements Flow.Publisher<ByteBuffer> {

    private final SingleSubscriberHolder<ByteBuffer> subscriber = new SingleSubscriberHolder<>();
    private final Object invocationLock = new Object();

    private final RequestedCounter requested = new RequestedCounter();

    private final CompletableFuture<?> completionResult = new CompletableFuture<>();

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriberParam) {
        if (subscriber.register(subscriberParam)) {
            subscriberParam.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    requested.increment(n, t -> complete(t));
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    @Override
    public void write(byte[] b) throws IOException {

        publish(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        publish(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        byte bb = (byte) b;
        publish(new byte[] {bb}, 0, 1);
    }

    @Override
    public void close() throws IOException {
        complete();
        try {
            completionResult.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
    }

    /**
     * Signals this publishing output stream that it can safely return from otherwise blocking invocation
     * to it's {@link #close()} method.
     * Subsequent multiple invocations of this method are allowed, but have no effect on this publishing output stream.
     * <p>
     * When the {@link #close()} method on this output stream is invoked, it will block waiting for a signal to complete.
     * This is useful in cases, when the receiving side needs to synchronize it's completion with the publisher, e.g. to
     * ensure that any resources used by the subscribing party are not released prematurely due to a premature exit from
     * publishing output stream {@code close()} method.
     * <p>
     * Additionally, this mechanism can be used to propagate any downstream completion exceptions back to this publisher
     * and up it's call stack. When a non-null {@code throwable} parameter is passed into the method, it will be wrapped
     * in an {@link IOException} and thrown from the {@code close()} method when it is invoked.
     *
     * @param throwable represents a completion error condition that should be thrown when a {@code close()} method is invoked
     *                  on this publishing output stream. If set to {@code null}, the {@code close()} method will exit normally.
     */
    public void signalCloseComplete(Throwable throwable) {
        if (throwable == null) {
            completionResult.complete(null);
        } else {
            completionResult.completeExceptionally(throwable);
        }
    }

    private void publish(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer);

        try {
            final Flow.Subscriber<? super ByteBuffer> sub = subscriber.get();

            while (!subscriber.isClosed() && !requested.tryDecrement()) {
                //java9
                //Thread.onSpinWait(); // wait until some data can be sent or the stream has been closed
                //java8 :(
                Thread.sleep(30);
            }

            synchronized (invocationLock) {
                if (subscriber.isClosed()) {
                    throw new IOException("Output stream already closed.");
                }

                sub.onNext(ByteBuffer.wrap(buffer, offset, length));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            complete(e);
            throw new IOException(e);
        } catch (ExecutionException e) {
            complete(e.getCause());
            throw new IOException(e.getCause());
        }
    }

    private void complete() {
        subscriber.close(sub -> {
            synchronized (invocationLock) {
                sub.onComplete();
            }
        });
    }

    private void complete(Throwable t) {
        subscriber.close(sub -> {
            synchronized (invocationLock) {
                sub.onError(t);
            }
        });
    }
}
