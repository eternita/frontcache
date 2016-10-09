<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>

<nav class="navbar navbar-default navbar-static-top" id="top">
	<div class="container">
		<div class="navbar-header">
			<button aria-controls="bs-navbar" aria-expanded="false"
				class="collapsed navbar-toggle" data-target="#bs-navbar"
				data-toggle="collapse" type="button">
				<span class="sr-only">Toggle navigation</span> <span
					class="icon-bar"></span> <span class="icon-bar"></span> <span
					class="icon-bar"></span>
			</button>
			<a href="/" class="navbar-brand"><img alt="Brand" src="static/images/logo.jpeg" width="50px"></a>
		</div>
		<nav class="collapse navbar-collapse" id="bs-navbar">
			<ul class="nav navbar-nav">
				<li><a href="realtime">Realtime monitor </a></li>
				<li><a href="fallbacks">Fallback configs </a></li>
				<li><a href="cache-view">Cache view </a></li>
				<li><a href="edges">Edges </a></li>
			</ul>
		</nav>
	</div>
</nav>