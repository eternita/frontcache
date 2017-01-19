package org.frontcache.hystrix.stream;

import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.hystrix.metric.consumer.HystrixDashboardStream;

import rx.Observable;

public class FrontcacheHystrixMetricsStreamServlet extends FrontcacheHystrixSampleSseServlet {

    private static final long serialVersionUID = -7548505095303313237L;

    /* used to track number of connections and throttle */
    private static AtomicInteger concurrentConnections = new AtomicInteger(0);
    private static DynamicIntProperty maxConcurrentConnections =
            DynamicPropertyFactory.getInstance().getIntProperty("hystrix.config.stream.maxConcurrentConnections", 5);

    public FrontcacheHystrixMetricsStreamServlet() {
        this(HystrixDashboardStream.getInstance().observe());
    }
    
    public FrontcacheHystrixMetricsStreamServlet(Observable<HystrixDashboardStream.DashboardData> sampleStream) {
        super(sampleStream, DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS);
    }

    @Override
    protected int getMaxNumberConcurrentConnectionsAllowed() {
        return maxConcurrentConnections.get();
    }

    @Override
    protected int getNumberCurrentConnections() {
        return concurrentConnections.get();
    }

    @Override
    protected int incrementAndGetCurrentConcurrentConnections() {
        return concurrentConnections.incrementAndGet();
    }

    @Override
    protected void decrementCurrentConcurrentConnections() {
        concurrentConnections.decrementAndGet();
    }
}
