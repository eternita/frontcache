<%@ taglib prefix="spring" uri="http://www.springframework.org/tags"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<!DOCTYPE html>
<html lang="en">
<head>
<title>Frontcache Console</title>

<%-- <spring:url value="/resources/core/css/console.css" var="coreCss" />
<spring:url value="/resources/core/css/bootstrap.min.css" var="bootstrapCss" />
<link href="${bootstrapCss}" rel="stylesheet" />
<link href="${coreCss}" rel="stylesheet" />
 --%>

<%-- 
<spring:url value="/resources/core/js/console.js" var="coreJs" />
<script src="${coreJs}"></script>
<spring:url value="/resources/core/js/bootstrap.min.js" var="bootstrapJs" />
<script src="${bootstrapJs}"></script>
--%>

	<!-- Setup base for everything -->
	<link rel="stylesheet" type="text/css" href="resources/hystrix/css/global.css" />
	
	<!-- Our custom CSS -->
	<link rel="stylesheet" type="text/css" href="resources/hystrix/monitor/monitor.css" />

	<!-- d3 -->
	<script type="text/javascript" src="resources/hystrix/js/d3.v2.min.js"></script>
	
	<!-- Javascript to monitor and display 
	-->
	
	<script src="https://ajax.googleapis.com/ajax/libs/jquery/1.11.2/jquery.min.js"></script>
	<script type="text/javascript" src="resources/hystrix/js/jquery.tinysort.min.js"></script>
	
	<script type="text/javascript" src="resources/hystrix/js/tmpl.js"></script>
	<script src="//cdnjs.cloudflare.com/ajax/libs/underscore.js/1.8.3/underscore-min.js"></script>
	
	<!-- HystrixCommand -->
	<script type="text/javascript" src="resources/hystrix/components/hystrixCommand/hystrixCommand.js"></script>
	<link rel="stylesheet" type="text/css" href="resources/hystrix/components/hystrixCommand/hystrixCommand.css" />
	
	<!-- HystrixThreadPool -->
	<script type="text/javascript" src="resources/hystrix/components/hystrixThreadPool/hystrixThreadPool.js"></script>
	<link rel="stylesheet" type="text/css" href="resources/hystrix/components/hystrixThreadPool/hystrixThreadPool.css" />

</head>

<div style="font-size: 22px; padding-left: 20px;">
	Frontcache Console
</div>

<div class="container">

	<hr>
	<div class="">
		<div style="padding-left: 20px;">
		<p style="height: 10px">&nbsp;</p>
		<h2 style="font-size: 22px;">Cached items on servers</h2>  
		<br/>
		
			<c:forEach var="entry" items="${cachedAmount}">
			  <c:out value="${entry.key}"/> - 
			  <c:out value="${entry.value}"/> items <br/>
			</c:forEach>
		</div>
	</div>
       
	<hr>

	<div id="header"><h2 style="font-size: 22px; padding-left: 20px;">Real time performance monitoring</h2></div>
	<div id="content"></div>

	<script>

		$(window).load(function() { // within load with a setTimeout to prevent the infinite spinner
			setTimeout(function() {
				addStreams();
			},0);
		});

		var hystrixStreams = [];

		function addStreams() {
			var streams = JSON.parse('${hystrixMonitorURLList}'); // hystrixMonitorURLList privided by controller
			
			_.map(streams, function(s, i) {
				var dependenciesId = 'dependencies_' + i;

				var hystrixMonitor = new HystrixCommandMonitor(i, dependenciesId, {includeDetailIcon:false});
				var dependencyThreadPoolMonitor = new HystrixThreadPoolMonitor(i, 'dependencyThreadPools_' + i);

				hystrixStreams[i] = {
					titleName: s.name || s.stream,
					hystrixMonitor: hystrixMonitor,
					dependencyThreadPoolMonitor: dependencyThreadPoolMonitor
				};

				// sort by error+volume by default
				hystrixMonitor.sortByErrorThenVolume();
				dependencyThreadPoolMonitor.sortByVolume();

				var origin;
				if(s != undefined) {
					origin = s.stream;

					if(s.delay) {
						origin = origin + "&delay=" + s.delay;
					}

					//do not show authorization in stream title
					if(s.auth) {
						origin = origin + "&authorization=" + s.auth;
					}
				}

				var proxyStream = "resources/hystrix/proxy.stream?origin=" + origin;

				// start the EventSource which will open a streaming connection to the server
				var source = new EventSource(proxyStream);

				// add the listener that will process incoming events
				source.addEventListener('message', hystrixMonitor.eventSourceMessageListener, false);
				source.addEventListener('message', dependencyThreadPoolMonitor.eventSourceMessageListener, false);

				//	source.addEventListener('open', function(e) {
				//		console.console.log(">>> opened connection, phase: " + e.eventPhase);
				//		// Connection was opened.
				//	}, false);

				source.addEventListener('error', function(e) {
					$("#" + dependenciesId + " .loading").html("Unable to connect to Command Metric Stream.");
					$("#" + dependenciesId + " .loading").addClass("failed");
					if (e.eventPhase == EventSource.CLOSED) {
						// Connection was closed.
						console.log("Connection was closed on error: " + e);
					} else {
						console.log("Error occurred while streaming: " + e);
					}
				}, false);
			});

			addMonitors();
		}

		function addMonitors() {
			$("#content").html(_.reduce(hystrixStreams, function(html, s, i) {
				var hystrixMonitor = 'hystrixStreams[' + i + '].hystrixMonitor';
				var dependencyThreadPoolMonitor = 'hystrixStreams[' + i + '].dependencyThreadPoolMonitor';
				var dependenciesId = 'dependencies_' + i;
				var dependencyThreadPoolsId = 'dependencyThreadPools_' + i;
				var title_name = 'title_name_' + i;

				return html +
					'<div id="monitor">' +
						'<div id="streamHeader">' +
							'<h2><span id="' + title_name + '"></span>Hystrix Stream: ' + s.titleName + '</h2>' +
						'</div>' +
						'<div class="container">' +
							'<div class="row">' +
								'<div class="menubar">' +
									'<div class="title">' +
										'Circuit' +
									'</div>' +
									'<div class="menu_actions">' +
										'Sort: ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByErrorThenVolume();">Error then Volume</a> |' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortAlphabetically();">Alphabetical</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByVolume();">Volume</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByError();">Error</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByLatencyMean();">Mean</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByLatencyMedian();">Median</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByLatency90();">90</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByLatency99();">99</a> | ' +
										'<a href="javascript://" onclick="' + hystrixMonitor + '.sortByLatency995();">99.5</a> ' +
									'</div>' +
									'<div class="menu_legend">' +
										'<span class="success">Success</span> | <span class="shortCircuited">Short-Circuited</span> | <span class="badRequest"> Bad Request</span> | <span class="timeout">Timeout</span> | <span class="rejected">Rejected</span> | <span class="failure">Failure</span> | <span class="errorPercentage">Error %</span>' +
									'</div>' +
								'</div>' +
							'</div>' +
							'<div id="' + dependenciesId + '" class="row dependencies"><span class="loading">Loading ...</span></div>' +
							'<div class="spacer"></div>' +

/* 							'<div class="row">' +
								'<div class="menubar">' +
									'<div class="title">' +
										'Thread Pools' +
									'</div>' +
									'<div class="menu_actions">' +
										'Sort: <a href="javascript://" onclick="' + dependencyThreadPoolMonitor + '.sortAlphabetically();">Alphabetical</a> | ' +
										'<a href="javascript://" onclick="' + dependencyThreadPoolMonitor + '.sortByVolume();">Volume</a> | ' +
									'</div>' +
								'</div>' +
							'</div>' +
							'<div id="' + dependencyThreadPoolsId + '" class="row dependencyThreadPools"><span class="loading">Loading ...</span></div>' +
							'<div class="spacer"></div>' +
							'<div class="spacer"></div>' +
 */						'</div>' +
					'</div>';
			}, ''));
		}
	</script>


	<hr>
	<footer>
		<p>&copy; frontcache.org</p>
	</footer>
</div>

 
</body>
</html>