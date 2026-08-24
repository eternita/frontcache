<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- sub menu to switch between config screens; ${activeConfig} marks the current one -->
<ul class="nav nav-pills" role="tablist">
  <li role="presentation" class="${activeConfig == 'fallbacks' ? 'active' : ''}"><a href="fallbacks">Fallback Config</a></li>
  <li role="presentation" class="${activeConfig == 'bot' ? 'active' : ''}"><a href="bot-configs">Bot Config</a></li>
  <li role="presentation" class="${activeConfig == 'dynamic' ? 'active' : ''}"><a href="dynamic-urls-configs">Dynamic URLs Config</a></li>
  <li role="presentation" class="${activeConfig == 'resilience' ? 'active' : ''}"><a href="resilience-configs">Resilience Configs</a></li>
  <li role="presentation" class="${activeConfig == 'guard' ? 'active' : ''}"><a href="guard-configs">Guard Configs</a></li>
</ul>
