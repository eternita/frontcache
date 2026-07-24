<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- sub menu to switch between cache screens; ${activeCache} marks the current one -->
<ul class="nav nav-pills" role="tablist">
  <li role="presentation" class="${activeCache == 'view' ? 'active' : ''}"><a href="cache-view">Cache view</a></li>
  <li role="presentation" class="${activeCache == 'invalidation' ? 'active' : ''}"><a href="cache-invalidation">Cache Invalidation</a></li>
  <li role="presentation" class="${activeCache == 'replication' ? 'active' : ''}"><a href="cache-replication">Cache Replication</a></li>
  <li role="presentation" class="${activeCache == 'other' ? 'active' : ''}"><a href="cache-other">Other</a></li>
</ul>
