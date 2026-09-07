# Frontcache — HTTP Headers

Concepts: [concept.md](concept.md) · JSP tag equivalents: [jsp-tags.md](jsp-tags.md)

HTTP Header | Set by | Where | Description
--- | --- | --- | ---
`x-frontcache-trace` | Client | request | when true - response headers have performance statistics for request and includes
`x-frontcache-component-tags` | Origin | response | set invalidation tags for cache, optional
`x-frontcache-component-maxage` | Origin | response | set time to live in cache
`x-frontcache-component-cache-level` | Origin  | response  | set cache level - L1 / L2, optional, default - L2 
`x-frontcache-component-refresh` | Origin | response | set refresh type for cached entry - regular / soft, optional, default - regular
`x-frontcache-combine` | Origin | response | set to 1 by an origin answering a combined include request, see [include-combining.md](include-combining.md)
`x-frontcache-id` | Frontcache | response | frontcache server id (useful in case of geo balancing e.g. R53)
`x-frontcache-request-id` | Frontcache | response | request UUID - the same value for top level request and all includes
`x-frontcache-client-ip` | Frontcache | request | set in frontcache server for origin application
`x-frontcache-trace-request` | Frontcache | response | performance statistics for request and includes
`x-frontcache-fallback-is-used` | Frontcache | response | is true when response has fallbacks included
`x-frontcache-dynamic-request` | Frontcache | request | internal use
`x-frontcache-soft-refresh` | Frontcache | request | internal use
`x-frontcache-async-include` | Frontcache | request | internal use
`x-frontcache-include-level` | Frontcache | request | internal use




