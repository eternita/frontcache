package org.frontcache.hystrix;

import com.netflix.hystrix.metric.consumer.HystrixDashboardStream;

import org.frontcache.hystrix.stream.FrontcacheHystrixMetricsStreamServlet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import rx.Observable;
import rx.functions.Func1;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HystrixMetricsStreamServletUnitTest {
	    @Mock HttpServletRequest mockReq;
	    @Mock HttpServletResponse mockResp;
	    @Mock HystrixDashboardStream.DashboardData mockDashboard;
	    @Mock PrintWriter mockPrintWriter;

	    FrontcacheHystrixMetricsStreamServlet servlet;

	    private final Observable<HystrixDashboardStream.DashboardData> streamOfOnNexts =
	            Observable.interval(100, TimeUnit.MILLISECONDS).map(new Func1<Long, HystrixDashboardStream.DashboardData>() {
	                @Override
	                public HystrixDashboardStream.DashboardData call(Long timestamp) {
	                    return mockDashboard;
	                }
	            });


	    @Before
	    public void init() {
	        MockitoAnnotations.initMocks(this);
	        when(mockReq.getMethod()).thenReturn("GET");
	    }

	    @After
	    public void tearDown() {
	        servlet.destroy();
	        servlet.shutdown();
	    }

	    @Test
	    public void shutdownServletShouldRejectRequests() throws ServletException, IOException {
	        servlet = new FrontcacheHystrixMetricsStreamServlet(streamOfOnNexts);
	        try {
	            servlet.init();
	        } catch (ServletException ex) {

	        }

	        servlet.shutdown();

	        servlet.service(mockReq, mockResp);

	        verify(mockResp).sendError(503, "Service has been shut down.");
	    }
	    
	    @Test
	    public void testOnGetNoSiteKeyHeader() throws ServletException, IOException {
	        servlet = new FrontcacheHystrixMetricsStreamServlet(streamOfOnNexts);
	        try {
	            servlet.init();
	        } catch (ServletException ex) {

	        }
	 

	        servlet.service(mockReq, mockResp);

	        verify(mockResp).sendError(503, "Can't resolve domain from siteKey");
	    }
	    
	}
