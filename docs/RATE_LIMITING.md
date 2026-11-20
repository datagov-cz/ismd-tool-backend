# Rate limiting

Who this is for: the administrator running ISMD after development ends. It covers the limits that exist, how
to switch on the backend's own limiter, and what to change when the client's nginx proxy is retired.

## Request path

Today:

```
browser ──► client's nginx ──► App Gateway (WAF_v2) ──► tool-frontend (Next.js) ──► tool-backend
            public IP N        public IP G               /popisujeme/api/backend/*    internal ingress
```

After nginx is retired:

```
browser ──► App Gateway (WAF_v2) ──► tool-frontend ──► tool-backend
```

On TEST and PROD the backend has internal ingress only: every request reaches it through the frontend's proxy
route. On DEV, App Gateway also routes `/popisujeme/be/*` straight to the backend.

Each proxy appends the address it received the request from to `X-Forwarded-For`. nginx puts the browser's
address in it; App Gateway appends nginx's address (with a port, e.g. `203.0.113.10:51234`); the Container Apps
ingress may append internal addresses. The frontend passes the header on unchanged.

## Two layers

| Layer | Where | What it limits | Status |
|---|---|---|---|
| WAF rule `RateLimitPerClientIp` | App Gateway, `ismd-infrastructure/modules/shared_global/waf_policy.tf` | Every request, 600 per minute per client IP (`shared-global/main.tf`) | On |
| Backend limiter | tool-backend, this repo | The expensive public endpoints, per endpoint group, per user or client IP | Off until switched on (below) |

The WAF rule stops floods. The backend limiter protects the shared upstream budgets (above all the e-Sbírka
REST key, which allows one call per 20 ms for the whole application) with limits per endpoint group, far below
600 a minute.

## Backend limiter

### What it limits

| Group | Paths (inside the backend) | Default per caller |
|---|---|---|
| `esbirka` | `/api/eli/**` | 60 per minute |
| `resolve` | `/api/ontology/concepts/resolve` | 30 per minute |
| `search` | `/api/search/**`, `/api/nkd/**`, `/api/nkod/**`, `/api/rpp/*/search` | 120 per minute |

Only the public and search security chains are limited. Endpoints that need a login are not.

A caller is:

- on `/api/search`, the signed-in user (Keycloak `sub`) when the request carries a token;
- otherwise the client IP found in `X-Forwarded-For` (the public endpoints never read the token).

Over the limit, the response is `429` with a `Retry-After` header (seconds) and the usual error body:

```json
{"success": false, "errorCode": "RATE_LIMITED", "message": "Příliš mnoho požadavků. Zkuste to prosím za chvíli."}
```

Counters are held in memory. They are exact while the backend runs as one replica (`max_replicas = 1`). With
more replicas each one counts on its own, so the effective limit is multiplied by the replica count.

### How the client IP is found

The backend reads `X-Forwarded-For` from the right and skips every address listed in
`RATE_LIMIT_TRUSTED_PROXIES`. The first address that is not listed is the client. Anything a client writes into
the header itself sits to the left of that and is never used, so it cannot be spoofed as long as every proxy in
the chain is listed.

If a proxy is **not** listed, its address is taken for the client and every user behind it shares one budget.
That is the failure to check for before enforcing.

### Settings

Set as environment variables on the backend container app (`ismd-infrastructure/modules/tool_apps/backend.tf`,
`env { name = ... value = ... }` blocks), per environment.

| Variable | Default | Meaning |
|---|---|---|
| `RATE_LIMIT_MODE` | `OFF` | `OFF`, `LOG` (log what would be rejected, reject nothing) or `ENFORCE` (reject with 429) |
| `RATE_LIMIT_TRUSTED_PROXIES` | private and loopback ranges | Comma-separated addresses or CIDRs to skip. Setting it **replaces** the default, so keep the private ranges in the list |
| `RATE_LIMIT_ESBIRKA_PER_MINUTE` | `60` | Budget of the `esbirka` group |
| `RATE_LIMIT_RESOLVE_PER_MINUTE` | `30` | Budget of the `resolve` group |
| `RATE_LIMIT_SEARCH_PER_MINUTE` | `120` | Budget of the `search` group |
| `LOGGING_LEVEL_COM_DIA_ISMDTOOLBACKEND_RATELIMIT` | `INFO` | `DEBUG` logs, per request, the resolved caller and the raw header. Personal data: use only while checking |

The default private ranges are
`10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,127.0.0.0/8,::1/128,fc00::/7`.

### Switching it on

Prerequisite: a tool-frontend build that forwards `X-Forwarded-For` (branch `feat/rate-limit-when-nginx-deprecated`,
`src/app/api/backend/[...path]/route.ts`). Without it every anonymous user shares one budget.

1. **Find the proxy addresses.**
   - App Gateway public IP **G**: `terraform output app_gateway_public_ip_address` in `shared-global`, or the
     portal (App Gateway → Frontend IP configurations).
   - nginx egress IP(s) **N**: nginx is the TCP peer of App Gateway, so its addresses dominate the client IP of
     the access log. In the Log Analytics workspace:

     ```kusto
     // resource-specific table
     AGWAccessLogs
     | where TimeGenerated > ago(1d)
     | summarize requests = count() by ClientIp
     | top 10 by requests
     // or, in the AzureDiagnostics mode
     AzureDiagnostics
     | where Category == "ApplicationGatewayAccessLog" and TimeGenerated > ago(1d)
     | summarize requests = count() by clientIP_s
     | top 10 by requests
     ```

     Confirm the addresses with whoever runs nginx; it may have more than one.
2. **Set log mode.**
   `RATE_LIMIT_MODE=LOG`,
   `RATE_LIMIT_TRUSTED_PROXIES=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,127.0.0.0/8,::1/128,fc00::/7,<G>,<N>`,
   and for this check only `LOGGING_LEVEL_COM_DIA_ISMDTOOLBACKEND_RATELIMIT=DEBUG`.
3. **Check the caller.** Open the tool, search for something, and find your request in the backend log:
   `Rate limit caller=ip:<address> group=search xff='…' peer=…`. `<address>` must be your own public IP
   (whatismyip). If it is G, N or an internal address, add that address to the trusted list and repeat. Then
   remove the `DEBUG` variable.
4. **Watch for a week.** Lines `Rate limit would reject: group=… caller=…` show who would have been stopped. If
   real users show up, raise that group's budget.
5. **Enforce.** `RATE_LIMIT_MODE=ENFORCE`. Rejections are logged at INFO as `Rate limit rejected: group=…`.

To switch it off again: `RATE_LIMIT_MODE=OFF`. No redeploy of the image is needed, only a new revision with
the changed variable.

## When nginx is retired

Do these in the same change window as the DNS switch to App Gateway.

1. **WAF rule: group by the TCP address.** In `waf_policy.tf`, rule `RateLimitPerClientIp`, change
   `group_rate_limit_by = "ClientAddrXFFHeader"` to `"ClientAddr"`, and update the rule's comment and the one in
   `shared-global/main.tf`. Without nginx, browsers connect to App Gateway directly, so `ClientAddr` is the real,
   unspoofable client. Keeping `ClientAddrXFFHeader` would let anyone escape the limit by sending a different
   `X-Forwarded-For` value with each request.
2. **Backend limiter: drop nginx from the trusted list.** Remove N from `RATE_LIMIT_TRUSTED_PROXIES`, keep G and
   the private ranges. Leaving N in is harmless but untidy. Then repeat step 3 of "Switching it on": the
   resolved caller must still be your own IP.
3. **Bare `/` is now blocked.** The `BlockUnroutedPaths` WAF rule blocks every path but `/validujeme` and
   `/popisujeme`. Today nginx answers `/`; afterwards `/` reaches App Gateway and gets a 403. If `/` should lead
   somewhere, add a redirect for it in the URL path map (`appgw_validator_config.tf`); App Gateway does not run
   the WAF on redirected requests.
4. **Re-check the WAF threshold.** 600 per minute was set from dev and test peaks measured behind nginx. Run the
   per-IP peak query from `shared-global/main.tf` against prod after a week and adjust
   `waf_rate_limit_threshold` if needed.
5. **Outside ISMD:** the public hostname's DNS and TLS certificate move from nginx to App Gateway's listener.
   That belongs to whoever owns the domain; check it is planned.

No backend or frontend code changes are needed for the retirement.

## Options considered

| Option | Verdict |
|---|---|
| **Backend limiter (Bucket4j), per user or IP** | Chosen. Per endpoint group, configured per environment, no extra Azure cost, survives the nginx retirement through configuration. |
| **App Gateway WAF rules per path** | Possible addition, admin-only, no code. A second rate-limit rule with a lower threshold on the e-Sbírka path. See below. Coarser than the backend (one-minute or five-minute windows, no per-user key). |
| **nginx `limit_req`** | Not recommended: nginx is run by the client and is going away. |
| **Azure Front Door Premium + WAF** | Not used: not part of this deployment, adds cost, and App Gateway WAF_v2 already provides rate-limit rules. |

### Optional: a WAF rule for e-Sbírka

Add next to `RateLimitPerClientIp` in `waf_policy.tf` (priority 11 runs right after it):

```hcl
custom_rules {
  name                 = "RateLimitEsbirkaPerClientIp"
  priority             = 11
  rule_type            = "RateLimitRule"
  action               = "Block"
  rate_limit_duration  = "OneMin"
  rate_limit_threshold = 120
  group_rate_limit_by  = "ClientAddrXFFHeader" # "ClientAddr" once nginx is retired

  match_conditions {
    match_variables {
      variable_name = "RequestUri"
    }
    operator     = "BeginsWith"
    transforms   = ["Lowercase"]
    match_values = ["/popisujeme/api/backend/api/eli/"]
  }
}
```

The WAF answers blocked requests itself with `429` and its own page, not with the backend's JSON body, so the
frontend shows a generic error for them.
