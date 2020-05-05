/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.api;

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpApiConversions.ServiceAdapterHolder;
import io.servicetalk.transport.api.ConnectionAcceptor;
import io.servicetalk.transport.api.ConnectionAcceptorFactory;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.api.ServiceTalkSocketOptions;

import org.slf4j.event.Level;

import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import static io.servicetalk.http.api.BlockingUtils.blockingInvocation;
import static io.servicetalk.http.api.HttpApiConversions.toStreamingHttpService;
import static io.servicetalk.http.api.HttpExecutionStrategies.defaultStrategy;
import static io.servicetalk.http.api.HttpExecutionStrategyInfluencer.defaultStreamingInfluencer;
import static io.servicetalk.http.api.StrategyInfluencerAwareConversions.toConditionalServiceFilterFactory;
import static io.servicetalk.transport.api.ConnectionAcceptor.ACCEPT_ALL;

/**
 * A builder for building HTTP Servers.
 */
public abstract class HttpServerBuilder {

    @Nullable
    private ConnectionAcceptorFactory connectionAcceptorFactory;
    @Nullable
    private StreamingHttpServiceFilterFactory serviceFilter;
    private HttpExecutionStrategy strategy = defaultStrategy();
    private final StrategyInfluencerChainBuilder influencerChainBuilder = new StrategyInfluencerChainBuilder();
    private boolean drainRequestPayloadBody = true;

    /**
     * Configurations of various HTTP protocol versions.
     * <p>
     * <b>Note:</b> the order of specified protocols will reflect on priorities for ALPN in case the connections are
     * {@link #secure() secured}.
     *
     * @param protocols {@link HttpProtocolConfig} for each protocol that should be supported.
     * @return {@code this}.
     */
    public abstract HttpServerBuilder protocols(HttpProtocolConfig... protocols);

    /**
     * Sets the maximum queue length for incoming connection indications (a request to connect) is set to the backlog
     * parameter. If a connection indication arrives when the queue is full, the connection may time out.
     *
     * @param backlog the backlog to use when accepting connections.
     * @return {@code this}.
     */
    public abstract HttpServerBuilder backlog(int backlog);

    /**
     * Initiates security configuration for this server. Calling any {@code commit} method on the returned
     * {@link HttpServerSecurityConfigurator} will commit the configuration.
     * <p>
     * Additionally use {@link #secure(String...)} to define configurations for specific
     * <a href="https://tools.ietf.org/html/rfc6066#section-3">SNI</a> hostnames. If such configuration is additionally
     * defined then configuration using this method is used as default if the hostname does not match any of the
     * specified hostnames.
     *
     * @return {@link HttpServerSecurityConfigurator} to configure security for this server. It is
     * mandatory to call any one of the {@code commit} methods after all configuration is done.
     */
    public abstract HttpServerSecurityConfigurator secure();

    /**
     * Initiates security configuration for this server for the passed {@code sniHostnames}.
     * Calling any {@code commit} method on the returned {@link HttpServerSecurityConfigurator} will commit the
     * configuration.
     * <p>
     * When using this method, it is mandatory to also define the default configuration using {@link #secure()} which
     * is used when the hostname does not match any of the specified {@code sniHostnames}.
     *
     * @param sniHostnames <a href="https://tools.ietf.org/html/rfc6066#section-3">SNI</a> hostnames for which this
     * config is being defined.
     * @return {@link HttpServerSecurityConfigurator} to configure security for this server. It is
     * mandatory to call any one of the {@code commit} methods after all configuration is done.
     */
    public abstract HttpServerSecurityConfigurator secure(String... sniHostnames);

    /**
     * Adds a {@link SocketOption} that is applied.
     *
     * @param <T> the type of the value.
     * @param option the option to apply.
     * @param value the value.
     * @return this.
     * @see StandardSocketOptions
     * @see ServiceTalkSocketOptions
     */
    public abstract <T> HttpServerBuilder socketOption(SocketOption<T> option, T value);

    /**
     * Enables wire-logging for this server.
     * <p>
     * All wire events will be logged at {@link Level#TRACE TRACE} level.
     *
     * @param loggerName The name of the logger to log wire events.
     * @return {@code this}.
     */
    public abstract HttpServerBuilder enableWireLogging(String loggerName);

    /**
     * Disables automatic consumption of request {@link StreamingHttpRequest#payloadBody() payload body} when it is not
     * consumed by the service.
     * <p>
     * For <a href="https://tools.ietf.org/html/rfc7230#section-6.3">persistent HTTP connections</a> it is required to
     * eventually consume the entire request payload to enable reading of the next request. This is required because
     * requests are pipelined for HTTP/1.1, so if the previous request is not completely read, next request can not be
     * read from the socket. For cases when there is a possibility that user may forget to consume request payload,
     * ServiceTalk automatically consumes request payload body. This automatic consumption behavior may create some
     * overhead and can be disabled using this method when it is guaranteed that all request paths consumes all request
     * payloads eventually. An example of guaranteed consumption are {@link HttpRequest non-streaming APIs}.
     *
     * @return {@code this}.
     */
    public final HttpServerBuilder disableDrainingRequestPayloadBody() {
        this.drainRequestPayloadBody = false;
        return this;
    }

    /**
     * Appends the filter to the chain of filters used to decorate the {@link ConnectionAcceptor} used by this builder.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.appendConnectionAcceptorFilter(filter1).appendConnectionAcceptorFilter(filter2).
     *     appendConnectionAcceptorFilter(filter3)
     * </pre>
     * accepting a connection by a filter wrapped by this filter chain, the order of invocation of these filters will
     * be:
     * <pre>
     *     filter1 =&gt; filter2 =&gt; filter3
     * </pre>
     *
     * @param factory {@link ConnectionAcceptorFactory} to append. Lifetime of this
     * {@link ConnectionAcceptorFactory} is managed by this builder and the server started thereof.
     * @return {@code this}
     */
    public final HttpServerBuilder appendConnectionAcceptorFilter(final ConnectionAcceptorFactory factory) {
        if (connectionAcceptorFactory == null) {
            connectionAcceptorFactory = factory;
        } else {
            connectionAcceptorFactory = connectionAcceptorFactory.append(factory);
        }
        return this;
    }

    /**
     * Appends the filter to the chain of filters used to decorate the {@link StreamingHttpService} used by this
     * builder.
     * <p>
     * Note this method will be used to decorate the {@link StreamingHttpService} passed to
     * {@link #listenStreaming(StreamingHttpService)} before it is used by the server.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.append(filter1).append(filter2).append(filter3)
     * </pre>
     * accepting a request by a service wrapped by this filter chain, the order of invocation of these filters will be:
     * <pre>
     *     filter1 =&gt; filter2 =&gt; filter3 =&gt; service
     * </pre>
     *
     * @param factory {@link StreamingHttpServiceFilterFactory} to append.
     * @return {@code this}
     */
    public final HttpServerBuilder appendServiceFilter(final StreamingHttpServiceFilterFactory factory) {
        if (serviceFilter == null) {
            serviceFilter = factory;
        } else {
            serviceFilter = serviceFilter.append(factory);
        }
        if (!influencerChainBuilder.appendIfInfluencer(factory)) {
            influencerChainBuilder.append(defaultStreamingInfluencer());
        }
        return this;
    }

    /**
     * Appends the filter to the chain of filters used to decorate the {@link StreamingHttpService} used by this
     * builder, for every request that passes the provided {@link Predicate}.
     * <p>
     * Note this method will be used to decorate the {@link StreamingHttpService} passed to
     * {@link #listenStreaming(StreamingHttpService)} before it is used by the server.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.append(filter1).append(filter2).append(filter3)
     * </pre>
     * accepting a request by a service wrapped by this filter chain, the order of invocation of these filters will be:
     * <pre>
     *     filter1 =&gt; filter2 =&gt; filter3 =&gt; service
     * </pre>
     *
     * @param predicate the {@link Predicate} to test if the filter must be applied.
     * @param factory {@link StreamingHttpServiceFilterFactory} to append.
     * @return {@code this}
     */
    public final HttpServerBuilder appendServiceFilter(final Predicate<StreamingHttpRequest> predicate,
                                                       final StreamingHttpServiceFilterFactory factory) {
        appendServiceFilter(toConditionalServiceFilterFactory(predicate, factory));
        return this;
    }

    /**
     * Sets the {@link IoExecutor} to be used by this server.
     *
     * @param ioExecutor {@link IoExecutor} to use.
     * @return {@code this}.
     */
    public abstract HttpServerBuilder ioExecutor(IoExecutor ioExecutor);

    /**
     * Sets the {@link BufferAllocator} to be used by this server.
     *
     * @param allocator {@link BufferAllocator} to use.
     * @return {@code this}.
     */
    public abstract HttpServerBuilder bufferAllocator(BufferAllocator allocator);

    /**
     * Sets the {@link HttpExecutionStrategy} to be used by this server.
     *
     * @param strategy {@link HttpExecutionStrategy} to use by this server.
     * @return {@code this}.
     */
    public final HttpServerBuilder executionStrategy(HttpExecutionStrategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link ServerContext} by blocking the calling thread until the server is successfully started or
     * throws an {@link Exception} if the server could not be started.
     * @throws Exception if the server could not be started.
     */
    public final ServerContext listenAndAwait(final HttpService service) throws Exception {
        return blockingInvocation(listen(service));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param handler Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link ServerContext} by blocking the calling thread until the server is successfully started or
     * throws an {@link Exception} if the server could not be started.
     * @throws Exception if the server could not be started.
     */
    public final ServerContext listenStreamingAndAwait(final StreamingHttpService handler) throws Exception {
        return blockingInvocation(listenStreaming(handler));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link ServerContext} by blocking the calling thread until the server is successfully started or
     * throws an {@link Exception} if the server could not be started.
     * @throws Exception if the server could not be started.
     */
    public final ServerContext listenBlockingAndAwait(final BlockingHttpService service) throws Exception {
        return blockingInvocation(listenBlocking(service));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param handler Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link ServerContext} by blocking the calling thread until the server is successfully started or
     * throws an {@link Exception} if the server could not be started.
     * @throws Exception if the server could not be started.
     */
    public final ServerContext listenBlockingStreamingAndAwait(
            final BlockingStreamingHttpService handler) throws Exception {
        return blockingInvocation(listenBlockingStreaming(handler));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link Single} that completes when the server is successfully started or terminates with an error if
     * the server could not be started.
     */
    public final Single<ServerContext> listen(final HttpService service) {
        influencerChainBuilder.prependIfInfluencer(service);
        return listenForAdapter(toStreamingHttpService(service, influencerChainBuilder.build(strategy)));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link Single} that completes when the server is successfully started or terminates with an error if
     * the server could not be started.
     */
    public final Single<ServerContext> listenStreaming(final StreamingHttpService service) {
        return listenForService(service, strategy);
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link Single} that completes when the server is successfully started or terminates with an error if
     * the server could not be started.
     */
    public final Single<ServerContext> listenBlocking(final BlockingHttpService service) {
        influencerChainBuilder.prependIfInfluencer(service);
        return listenForAdapter(toStreamingHttpService(service, influencerChainBuilder.build(strategy)));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param service Service invoked for every request received by this server. The returned {@link ServerContext}
     * manages the lifecycle of the {@code service}, ensuring it is closed when the {@link ServerContext} is closed.
     * @return A {@link Single} that completes when the server is successfully started or terminates with an error if
     * the server could not be started.
     */
    public final Single<ServerContext> listenBlockingStreaming(final BlockingStreamingHttpService service) {
        influencerChainBuilder.prependIfInfluencer(service);
        return listenForAdapter(toStreamingHttpService(service, influencerChainBuilder.build(strategy)));
    }

    /**
     * Starts this server and returns the {@link ServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this should result in a socket bind/listen on {@code address}.
     *
     * @param connectionAcceptor {@link ConnectionAcceptor} to use for the server.
     * @param service {@link StreamingHttpService} to use for the server.
     * @param strategy the {@link HttpExecutionStrategy} to use for the service.
     * @param drainRequestPayloadBody if {@code true} the server implementation should automatically subscribe and
     * ignore the {@link StreamingHttpRequest#payloadBody() payload body} of incoming requests.
     * @return A {@link Single} that completes when the server is successfully started or terminates with an error if
     * the server could not be started.
     */
    protected abstract Single<ServerContext> doListen(@Nullable ConnectionAcceptor connectionAcceptor,
                                                      StreamingHttpService service,
                                                      HttpExecutionStrategy strategy,
                                                      boolean drainRequestPayloadBody);

    private Single<ServerContext> listenForAdapter(ServiceAdapterHolder adapterHolder) {
        return listenForService(adapterHolder.adaptor(), adapterHolder.serviceInvocationStrategy());
    }

    private Single<ServerContext> listenForService(StreamingHttpService rawService, HttpExecutionStrategy strategy) {
        ConnectionAcceptor connectionAcceptor = connectionAcceptorFactory == null ? null :
                connectionAcceptorFactory.create(ACCEPT_ALL);
        StreamingHttpService filteredService = serviceFilter != null ? serviceFilter.create(rawService) : rawService;
        return doListen(connectionAcceptor, filteredService, strategy, drainRequestPayloadBody);
    }
}