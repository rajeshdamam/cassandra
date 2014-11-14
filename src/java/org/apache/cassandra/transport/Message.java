/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.transport;

import java.util.ArrayList;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import org.apache.cassandra.concurrent.CustomRxScheduler;
import org.apache.cassandra.concurrent.NettyRxScheduler;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.transport.messages.*;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.utils.JVMStabilityInspector;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.observers.Subscribers;
import rx.schedulers.Schedulers;

/**
 * A message from the CQL binary protocol.
 */
public abstract class Message
{
    protected static final Logger logger = LoggerFactory.getLogger(Message.class);

    /**
     * When we encounter an unexpected IOException we look for these {@link Throwable#getMessage() messages}
     * (because we have no better way to distinguish) and log them at DEBUG rather than INFO, since they
     * are generally caused by unclean client disconnects rather than an actual problem.
     */
    private static final Set<String> ioExceptionsAtDebugLevel = ImmutableSet.<String>builder().
            add("Connection reset by peer").
            add("Broken pipe").
            add("Connection timed out").
            build();

    public interface Codec<M extends Message> extends CBCodec<M> {}

    public enum Direction
    {
        REQUEST, RESPONSE;

        public static Direction extractFromVersion(int versionWithDirection)
        {
            return (versionWithDirection & 0x80) == 0 ? REQUEST : RESPONSE;
        }

        public int addToVersion(int rawVersion)
        {
            return this == REQUEST ? (rawVersion & 0x7F) : (rawVersion | 0x80);
        }
    }

    public enum Type
    {
        ERROR          (0,  Direction.RESPONSE, ErrorMessage.codec),
        STARTUP        (1,  Direction.REQUEST,  StartupMessage.codec),
        READY          (2,  Direction.RESPONSE, ReadyMessage.codec),
        AUTHENTICATE   (3,  Direction.RESPONSE, AuthenticateMessage.codec),
        CREDENTIALS    (4,  Direction.REQUEST,  CredentialsMessage.codec),
        OPTIONS        (5,  Direction.REQUEST,  OptionsMessage.codec),
        SUPPORTED      (6,  Direction.RESPONSE, SupportedMessage.codec),
        QUERY          (7,  Direction.REQUEST,  QueryMessage.codec),
        RESULT         (8,  Direction.RESPONSE, ResultMessage.codec),
        PREPARE        (9,  Direction.REQUEST,  PrepareMessage.codec),
        EXECUTE        (10, Direction.REQUEST,  ExecuteMessage.codec),
        REGISTER       (11, Direction.REQUEST,  RegisterMessage.codec),
        EVENT          (12, Direction.RESPONSE, EventMessage.codec),
        BATCH          (13, Direction.REQUEST,  BatchMessage.codec),
        AUTH_CHALLENGE (14, Direction.RESPONSE, AuthChallenge.codec),
        AUTH_RESPONSE  (15, Direction.REQUEST,  AuthResponse.codec),
        AUTH_SUCCESS   (16, Direction.RESPONSE, AuthSuccess.codec);

        public final int opcode;
        public final Direction direction;
        public final Codec<?> codec;

        private static final Type[] opcodeIdx;
        static
        {
            int maxOpcode = -1;
            for (Type type : Type.values())
                maxOpcode = Math.max(maxOpcode, type.opcode);
            opcodeIdx = new Type[maxOpcode + 1];
            for (Type type : Type.values())
            {
                if (opcodeIdx[type.opcode] != null)
                    throw new IllegalStateException("Duplicate opcode");
                opcodeIdx[type.opcode] = type;
            }
        }

        private Type(int opcode, Direction direction, Codec<?> codec)
        {
            this.opcode = opcode;
            this.direction = direction;
            this.codec = codec;
        }

        public static Type fromOpcode(int opcode, Direction direction)
        {
            if (opcode >= opcodeIdx.length)
                throw new ProtocolException(String.format("Unknown opcode %d", opcode));
            Type t = opcodeIdx[opcode];
            if (t == null)
                throw new ProtocolException(String.format("Unknown opcode %d", opcode));
            if (t.direction != direction)
                throw new ProtocolException(String.format("Wrong protocol direction (expected %s, got %s) for opcode %d (%s)",
                                                          t.direction,
                                                          direction,
                                                          opcode,
                                                          t));
            return t;
        }
    }

    public final Type type;
    protected Connection connection;
    private int streamId;
    private Frame sourceFrame;

    protected Message(Type type)
    {
        this.type = type;
    }

    public void attach(Connection connection)
    {
        this.connection = connection;
    }

    public Connection connection()
    {
        return connection;
    }

    public Message setStreamId(int streamId)
    {
        this.streamId = streamId;
        return this;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public void setSourceFrame(Frame sourceFrame)
    {
        this.sourceFrame = sourceFrame;
    }

    public Frame getSourceFrame()
    {
        return sourceFrame;
    }

    public static abstract class Request extends Message
    {
        protected boolean tracingRequested;

        protected Request(Type type)
        {
            super(type);

            if (type.direction != Direction.REQUEST)
                throw new IllegalArgumentException();
        }

        public abstract Observable<? extends Response> execute(QueryState queryState);

        public void setTracingRequested()
        {
            this.tracingRequested = true;
        }

        public boolean isTracingRequested()
        {
            return tracingRequested;
        }
    }

    public static abstract class Response extends Message
    {
        protected UUID tracingId;

        protected Response(Type type)
        {
            super(type);

            if (type.direction != Direction.RESPONSE)
                throw new IllegalArgumentException();
        }

        public Message setTracingId(UUID tracingId)
        {
            this.tracingId = tracingId;
            return this;
        }

        public UUID getTracingId()
        {
            return tracingId;
        }
    }

    @ChannelHandler.Sharable
    public static class ProtocolDecoder extends MessageToMessageDecoder<Frame>
    {
        public void decode(ChannelHandlerContext ctx, Frame frame, List results)
        {
            boolean isRequest = frame.header.type.direction == Direction.REQUEST;
            boolean isTracing = frame.header.flags.contains(Frame.Header.Flag.TRACING);

            UUID tracingId = isRequest || !isTracing ? null : CBUtil.readUUID(frame.body);

            try
            {
                Message message = frame.header.type.codec.decode(frame.body, frame.header.version);
                message.setStreamId(frame.header.streamId);
                message.setSourceFrame(frame);

                if (isRequest)
                {
                    assert message instanceof Request;
                    Request req = (Request)message;
                    Connection connection = ctx.channel().attr(Connection.attributeKey).get();
                    req.attach(connection);
                    if (isTracing)
                        req.setTracingRequested();
                }
                else
                {
                    assert message instanceof Response;
                    if (isTracing)
                        ((Response)message).setTracingId(tracingId);
                }

                results.add(message);
            }
            catch (Throwable ex)
            {
                frame.release();
                // Remember the streamId
                throw ErrorMessage.wrap(ex, frame.header.streamId);
            }
        }
    }

    @ChannelHandler.Sharable
    public static class ProtocolEncoder extends MessageToMessageEncoder<Message>
    {
        public void encode(ChannelHandlerContext ctx, Message message, List results)
        {
            Connection connection = ctx.channel().attr(Connection.attributeKey).get();
            // The only case the connection can be null is when we send the initial STARTUP message (client side thus)
            int version = connection == null ? Server.CURRENT_VERSION : connection.getVersion();

            EnumSet<Frame.Header.Flag> flags = EnumSet.noneOf(Frame.Header.Flag.class);

            Codec<Message> codec = (Codec<Message>)message.type.codec;
            try
            {
                int messageSize = codec.encodedSize(message, version);
                ByteBuf body;
                if (message instanceof Response)
                {
                    UUID tracingId = ((Response)message).getTracingId();
                    if (tracingId != null)
                    {
                        body = CBUtil.allocator.buffer(CBUtil.sizeOfUUID(tracingId) + messageSize);
                        CBUtil.writeUUID(tracingId, body);
                        flags.add(Frame.Header.Flag.TRACING);
                    }
                    else
                    {
                        body = CBUtil.allocator.buffer(messageSize);
                    }
                }
                else
                {
                    assert message instanceof Request;
                    body = CBUtil.allocator.buffer(messageSize);
                    if (((Request)message).isTracingRequested())
                        flags.add(Frame.Header.Flag.TRACING);
                }

                try
                {
                    codec.encode(message, body, version);
                }
                catch (Throwable e)
                {
                    body.release();
                    throw e;
                }

                results.add(Frame.create(message.type, message.getStreamId(), version, flags, body));
            }
            catch (Throwable e)
            {
                throw ErrorMessage.wrap(e, message.getStreamId());
            }
        }
    }

    @ChannelHandler.Sharable
    public static class Dispatcher extends SimpleChannelInboundHandler<Request>
    {
        private static final ConcurrentMap<EventLoop, ConcurrentLinkedQueue<Pair<Response,Frame>>> flusherLookup = new ConcurrentHashMap<>();

        public Dispatcher()
        {
            super(false);
        }

        @Override
        public void channelRead0(final ChannelHandlerContext ctx, final Request request)
        {

            final ServerConnection connection;

            assert request.connection() instanceof ServerConnection;
            connection = (ServerConnection) request.connection();
            final QueryState qstate = connection.validateNewMessage(request.type, connection.getVersion(), request.getStreamId());

            logger.debug("Received: {}, v={}", request, connection.getVersion());

            Scheduler.Worker worker = CustomRxScheduler.instance.createWorker();

            //Schedule the work on our compute scheduler
            worker.schedule(new Action0()
            {
                @Override
                public void call()
                {
                    final Observable<? extends Response> responseObs = request.execute(qstate);

                    final EventLoop loop = ctx.channel().eventLoop();
                    ConcurrentLinkedQueue<Pair<Response, Frame>> current = flusherLookup.get(loop);
                    if (current == null)
                    {
                        ConcurrentLinkedQueue<Pair<Response, Frame>> alt = flusherLookup.putIfAbsent(loop, current = new ConcurrentLinkedQueue<>());
                        if (alt != null)
                        {
                            current = alt;
                        }
                    }

                    final ConcurrentLinkedQueue<Pair<Response, Frame>> currentFinal = current;


                    responseObs.subscribe(
                            new Action1<Response>()
                            {
                                @Override
                                public void call(Response response)
                                {
                                    response.setStreamId(request.getStreamId());
                                    response.attach(connection);
                                    connection.applyStateTransition(request.type, response.type);

                                    logger.debug("Responding: {}, v={}", response, connection.getVersion());

                                    currentFinal.add(Pair.create(response, request.getSourceFrame()));
                                }
                            },
                            new Action1<Throwable>()
                            {
                                @Override
                                public void call(Throwable t)
                                {
                                    JVMStabilityInspector.inspectThrowable(t);
                                    UnexpectedChannelExceptionHandler handler = new UnexpectedChannelExceptionHandler(ctx.channel(), true);
                                    currentFinal.add(Pair.create((Response) ErrorMessage.fromException(t, handler).setStreamId(request.getStreamId()), request.getSourceFrame()));
                                }
                            },
                            new Action0()
                            {
                                @Override
                                public void call()
                                {
                                    loop.schedule(new Runnable()
                                    {
                                        @Override
                                        public void run()
                                        {
                                            List<Frame> frames = null;

                                            Pair<Response, Frame> pair;
                                            while (null != (pair = currentFinal.poll()))
                                            {
                                                ctx.write(pair.left, ctx.voidPromise());

                                                if (frames == null)
                                                    frames = new ArrayList<>();

                                                frames.add(pair.right);
                                            }

                                            if (frames != null)
                                            {
                                                ctx.flush();
                                                for (Frame frame : frames)
                                                    frame.release();
                                            }
                                        }
                                    }, 10000, TimeUnit.NANOSECONDS);
                                }
                            }
                    );
                }
            });

        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, Throwable cause)
        throws Exception
        {
            if (ctx.channel().isOpen())
            {
                UnexpectedChannelExceptionHandler handler = new UnexpectedChannelExceptionHandler(ctx.channel(), false);
                ChannelFuture future = ctx.writeAndFlush(ErrorMessage.fromException(cause, handler));
                // On protocol exception, close the channel as soon as the message have been sent
                if (cause instanceof ProtocolException)
                {
                    future.addListener(new ChannelFutureListener() {
                        public void operationComplete(ChannelFuture future) {
                            ctx.close();
                        }
                    });
                }
            }
        }
    }

    /**
     * Include the channel info in the logged information for unexpected errors, and (if {@link #alwaysLogAtError} is
     * false then choose the log level based on the type of exception (some are clearly client issues and shouldn't be
     * logged at server ERROR level)
     */
    static final class UnexpectedChannelExceptionHandler implements Predicate<Throwable>
    {
        private final Channel channel;
        private final boolean alwaysLogAtError;

        UnexpectedChannelExceptionHandler(Channel channel, boolean alwaysLogAtError)
        {
            this.channel = channel;
            this.alwaysLogAtError = alwaysLogAtError;
        }

        @Override
        public boolean apply(Throwable exception)
        {
            String message;
            try
            {
                message = "Unexpected exception during request; channel = " + channel;
            }
            catch (Exception ignore)
            {
                // We don't want to make things worse if String.valueOf() throws an exception
                message = "Unexpected exception during request; channel = <unprintable>";
            }

            if (!alwaysLogAtError && exception instanceof IOException)
            {
                if (ioExceptionsAtDebugLevel.contains(exception.getMessage()))
                {
                    // Likely unclean client disconnects
                    logger.debug(message, exception);
                }
                else
                {
                    // Generally unhandled IO exceptions are network issues, not actual ERRORS
                    logger.info(message, exception);
                }
            }
            else
            {
                // Anything else is probably a bug in server of client binary protocol handling
                logger.error(message, exception);
            }

            // We handled the exception.
            return true;
        }
    }
}
