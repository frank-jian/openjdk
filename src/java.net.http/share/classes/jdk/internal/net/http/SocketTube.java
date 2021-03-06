/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.net.http;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import jdk.internal.net.http.common.Demand;
import jdk.internal.net.http.common.FlowTube;
import jdk.internal.net.http.common.Logger;
import jdk.internal.net.http.common.SequentialScheduler;
import jdk.internal.net.http.common.SequentialScheduler.DeferredCompleter;
import jdk.internal.net.http.common.SequentialScheduler.RestartableTask;
import jdk.internal.net.http.common.Utils;

/**
 * A SocketTube is a terminal tube plugged directly into the socket.
 * The read subscriber should call {@code subscribe} on the SocketTube before
 * the SocketTube is subscribed to the write publisher.
 */
final class SocketTube implements FlowTube {

    final Logger debug = Utils.getDebugLogger(this::dbgString, Utils.DEBUG);
    static final AtomicLong IDS = new AtomicLong();

    private final HttpClientImpl client;
    private final SocketChannel channel;
    private final Supplier<ByteBuffer> buffersSource;
    private final Object lock = new Object();
    private final AtomicReference<Throwable> errorRef = new AtomicReference<>();
    private final InternalReadPublisher readPublisher;
    private final InternalWriteSubscriber writeSubscriber;
    private final long id = IDS.incrementAndGet();

    public SocketTube(HttpClientImpl client, SocketChannel channel,
                      Supplier<ByteBuffer> buffersSource) {
        this.client = client;
        this.channel = channel;
        this.buffersSource = buffersSource;
        this.readPublisher = new InternalReadPublisher();
        this.writeSubscriber = new InternalWriteSubscriber();
    }

    /**
     * Returns {@code true} if this flow is finished.
     * This happens when this flow internal read subscription is completed,
     * either normally (EOF reading) or exceptionally  (EOF writing, or
     * underlying socket closed, or some exception occurred while reading or
     * writing to the socket).
     *
     * @return {@code true} if this flow is finished.
     */
    public boolean isFinished() {
        InternalReadPublisher.InternalReadSubscription subscription =
                readPublisher.subscriptionImpl;
        return subscription != null && subscription.completed
                || subscription == null && errorRef.get() != null;
    }

    // ===================================================================== //
    //                       Flow.Publisher                                  //
    // ======================================================================//

    /**
     * {@inheritDoc }
     * @apiNote This method should be called first. In particular, the caller
     *          must ensure that this method must be called by the read
     *          subscriber before the write publisher can call {@code onSubscribe}.
     *          Failure to adhere to this contract may result in assertion errors.
     */
    @Override
    public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> s) {
        Objects.requireNonNull(s);
        assert s instanceof TubeSubscriber : "Expected TubeSubscriber, got:" + s;
        readPublisher.subscribe(s);
    }


    // ===================================================================== //
    //                       Flow.Subscriber                                 //
    // ======================================================================//

    /**
     * {@inheritDoc }
     * @apiNote The caller must ensure that {@code subscribe} is called by
     *          the read subscriber before {@code onSubscribe} is called by
     *          the write publisher.
     *          Failure to adhere to this contract may result in assertion errors.
     */
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        writeSubscriber.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        writeSubscriber.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        writeSubscriber.onError(throwable);
    }

    @Override
    public void onComplete() {
        writeSubscriber.onComplete();
    }

    // ===================================================================== //
    //                           Events                                      //
    // ======================================================================//

    void signalClosed() {
        // Ensures that the subscriber will be terminated and that future
        // subscribers will be notified when the connection is closed.
        readPublisher.subscriptionImpl.signalError(
                new IOException("connection closed locally"));
    }

    /**
     * A restartable task used to process tasks in sequence.
     */
    private static class SocketFlowTask implements RestartableTask {
        final Runnable task;
        private final Object monitor = new Object();
        SocketFlowTask(Runnable task) {
            this.task = task;
        }
        @Override
        public final void run(DeferredCompleter taskCompleter) {
            try {
                // non contentious synchronized for visibility.
                synchronized(monitor) {
                    task.run();
                }
            } finally {
                taskCompleter.complete();
            }
        }
    }

    // This is best effort - there's no guarantee that the printed set of values
    // is consistent. It should only be considered as weakly accurate - in
    // particular in what concerns the events states, especially when displaying
    // a read event state from a write event callback and conversely.
    void debugState(String when) {
        if (debug.on()) {
            StringBuilder state = new StringBuilder();

            InternalReadPublisher.InternalReadSubscription sub =
                    readPublisher.subscriptionImpl;
            InternalReadPublisher.ReadEvent readEvent =
                    sub == null ? null : sub.readEvent;
            Demand rdemand = sub == null ? null : sub.demand;
            InternalWriteSubscriber.WriteEvent writeEvent =
                    writeSubscriber.writeEvent;
            Demand wdemand = writeSubscriber.writeDemand;
            int rops = readEvent == null ? 0 : readEvent.interestOps();
            long rd = rdemand == null ? 0 : rdemand.get();
            int wops = writeEvent == null ? 0 : writeEvent.interestOps();
            long wd = wdemand == null ? 0 : wdemand.get();

            state.append(when).append(" Reading: [ops=")
                    .append(rops).append(", demand=").append(rd)
                    .append(", stopped=")
                    .append((sub == null ? false : sub.readScheduler.isStopped()))
                    .append("], Writing: [ops=").append(wops)
                    .append(", demand=").append(wd)
                    .append("]");
            debug.log(state.toString());
        }
    }

    /**
     * A repeatable event that can be paused or resumed by changing its
     * interestOps. When the event is fired, it is first paused before being
     * signaled. It is the responsibility of the code triggered by
     * {@code signalEvent} to resume the event if required.
     */
    private static abstract class SocketFlowEvent extends AsyncEvent {
        final SocketChannel channel;
        final int defaultInterest;
        volatile int interestOps;
        volatile boolean registered;
        SocketFlowEvent(int defaultInterest, SocketChannel channel) {
            super(AsyncEvent.REPEATING);
            this.defaultInterest = defaultInterest;
            this.channel = channel;
        }
        final boolean registered() {return registered;}
        final void resume() {
            interestOps = defaultInterest;
            registered = true;
        }
        final void pause() {interestOps = 0;}
        @Override
        public final SelectableChannel channel() {return channel;}
        @Override
        public final int interestOps() {return interestOps;}

        @Override
        public final void handle() {
            pause();       // pause, then signal
            signalEvent(); // won't be fired again until resumed.
        }
        @Override
        public final void abort(IOException error) {
            debug().log(() -> "abort: " + error);
            pause();              // pause, then signal
            signalError(error);   // should not be resumed after abort (not checked)
        }

        protected abstract void signalEvent();
        protected abstract void signalError(Throwable error);
        abstract Logger debug();
    }

    // ===================================================================== //
    //                              Writing                                  //
    // ======================================================================//

    // This class makes the assumption that the publisher will call onNext
    // sequentially, and that onNext won't be called if the demand has not been
    // incremented by request(1).
    // It has a 'queue of 1' meaning that it will call request(1) in
    // onSubscribe, and then only after its 'current' buffer list has been
    // fully written and current set to null;
    private final class InternalWriteSubscriber
            implements Flow.Subscriber<List<ByteBuffer>> {

        volatile WriteSubscription subscription;
        volatile List<ByteBuffer> current;
        volatile boolean completed;
        final AsyncTriggerEvent startSubscription =
                new AsyncTriggerEvent(this::signalError, this::startSubscription);
        final WriteEvent writeEvent = new WriteEvent(channel, this);
        final Demand writeDemand = new Demand();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            WriteSubscription previous = this.subscription;
            if (debug.on()) debug.log("subscribed for writing");
            try {
                boolean needEvent = current == null;
                if (needEvent) {
                    if (previous != null && previous.upstreamSubscription != subscription) {
                        previous.dropSubscription();
                    }
                }
                this.subscription = new WriteSubscription(subscription);
                if (needEvent) {
                    if (debug.on())
                        debug.log("write: registering startSubscription event");
                    client.registerEvent(startSubscription);
                }
            } catch (Throwable t) {
                signalError(t);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> bufs) {
            assert current == null : dbgString() // this is a queue of 1.
                    + "w.onNext current: " + current;
            assert subscription != null : dbgString()
                    + "w.onNext: subscription is null";
            current = bufs;
            tryFlushCurrent(client.isSelectorThread()); // may be in selector thread
            // For instance in HTTP/2, a received SETTINGS frame might trigger
            // the sending of a SETTINGS frame in turn which might cause
            // onNext to be called from within the same selector thread that the
            // original SETTINGS frames arrived on. If rs is the read-subscriber
            // and ws is the write-subscriber then the following can occur:
            // ReadEvent -> rs.onNext(bytes) -> process server SETTINGS -> write
            // client SETTINGS -> ws.onNext(bytes) -> tryFlushCurrent
            debugState("leaving w.onNext");
        }

        // Don't use a SequentialScheduler here: rely on onNext() being invoked
        // sequentially, and not being invoked if there is no demand, request(1).
        // onNext is usually called from within a user / executor thread.
        // Initial writing will be performed in that thread. If for some reason,
        // not all the data can be written, a writeEvent will be registered, and
        // writing will resume in the the selector manager thread when the
        // writeEvent is fired.
        //
        // If this method is invoked in the selector manager thread (because of
        // a writeEvent), then the executor will be used to invoke request(1),
        // ensuring that onNext() won't be invoked from within the selector
        // thread. If not in the selector manager thread, then request(1) is
        // invoked directly.
        void tryFlushCurrent(boolean inSelectorThread) {
            List<ByteBuffer> bufs = current;
            if (bufs == null) return;
            try {
                assert inSelectorThread == client.isSelectorThread() :
                       "should " + (inSelectorThread ? "" : "not ")
                        + " be in the selector thread";
                long remaining = Utils.remaining(bufs);
                if (debug.on()) debug.log("trying to write: %d", remaining);
                long written = writeAvailable(bufs);
                if (debug.on()) debug.log("wrote: %d", written);
                assert written >= 0 : "negative number of bytes written:" + written;
                assert written <= remaining;
                if (remaining - written == 0) {
                    current = null;
                    if (writeDemand.tryDecrement()) {
                        Runnable requestMore = this::requestMore;
                        if (inSelectorThread) {
                            assert client.isSelectorThread();
                            client.theExecutor().execute(requestMore);
                        } else {
                            assert !client.isSelectorThread();
                            requestMore.run();
                        }
                    }
                } else {
                    resumeWriteEvent(inSelectorThread);
                }
            } catch (Throwable t) {
                signalError(t);
                subscription.cancel();
            }
        }

        // Kick off the initial request:1 that will start the writing side.
        // Invoked in the selector manager thread.
        void startSubscription() {
            try {
                if (debug.on()) debug.log("write: starting subscription");
                assert client.isSelectorThread();
                // make sure read registrations are handled before;
                readPublisher.subscriptionImpl.handlePending();
                if (debug.on()) debug.log("write: offloading requestMore");
                // start writing;
                client.theExecutor().execute(this::requestMore);
            } catch(Throwable t) {
                signalError(t);
            }
        }

        void requestMore() {
           WriteSubscription subscription = this.subscription;
           subscription.requestMore();
        }

        @Override
        public void onError(Throwable throwable) {
            signalError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
            // no need to pause the write event here: the write event will
            // be paused if there is nothing more to write.
            List<ByteBuffer> bufs = current;
            long remaining = bufs == null ? 0 : Utils.remaining(bufs);
            if (debug.on())
                debug.log( "write completed, %d yet to send", remaining);
            debugState("InternalWriteSubscriber::onComplete");
        }

        void resumeWriteEvent(boolean inSelectorThread) {
            if (debug.on()) debug.log("scheduling write event");
            resumeEvent(writeEvent, this::signalError);
        }

        void signalWritable() {
            if (debug.on()) debug.log("channel is writable");
            tryFlushCurrent(true);
        }

        void signalError(Throwable error) {
            debug.log(() -> "write error: " + error);
            completed = true;
            readPublisher.signalError(error);
        }

        // A repeatable WriteEvent which is paused after firing and can
        // be resumed if required - see SocketFlowEvent;
        final class WriteEvent extends SocketFlowEvent {
            final InternalWriteSubscriber sub;
            WriteEvent(SocketChannel channel, InternalWriteSubscriber sub) {
                super(SelectionKey.OP_WRITE, channel);
                this.sub = sub;
            }
            @Override
            protected final void signalEvent() {
                try {
                    client.eventUpdated(this);
                    sub.signalWritable();
                } catch(Throwable t) {
                    sub.signalError(t);
                }
            }

            @Override
            protected void signalError(Throwable error) {
                sub.signalError(error);
            }

            @Override
            Logger debug() { return debug; }
        }

        final class WriteSubscription implements Flow.Subscription {
            final Flow.Subscription upstreamSubscription;
            volatile boolean cancelled;
            WriteSubscription(Flow.Subscription subscription) {
                this.upstreamSubscription = subscription;
            }

            @Override
            public void request(long n) {
                if (cancelled) return;
                upstreamSubscription.request(n);
            }

            @Override
            public void cancel() {
                dropSubscription();
                upstreamSubscription.cancel();
            }

            void dropSubscription() {
                synchronized (InternalWriteSubscriber.this) {
                    cancelled = true;
                    if (debug.on()) debug.log("write: resetting demand to 0");
                    writeDemand.reset();
                }
            }

            void requestMore() {
                try {
                    if (completed || cancelled) return;
                    boolean requestMore;
                    long d;
                    // don't fiddle with demand after cancel.
                    // see dropSubscription.
                    synchronized (InternalWriteSubscriber.this) {
                        if (cancelled) return;
                        d = writeDemand.get();
                        requestMore = writeDemand.increaseIfFulfilled();
                    }
                    if (requestMore) {
                        if (debug.on()) debug.log("write: requesting more...");
                        upstreamSubscription.request(1);
                    } else {
                        if (debug.on())
                            debug.log("write: no need to request more: %d", d);
                    }
                } catch (Throwable t) {
                    if (debug.on())
                        debug.log("write: error while requesting more: " + t);
                    cancelled = true;
                    signalError(t);
                    subscription.cancel();
                } finally {
                    debugState("leaving requestMore: ");
                }
            }
        }
    }

    // ===================================================================== //
    //                              Reading                                  //
    // ===================================================================== //

    // The InternalReadPublisher uses a SequentialScheduler to ensure that
    // onNext/onError/onComplete are called sequentially on the caller's
    // subscriber.
    // However, it relies on the fact that the only time where
    // runOrSchedule() is called from a user/executor thread is in signalError,
    // right after the errorRef has been set.
    // Because the sequential scheduler's task always checks for errors first,
    // and always terminate the scheduler on error, then it is safe to assume
    // that if it reaches the point where it reads from the channel, then
    // it is running in the SelectorManager thread. This is because all
    // other invocation of runOrSchedule() are triggered from within a
    // ReadEvent.
    //
    // When pausing/resuming the event, some shortcuts can then be taken
    // when we know we're running in the selector manager thread
    // (in that case there's no need to call client.eventUpdated(readEvent);
    //
    private final class InternalReadPublisher
            implements Flow.Publisher<List<ByteBuffer>> {
        private final InternalReadSubscription subscriptionImpl
                = new InternalReadSubscription();
        AtomicReference<ReadSubscription> pendingSubscription = new AtomicReference<>();
        private volatile ReadSubscription subscription;

        @Override
        public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> s) {
            Objects.requireNonNull(s);

            TubeSubscriber sub = FlowTube.asTubeSubscriber(s);
            ReadSubscription target = new ReadSubscription(subscriptionImpl, sub);
            ReadSubscription previous = pendingSubscription.getAndSet(target);

            if (previous != null && previous != target) {
                if (debug.on())
                    debug.log("read publisher: dropping pending subscriber: "
                              + previous.subscriber);
                previous.errorRef.compareAndSet(null, errorRef.get());
                previous.signalOnSubscribe();
                if (subscriptionImpl.completed) {
                    previous.signalCompletion();
                } else {
                    previous.subscriber.dropSubscription();
                }
            }

            if (debug.on()) debug.log("read publisher got subscriber");
            subscriptionImpl.signalSubscribe();
            debugState("leaving read.subscribe: ");
        }

        void signalError(Throwable error) {
            if (debug.on()) debug.log("error signalled " + error);
            if (!errorRef.compareAndSet(null, error)) {
                return;
            }
            subscriptionImpl.handleError();
        }

        final class ReadSubscription implements Flow.Subscription {
            final InternalReadSubscription impl;
            final TubeSubscriber  subscriber;
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            volatile boolean subscribed;
            volatile boolean cancelled;
            volatile boolean completed;

            public ReadSubscription(InternalReadSubscription impl,
                                    TubeSubscriber subscriber) {
                this.impl = impl;
                this.subscriber = subscriber;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public void request(long n) {
                if (!cancelled) {
                    impl.request(n);
                } else {
                    if (debug.on())
                        debug.log("subscription cancelled, ignoring request %d", n);
                }
            }

            void signalCompletion() {
                assert subscribed || cancelled;
                if (completed || cancelled) return;
                synchronized (this) {
                    if (completed) return;
                    completed = true;
                }
                Throwable error = errorRef.get();
                if (error != null) {
                    if (debug.on())
                        debug.log("forwarding error to subscriber: " + error);
                    subscriber.onError(error);
                } else {
                    if (debug.on()) debug.log("completing subscriber");
                    subscriber.onComplete();
                }
            }

            void signalOnSubscribe() {
                if (subscribed || cancelled) return;
                synchronized (this) {
                    if (subscribed || cancelled) return;
                    subscribed = true;
                }
                subscriber.onSubscribe(this);
                if (debug.on()) debug.log("onSubscribe called");
                if (errorRef.get() != null) {
                    signalCompletion();
                }
            }
        }

        final class InternalReadSubscription implements Flow.Subscription {

            private final Demand demand = new Demand();
            final SequentialScheduler readScheduler;
            private volatile boolean completed;
            private final ReadEvent readEvent;
            private final AsyncEvent subscribeEvent;

            InternalReadSubscription() {
                readScheduler = new SequentialScheduler(new SocketFlowTask(this::read));
                subscribeEvent = new AsyncTriggerEvent(this::signalError,
                                                       this::handleSubscribeEvent);
                readEvent = new ReadEvent(channel, this);
            }

            /*
             * This method must be invoked before any other method of this class.
             */
            final void signalSubscribe() {
                if (readScheduler.isStopped() || completed) {
                    // if already completed or stopped we can handle any
                    // pending connection directly from here.
                    if (debug.on())
                        debug.log("handling pending subscription while completed");
                    handlePending();
                } else {
                    try {
                        if (debug.on()) debug.log("registering subscribe event");
                        client.registerEvent(subscribeEvent);
                    } catch (Throwable t) {
                        signalError(t);
                        handlePending();
                    }
                }
            }

            final void handleSubscribeEvent() {
                assert client.isSelectorThread();
                debug.log("subscribe event raised");
                readScheduler.runOrSchedule();
                if (readScheduler.isStopped() || completed) {
                    // if already completed or stopped we can handle any
                    // pending connection directly from here.
                    if (debug.on())
                        debug.log("handling pending subscription when completed");
                    handlePending();
                }
            }


            /*
             * Although this method is thread-safe, the Reactive-Streams spec seems
             * to not require it to be as such. It's a responsibility of the
             * subscriber to signal demand in a thread-safe manner.
             *
             * See Reactive Streams specification, rules 2.7 and 3.4.
             */
            @Override
            public final void request(long n) {
                if (n > 0L) {
                    boolean wasFulfilled = demand.increase(n);
                    if (wasFulfilled) {
                        if (debug.on()) debug.log("got some demand for reading");
                        resumeReadEvent();
                        // if demand has been changed from fulfilled
                        // to unfulfilled register read event;
                    }
                } else {
                    signalError(new IllegalArgumentException("non-positive request"));
                }
                debugState("leaving request("+n+"): ");
            }

            @Override
            public final void cancel() {
                pauseReadEvent();
                readScheduler.stop();
            }

            private void resumeReadEvent() {
                if (debug.on()) debug.log("resuming read event");
                resumeEvent(readEvent, this::signalError);
            }

            private void pauseReadEvent() {
                if (debug.on()) debug.log("pausing read event");
                pauseEvent(readEvent, this::signalError);
            }


            final void handleError() {
                assert errorRef.get() != null;
                readScheduler.runOrSchedule();
            }

            final void signalError(Throwable error) {
                if (!errorRef.compareAndSet(null, error)) {
                    return;
                }
                if (debug.on()) debug.log("got read error: " + error);
                readScheduler.runOrSchedule();
            }

            final void signalReadable() {
                readScheduler.runOrSchedule();
            }

            /** The body of the task that runs in SequentialScheduler. */
            final void read() {
                // It is important to only call pauseReadEvent() when stopping
                // the scheduler. The event is automatically paused before
                // firing, and trying to pause it again could cause a race
                // condition between this loop, which calls tryDecrementDemand(),
                // and the thread that calls request(n), which will try to resume
                // reading.
                try {
                    while(!readScheduler.isStopped()) {
                        if (completed) return;

                        // make sure we have a subscriber
                        if (handlePending()) {
                            if (debug.on())
                                debug.log("pending subscriber subscribed");
                            return;
                        }

                        // If an error was signaled, we might not be in the
                        // the selector thread, and that is OK, because we
                        // will just call onError and return.
                        ReadSubscription current = subscription;
                        Throwable error = errorRef.get();
                        if (current == null)  {
                            assert error != null;
                            if (debug.on())
                                debug.log("error raised before subscriber subscribed: %s",
                                          (Object)error);
                            return;
                        }
                        TubeSubscriber subscriber = current.subscriber;
                        if (error != null) {
                            completed = true;
                            // safe to pause here because we're finished anyway.
                            pauseReadEvent();
                            if (debug.on())
                                debug.log("Sending error " + error
                                          + " to subscriber " + subscriber);
                            current.errorRef.compareAndSet(null, error);
                            current.signalCompletion();
                            readScheduler.stop();
                            debugState("leaving read() loop with error: ");
                            return;
                        }

                        // If we reach here then we must be in the selector thread.
                        assert client.isSelectorThread();
                        if (demand.tryDecrement()) {
                            // we have demand.
                            try {
                                List<ByteBuffer> bytes = readAvailable();
                                if (bytes == EOF) {
                                    if (!completed) {
                                        if (debug.on()) debug.log("got read EOF");
                                        completed = true;
                                        // safe to pause here because we're finished
                                        // anyway.
                                        pauseReadEvent();
                                        current.signalCompletion();
                                        readScheduler.stop();
                                    }
                                    debugState("leaving read() loop after EOF: ");
                                    return;
                                } else if (Utils.remaining(bytes) > 0) {
                                    // the subscriber is responsible for offloading
                                    // to another thread if needed.
                                    if (debug.on())
                                        debug.log("read bytes: " + Utils.remaining(bytes));
                                    assert !current.completed;
                                    subscriber.onNext(bytes);
                                    // we could continue looping until the demand
                                    // reaches 0. However, that would risk starving
                                    // other connections (bound to other socket
                                    // channels) - as other selected keys activated
                                    // by the selector manager thread might be
                                    // waiting for this event to terminate.
                                    // So resume the read event and return now...
                                    resumeReadEvent();
                                    debugState("leaving read() loop after onNext: ");
                                    return;
                                } else {
                                    // nothing available!
                                    if (debug.on()) debug.log("no more bytes available");
                                    // re-increment the demand and resume the read
                                    // event. This ensures that this loop is
                                    // executed again when the socket becomes
                                    // readable again.
                                    demand.increase(1);
                                    resumeReadEvent();
                                    debugState("leaving read() loop with no bytes");
                                    return;
                                }
                            } catch (Throwable x) {
                                signalError(x);
                                continue;
                            }
                        } else {
                            if (debug.on()) debug.log("no more demand for reading");
                            // the event is paused just after firing, so it should
                            // still be paused here, unless the demand was just
                            // incremented from 0 to n, in which case, the
                            // event will be resumed, causing this loop to be
                            // invoked again when the socket becomes readable:
                            // This is what we want.
                            // Trying to pause the event here would actually
                            // introduce a race condition between this loop and
                            // request(n).
                            debugState("leaving read() loop with no demand");
                            break;
                        }
                    }
                } catch (Throwable t) {
                    if (debug.on()) debug.log("Unexpected exception in read loop", t);
                    signalError(t);
                } finally {
                    handlePending();
                }
            }

            boolean handlePending() {
                ReadSubscription pending = pendingSubscription.getAndSet(null);
                if (pending == null) return false;
                if (debug.on())
                    debug.log("handling pending subscription for %s",
                            pending.subscriber);
                ReadSubscription current = subscription;
                if (current != null && current != pending && !completed) {
                    current.subscriber.dropSubscription();
                }
                if (debug.on()) debug.log("read demand reset to 0");
                subscriptionImpl.demand.reset(); // subscriber will increase demand if it needs to.
                pending.errorRef.compareAndSet(null, errorRef.get());
                if (!readScheduler.isStopped()) {
                    subscription = pending;
                } else {
                    if (debug.on()) debug.log("socket tube is already stopped");
                }
                if (debug.on()) debug.log("calling onSubscribe");
                pending.signalOnSubscribe();
                if (completed) {
                    pending.errorRef.compareAndSet(null, errorRef.get());
                    pending.signalCompletion();
                }
                return true;
            }
        }


        // A repeatable ReadEvent which is paused after firing and can
        // be resumed if required - see SocketFlowEvent;
        final class ReadEvent extends SocketFlowEvent {
            final InternalReadSubscription sub;
            ReadEvent(SocketChannel channel, InternalReadSubscription sub) {
                super(SelectionKey.OP_READ, channel);
                this.sub = sub;
            }
            @Override
            protected final void signalEvent() {
                try {
                    client.eventUpdated(this);
                    sub.signalReadable();
                } catch(Throwable t) {
                    sub.signalError(t);
                }
            }

            @Override
            protected final void signalError(Throwable error) {
                sub.signalError(error);
            }

            @Override
            Logger debug() { return debug; }
        }
    }

    // ===================================================================== //
    //                   Socket Channel Read/Write                           //
    // ===================================================================== //
    static final int MAX_BUFFERS = 3;
    static final List<ByteBuffer> EOF = List.of();
    static final List<ByteBuffer> NOTHING = List.of(Utils.EMPTY_BYTEBUFFER);

    // readAvailable() will read bytes into the 'current' ByteBuffer until
    // the ByteBuffer is full, or 0 or -1 (EOF) is returned by read().
    // When that happens, a slice of the data that has been read so far
    // is inserted into the returned buffer list, and if the current buffer
    // has remaining space, that space will be used to read more data when
    // the channel becomes readable again.
    private volatile ByteBuffer current;
    private List<ByteBuffer> readAvailable() throws IOException {
        ByteBuffer buf = current;
        buf = (buf == null || !buf.hasRemaining())
                ? (current = buffersSource.get()) : buf;
        assert buf.hasRemaining();

        int read;
        int pos = buf.position();
        List<ByteBuffer> list = null;
        while (buf.hasRemaining()) {
            try {
                while ((read = channel.read(buf)) > 0) {
                    if (!buf.hasRemaining())
                        break;
                }
            } catch (IOException x) {
                if (buf.position() == pos && list == null) {
                    // no bytes have been read, just throw...
                    throw x;
                } else {
                    // some bytes have been read, return them and fail next time
                    errorRef.compareAndSet(null, x);
                    read = 0; // ensures outer loop will exit
                }
            }

            // nothing read;
            if (buf.position() == pos) {
                // An empty list signals the end of data, and should only be
                // returned if read == -1. If some data has already been read,
                // then it must be returned. -1 will be returned next time
                // the caller attempts to read something.
                if (list == null) {
                    // nothing read - list was null - return EOF or NOTHING
                    list = read == -1 ? EOF : NOTHING;
                }
                break;
            }

            // check whether this buffer has still some free space available.
            // if so, we will keep it for the next round.
            final boolean hasRemaining = buf.hasRemaining();

            // creates a slice to add to the list
            int limit = buf.limit();
            buf.limit(buf.position());
            buf.position(pos);
            ByteBuffer slice = buf.slice();

            // restore buffer state to what it was before creating the slice
            buf.position(buf.limit());
            buf.limit(limit);

            // add the buffer to the list
            list = addToList(list, slice.asReadOnlyBuffer());
            if (read <= 0 || list.size() == MAX_BUFFERS) {
                break;
            }

            buf = hasRemaining ? buf : (current = buffersSource.get());
            pos = buf.position();
            assert buf.hasRemaining();
        }
        return list;
    }

    private <T> List<T> addToList(List<T> list, T item) {
        int size = list == null ? 0 : list.size();
        switch (size) {
            case 0: return List.of(item);
            case 1: return List.of(list.get(0), item);
            case 2: return List.of(list.get(0), list.get(1), item);
            default: // slow path if MAX_BUFFERS > 3
                ArrayList<T> res = new ArrayList<>(list);
                res.add(item);
                return res;
        }
    }

    private long writeAvailable(List<ByteBuffer> bytes) throws IOException {
        ByteBuffer[] srcs = bytes.toArray(Utils.EMPTY_BB_ARRAY);
        final long remaining = Utils.remaining(srcs);
        long written = 0;
        while (remaining > written) {
            try {
                long w = channel.write(srcs);
                assert w >= 0 : "negative number of bytes written:" + w;
                if (w == 0) {
                    break;
                }
                written += w;
            } catch (IOException x) {
                if (written == 0) {
                    // no bytes were written just throw
                    throw x;
                } else {
                    // return how many bytes were written, will fail next time
                    break;
                }
            }
        }
        return written;
    }

    private void resumeEvent(SocketFlowEvent event,
                             Consumer<Throwable> errorSignaler) {
        boolean registrationRequired;
        synchronized(lock) {
            registrationRequired = !event.registered();
            event.resume();
        }
        try {
            if (registrationRequired) {
                client.registerEvent(event);
             } else {
                client.eventUpdated(event);
            }
        } catch(Throwable t) {
            errorSignaler.accept(t);
        }
   }

    private void pauseEvent(SocketFlowEvent event,
                            Consumer<Throwable> errorSignaler) {
        synchronized(lock) {
            event.pause();
        }
        try {
            client.eventUpdated(event);
        } catch(Throwable t) {
            errorSignaler.accept(t);
        }
    }

    @Override
    public void connectFlows(TubePublisher writePublisher,
                             TubeSubscriber readSubscriber) {
        if (debug.on()) debug.log("connecting flows");
        this.subscribe(readSubscriber);
        writePublisher.subscribe(this);
    }


    @Override
    public String toString() {
        return dbgString();
    }

    final String dbgString() {
        return "SocketTube("+id+")";
    }
}
