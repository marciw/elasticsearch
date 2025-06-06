/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.repositories.azure;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.ProxyOptions;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.OperationPurpose;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.repositories.azure.executors.ReactorScheduledExecutorService;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.NettyAllocator;

import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import static org.elasticsearch.repositories.azure.AzureRepositoryPlugin.NETTY_EVENT_LOOP_THREAD_POOL_NAME;
import static org.elasticsearch.repositories.azure.AzureRepositoryPlugin.REPOSITORY_THREAD_POOL_NAME;

class AzureClientProvider extends AbstractLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(AzureClientProvider.class);

    private static final TimeValue DEFAULT_CONNECTION_TIMEOUT = TimeValue.timeValueSeconds(30);
    private static final TimeValue DEFAULT_MAX_CONNECTION_IDLE_TIME = TimeValue.timeValueSeconds(60);
    private static final int DEFAULT_MAX_CONNECTIONS = 50;
    private static final int DEFAULT_EVENT_LOOP_THREAD_COUNT = 1;
    private static final int PENDING_CONNECTION_QUEUE_SIZE = -1; // see ConnectionProvider.ConnectionPoolSpec.pendingAcquireMaxCount

    /**
     * Test-only system property to disable instance discovery for workload identity authentication in the Azure SDK.
     * This is necessary since otherwise the SDK will attempt to verify identities via a real host
     * (e.g. <a href="https://login.microsoft.com/">https://login.microsoft.com/</a>) for
     * workload identity authentication. This is incompatible with our test environment.
     */
    private static final boolean DISABLE_INSTANCE_DISCOVERY = System.getProperty(
        "tests.azure.credentials.disable_instance_discovery",
        "false"
    ).equals("true");

    static final Setting<Integer> EVENT_LOOP_THREAD_COUNT = Setting.intSetting(
        "repository.azure.http_client.event_loop_executor_thread_count",
        DEFAULT_EVENT_LOOP_THREAD_COUNT,
        1,
        Setting.Property.NodeScope
    );

    static final Setting<Integer> MAX_OPEN_CONNECTIONS = Setting.intSetting(
        "repository.azure.http_client.max_open_connections",
        DEFAULT_MAX_CONNECTIONS,
        1,
        Setting.Property.NodeScope
    );

    static final Setting<TimeValue> OPEN_CONNECTION_TIMEOUT = Setting.timeSetting(
        "repository.azure.http_client.connection_timeout",
        DEFAULT_CONNECTION_TIMEOUT,
        Setting.Property.NodeScope
    );

    static final Setting<TimeValue> MAX_IDLE_TIME = Setting.timeSetting(
        "repository.azure.http_client.connection_max_idle_time",
        DEFAULT_MAX_CONNECTION_IDLE_TIME,
        Setting.Property.NodeScope
    );

    private final ThreadPool threadPool;
    private final String reactorExecutorName;
    private final EventLoopGroup eventLoopGroup;
    private final ConnectionProvider connectionProvider;
    private final ByteBufAllocator byteBufAllocator;
    private final LoopResources nioLoopResources;
    private final int multipartUploadMaxConcurrency;
    private volatile boolean closed = false;

    AzureClientProvider(
        ThreadPool threadPool,
        String reactorExecutorName,
        EventLoopGroup eventLoopGroup,
        ConnectionProvider connectionProvider,
        ByteBufAllocator byteBufAllocator,
        int multipartUploadMaxConcurrency
    ) {
        this.threadPool = threadPool;
        this.reactorExecutorName = reactorExecutorName;
        this.eventLoopGroup = eventLoopGroup;
        this.connectionProvider = connectionProvider;
        this.byteBufAllocator = byteBufAllocator;
        // The underlying http client uses this as part of the connection pool key,
        // hence we need to use the same instance across all the client instances
        // to avoid creating multiple connection pools.
        this.nioLoopResources = useNative -> eventLoopGroup;
        this.multipartUploadMaxConcurrency = multipartUploadMaxConcurrency;
    }

    static int eventLoopThreadsFromSettings(Settings settings) {
        return EVENT_LOOP_THREAD_COUNT.get(settings);
    }

    static AzureClientProvider create(ThreadPool threadPool, Settings settings) {
        final ExecutorService eventLoopExecutor = threadPool.executor(NETTY_EVENT_LOOP_THREAD_POOL_NAME);
        // Most of the code that needs special permissions (i.e. jackson serializers generation) is executed
        // in the event loop executor. That's the reason why we should provide an executor that allows the
        // execution of privileged code
        final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(eventLoopThreadsFromSettings(settings), eventLoopExecutor);

        final TimeValue openConnectionTimeout = OPEN_CONNECTION_TIMEOUT.get(settings);
        final TimeValue maxIdleTime = MAX_IDLE_TIME.get(settings);

        ConnectionProvider provider = ConnectionProvider.builder("azure-sdk-connection-pool")
            .maxConnections(MAX_OPEN_CONNECTIONS.get(settings))
            .pendingAcquireMaxCount(PENDING_CONNECTION_QUEUE_SIZE) // This determines the max outstanding queued requests
            .pendingAcquireTimeout(Duration.ofMillis(openConnectionTimeout.millis()))
            .maxIdleTime(Duration.ofMillis(maxIdleTime.millis()))
            .build();

        // Just to verify that this executor exists
        threadPool.executor(REPOSITORY_THREAD_POOL_NAME);
        return new AzureClientProvider(
            threadPool,
            REPOSITORY_THREAD_POOL_NAME,
            eventLoopGroup,
            provider,
            NettyAllocator.getAllocator(),
            threadPool.info(REPOSITORY_THREAD_POOL_NAME).getMax()
        );
    }

    AzureBlobServiceClient createClient(
        AzureStorageSettings settings,
        LocationMode locationMode,
        RequestRetryOptions retryOptions,
        ProxyOptions proxyOptions,
        RequestMetricsHandler requestMetricsHandler,
        OperationPurpose purpose
    ) {
        if (closed) {
            throw new IllegalStateException("AzureClientProvider is already closed");
        }

        reactor.netty.http.client.HttpClient nettyHttpClient = reactor.netty.http.client.HttpClient.create(connectionProvider);
        nettyHttpClient = nettyHttpClient.port(80)
            .wiretap(false)
            .resolver(DefaultAddressResolverGroup.INSTANCE)
            .runOn(nioLoopResources)
            .option(ChannelOption.ALLOCATOR, byteBufAllocator);

        final HttpClient httpClient = new NettyAsyncHttpClientBuilder(nettyHttpClient).disableBufferCopy(true).proxy(proxyOptions).build();

        final String connectionString = settings.getConnectString();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder().connectionString(connectionString)
            .httpClient(httpClient)
            .retryOptions(retryOptions);

        if (settings.hasCredentials() == false) {
            final DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder().executorService(eventLoopGroup);
            if (DISABLE_INSTANCE_DISCOVERY) {
                credentialBuilder.disableInstanceDiscovery();
            }
            builder.credential(credentialBuilder.build());
        }

        if (requestMetricsHandler != null) {
            builder.addPolicy(new RequestMetricsTracker(purpose, requestMetricsHandler));
            builder.addPolicy(RetryMetricsTracker.INSTANCE);
        }

        if (locationMode.isSecondary()) {
            String secondaryUri = settings.getStorageEndpoint().secondaryURI();
            if (secondaryUri == null) {
                throw new IllegalArgumentException(
                    "Unable to configure an AzureClient using a secondary location without a secondary endpoint"
                );
            }

            builder.endpoint(secondaryUri);
        }

        BlobServiceClient blobServiceClient = builder.buildClient();
        BlobServiceAsyncClient asyncClient = builder.buildAsyncClient();
        return new AzureBlobServiceClient(blobServiceClient, asyncClient, settings.getMaxRetries(), byteBufAllocator);
    }

    @Override
    protected void doStart() {
        ReactorScheduledExecutorService executorService = new ReactorScheduledExecutorService(threadPool, reactorExecutorName);

        // The only way to configure the schedulers used by the SDK is to inject a new global factory. This is a bit ugly...
        // See https://github.com/Azure/azure-sdk-for-java/issues/17272 for a feature request to avoid this need.
        Schedulers.setFactory(new Schedulers.Factory() {
            @Override
            public Scheduler newParallel(int parallelism, ThreadFactory threadFactory) {
                return Schedulers.fromExecutor(executorService);
            }

            @Override
            public Scheduler newElastic(int ttlSeconds, ThreadFactory threadFactory) {
                return Schedulers.fromExecutor(executorService);
            }

            @Override
            public Scheduler newBoundedElastic(int threadCap, int queuedTaskCap, ThreadFactory threadFactory, int ttlSeconds) {
                return Schedulers.fromExecutor(executorService);
            }

            @Override
            public Scheduler newSingle(ThreadFactory threadFactory) {
                return Schedulers.fromExecutor(executorService);
            }
        });
    }

    @Override
    protected void doStop() {
        closed = true;
        connectionProvider.dispose();
        eventLoopGroup.shutdownGracefully();
        Schedulers.resetFactory();
    }

    @Override
    protected void doClose() {}

    public int getMultipartUploadMaxConcurrency() {
        return multipartUploadMaxConcurrency;
    }

    // visible for testing
    ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    static class RequestMetrics {
        private volatile long totalRequestTimeNanos = 0;
        private volatile int requestCount;
        private volatile int errorCount;
        private volatile int throttleCount;
        private volatile int statusCode;

        int getRequestCount() {
            return requestCount;
        }

        int getErrorCount() {
            return errorCount;
        }

        int getStatusCode() {
            return statusCode;
        }

        int getThrottleCount() {
            return throttleCount;
        }

        /**
         * Total time spent executing requests to complete operation in nanoseconds
         */
        long getTotalRequestTimeNanos() {
            return totalRequestTimeNanos;
        }

        @Override
        public String toString() {
            return "RequestMetrics{"
                + "totalRequestTimeNanos="
                + totalRequestTimeNanos
                + ", requestCount="
                + requestCount
                + ", errorCount="
                + errorCount
                + ", throttleCount="
                + throttleCount
                + ", statusCode="
                + statusCode
                + '}';
        }
    }

    private enum RetryMetricsTracker implements HttpPipelinePolicy {
        INSTANCE;

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            if (requestIsPartOfABatch(context)) {
                // Batch deletes fire once for each of the constituent requests, and they have a null response. Ignore those, we'll track
                // metrics at the bulk level.
                return next.process();
            }
            Optional<Object> metricsData = context.getData(RequestMetricsTracker.ES_REQUEST_METRICS_CONTEXT_KEY);
            if (metricsData.isPresent() == false) {
                assert false : "No metrics object associated with request " + context.getHttpRequest();
                return next.process();
            }
            RequestMetrics metrics = (RequestMetrics) metricsData.get();
            metrics.requestCount++;
            long requestStartTimeNanos = System.nanoTime();
            return next.process().doOnError(throwable -> {
                metrics.totalRequestTimeNanos += System.nanoTime() - requestStartTimeNanos;
                logger.debug("Detected error in RetryMetricsTracker", throwable);
                metrics.errorCount++;
            }).doOnSuccess(response -> {
                metrics.totalRequestTimeNanos += System.nanoTime() - requestStartTimeNanos;
                if (RestStatus.isSuccessful(response.getStatusCode()) == false) {
                    metrics.errorCount++;
                    // Azure always throttles with a 429 response, see
                    // https://learn.microsoft.com/en-us/azure/azure-resource-manager/management/request-limits-and-throttling#error-code
                    if (response.getStatusCode() == RestStatus.TOO_MANY_REQUESTS.getStatus()) {
                        metrics.throttleCount++;
                    }
                }
            });
        }

        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_RETRY;
        }
    }

    private static final class RequestMetricsTracker implements HttpPipelinePolicy {
        private static final String ES_REQUEST_METRICS_CONTEXT_KEY = "_es_azure_repo_request_stats";
        private static final Logger logger = LogManager.getLogger(RequestMetricsTracker.class);
        private final OperationPurpose purpose;
        private final RequestMetricsHandler requestMetricsHandler;

        private RequestMetricsTracker(OperationPurpose purpose, RequestMetricsHandler requestMetricsHandler) {
            this.purpose = purpose;
            this.requestMetricsHandler = requestMetricsHandler;
        }

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            if (requestIsPartOfABatch(context)) {
                // Batch deletes fire once for each of the constituent requests, and they have a null response. Ignore those, we'll track
                // metrics at the bulk level.
                return next.process();
            }
            final RequestMetrics requestMetrics = new RequestMetrics();
            context.setData(ES_REQUEST_METRICS_CONTEXT_KEY, requestMetrics);
            return next.process().doOnSuccess((httpResponse) -> {
                requestMetrics.statusCode = httpResponse.getStatusCode();
                trackCompletedRequest(context.getHttpRequest(), requestMetrics);
            }).doOnError(throwable -> {
                logger.debug("Detected error in RequestMetricsTracker", throwable);
                trackCompletedRequest(context.getHttpRequest(), requestMetrics);
            });
        }

        private void trackCompletedRequest(HttpRequest httpRequest, RequestMetrics requestMetrics) {
            HttpMethod method = httpRequest.getHttpMethod();
            if (method != null) {
                try {
                    requestMetricsHandler.requestCompleted(purpose, method, httpRequest.getUrl(), requestMetrics);
                } catch (Exception e) {
                    logger.warn("Unable to notify a successful request", e);
                }
            }
        }

        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_CALL;
        }
    }

    private static boolean requestIsPartOfABatch(HttpPipelineCallContext context) {
        return context.getData("Batch-Operation-Info").isPresent();
    }

    /**
     * The {@link RequestMetricsTracker} calls this when a request completes
     */
    interface RequestMetricsHandler {

        void requestCompleted(OperationPurpose purpose, HttpMethod method, URL url, RequestMetrics metrics);
    }
}
