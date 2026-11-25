#!/usr/bin/env bash
#
# search-cidr.sh - top client IP prefixes (CIDR blocks) for rows matching a regex
#                  in a FrontCache request log.
#
# Log format (org.frontcache.reqlog.RequestLogger):
#   ts req-id domain method success|error req-type cacheable|direct dynamic|from-cache|... \
#   runtime-ms length-bytes "url" "client-ip" fc-id bot|guest "client-type-rule" "user-agent"
#
# The client-ip column is the X-Forwarded-For chain ("real-client, cf-edge, ..."),
# so only the FIRST address in it is the actual client.
#
# client-type-rule is the conf/bots.conf rule that decided bot|guest. Frontcache 2.10.0
# INSERTED it before the user agent, so a log that spans the upgrade holds both shapes and
# the user-agent column is located per line. A frontcache-failed-requests log works here too:
# it has one more quoted column (the guard rule / fallback reason), detected by its filename.
#
# The regex is an ERE matched against the whole raw log line (like grep -E).
# Omit it to aggregate every line.
#
# Usage:
#   ./search-cidr.sh [-n N] [-m BITS] [-M BITS] [-i] [REGEX] [LOGFILE]
#
#   -n N     how many prefixes to show (default 20)
#   -m BITS  IPv4 prefix length, 0-32  (default 24 -> a.b.c.0/24)
#   -M BITS  IPv6 prefix length, 0-128 (default 64)
#   -i       case-insensitive regex
#
# Examples:
#   ./search-cidr.sh -n 20 -m 24 "toplevel.*no-js-proof"
#   ./search-cidr.sh -m 16 -M 32 'GET .*\.htm"'
#
# Default log: logs/fc-us.hobbyray.com-frontcache-requests.log next to this script.
# .gz / .bz2 / .xz logs are read transparently.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG="$SCRIPT_DIR/logs/fc-us.hobbyray.com-frontcache-requests.log"
TOP=20
V4BITS=24
V6BITS=64
NOCASE=0

# '2,/^$/p' = the header comment block, however long it grows - a hard-coded last line
# silently truncates the usage text the next time a paragraph is added above.
usage() { sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while getopts ":n:m:M:ih" opt; do
  case "$opt" in
    n) TOP="$OPTARG" ;;
    m) V4BITS="$OPTARG" ;;
    M) V6BITS="$OPTARG" ;;
    i) NOCASE=1 ;;
    h) usage 0 ;;
    :) echo "option -$OPTARG needs an argument" >&2; usage 1 ;;
    *) echo "unknown option: -$OPTARG" >&2; usage 1 ;;
  esac
done
shift $((OPTIND - 1))

FC_RE="${1-}"
[ $# -ge 2 ] && LOG="$2"

case "$TOP"    in ''|*[!0-9]*) echo "-n needs a number" >&2; exit 1 ;; esac
case "$V4BITS" in ''|*[!0-9]*) echo "-m needs a number" >&2; exit 1 ;; esac
case "$V6BITS" in ''|*[!0-9]*) echo "-M needs a number" >&2; exit 1 ;; esac
[ "$V4BITS" -le 32 ]  || { echo "-m must be 0..32" >&2; exit 1; }
[ "$V6BITS" -le 128 ] || { echo "-M must be 0..128" >&2; exit 1; }

[ -r "$LOG" ] || { echo "cannot read log: $LOG" >&2; exit 1; }

# A frontcache-failed-requests log carries one quoted column more than the request log (the
# guard rule / fallback reason), which shifts the baseline the user-agent column is found by.
case "$LOG" in
  *failed-requests*) ISFAILED=1 ;;
  *)                 ISFAILED=0 ;;
esac

case "$LOG" in
  *.gz)  READER=(gzip -cd) ;;
  *.bz2) READER=(bzip2 -cd) ;;
  *.xz)  READER=(xz -cd) ;;
  *)     READER=(cat) ;;
esac

echo "log:    $LOG"
echo "regex:  ${FC_RE:-(none - all lines)}$( [ "$NOCASE" = 1 ] && echo '  (case-insensitive)' )"
echo "mask:   IPv4 /$V4BITS, IPv6 /$V6BITS"
echo

export FC_RE

"${READER[@]}" "$LOG" | awk -v top="$TOP" -v v4bits="$V4BITS" -v v6bits="$V6BITS" -v nocase="$NOCASE" \
                             -v isfailed="$ISFAILED" '
function human(b,   u, i) {
  split("B KB MB GB TB PB", u, " ")
  i = 1
  while (b >= 1024 && i < 6) { b /= 1024; i++ }
  return sprintf("%.1f %s", b, u[i])
}
# keep the top "bits" bits of a "width"-bit field - portable, no gawk and()
function keepbits(v, bits, width,   d) {
  if (bits <= 0)     return 0
  if (bits >= width) return v
  d = 2 ^ (width - bits)
  return int(v / d) * d
}
function hex2dec(s,   i, c, n, p) {
  n = 0
  s = tolower(s)
  for (i = 1; i <= length(s); i++) {
    c = substr(s, i, 1)
    p = index("0123456789abcdef", c) - 1
    if (p < 0) return -1
    n = n * 16 + p
  }
  return n
}
function v4prefix(ip, bits,   o, v, m) {
  if (split(ip, o, ".") != 4) return ""
  for (i = 1; i <= 4; i++)
    if (o[i] !~ /^[0-9]+$/ || o[i] > 255) return ""
  v = keepbits(o[1] * 16777216 + o[2] * 65536 + o[3] * 256 + o[4], bits, 32)
  return sprintf("%d.%d.%d.%d/%d", int(v / 16777216) % 256, int(v / 65536) % 256, \
                 int(v / 256) % 256, v % 256, bits)
}
# expand "::" to 8 groups, mask, then re-compress the zero tail
function v6prefix(ip, bits,   head, tail, hn, tn, g, i, j, bleft, out, sep,
                             left, right, runStart, runLen, bestStart, bestLen) {
  if (ip ~ /%/) sub(/%.*/, "", ip)             # strip the zone id
  if (index(ip, "::") > 0) {
    j  = index(ip, "::")
    hn = split(substr(ip, 1, j - 1), head, ":")
    tn = split(substr(ip, j + 2), tail, ":")
    if (head[1] == "") hn = 0
    if (tail[1] == "") tn = 0
    if (hn + tn > 8) return ""
    for (i = 1; i <= hn; i++) g[i] = head[i]
    for (i = hn + 1; i <= 8 - tn; i++) g[i] = "0"
    for (i = 1; i <= tn; i++) g[8 - tn + i] = tail[i]
  } else {
    if (split(ip, head, ":") != 8) return ""
    for (i = 1; i <= 8; i++) g[i] = head[i]
  }
  for (i = 1; i <= 8; i++) {
    if (g[i] == "") g[i] = "0"
    if ((g[i] = hex2dec(g[i])) < 0 || g[i] > 65535) return ""
    bleft = bits - (i - 1) * 16
    g[i] = keepbits(g[i], bleft, 16)
  }
  # collapse the longest run of zero groups to "::" (RFC 5952)
  bestStart = 0; bestLen = 0; runStart = 0; runLen = 0
  for (i = 1; i <= 9; i++) {
    if (i <= 8 && g[i] == 0) {
      if (runLen++ == 0) runStart = i
    } else {
      if (runLen > bestLen && runLen > 1) { bestLen = runLen; bestStart = runStart }
      runLen = 0
    }
  }
  if (bestLen == 0) {
    out = ""; sep = ""
    for (i = 1; i <= 8; i++) { out = out sep sprintf("%x", g[i]); sep = ":" }
  } else {
    left = ""; sep = ""
    for (i = 1; i < bestStart; i++) { left = left sep sprintf("%x", g[i]); sep = ":" }
    right = ""; sep = ""
    for (i = bestStart + bestLen; i <= 8; i++) { right = right sep sprintf("%x", g[i]); sep = ":" }
    out = left "::" right
  }
  return out "/" bits
}
BEGIN {
  re = ENVIRON["FC_RE"]
  if (nocase) re = tolower(re)
  # quoted fields on a PRE-2.10.0 line of this file: "url" "client-ip" "user-agent", plus
  # "reason" in a failed-requests log. One more than this means the line also carries the
  # 2.10.0 client-type-rule column, which pushed the user agent from q[6] to q[8].
  baseQuoted = (isfailed + 0) ? 4 : 3
}
{
  if (re != "") {
    if (nocase) { if (tolower($0) !~ re) next }
    else        { if ($0 !~ re)          next }
  }
  matched++

  # quoted columns: q[2]=url, q[4]=client-ip chain, then a tail that depends on the node
  # version and on which log this is:
  #   request log, pre-2.10.0:  q[6]=user-agent
  #   request log, 2.10.0+:     q[6]=client-type-rule  q[8]=user-agent
  #   failed log,  pre-2.10.0:  q[6]=user-agent        q[8]=reason
  #   failed log,  2.10.0+:     q[6]=client-type-rule  q[8]=user-agent  q[10]=reason
  # Decided per line, not per file: a rolled log spans the upgrade. Only the client IP is
  # needed for the aggregation itself, and it is q[4] in every shape.
  n = split($0, q, "\"")
  if (n < 5) { malformed++; next }
  agentIdx = (int((n - 1) / 2) > baseQuoted) ? 8 : 6

  ip = q[4]
  if ((c = index(ip, ",")) > 0) ip = substr(ip, 1, c - 1)   # real client, drop CDN hops
  gsub(/^[ \t]+|[ \t]+$/, "", ip)
  if (ip == "" || ip == "null" || ip == "-") next

  # ::ffff:a.b.c.d and friends are really IPv4 clients - mask them as such
  if (index(ip, ":") > 0 && index(ip, ".") > 0) sub(/^.*:/, "", ip)

  net = (index(ip, ":") > 0) ? v6prefix(ip, v6bits) : v4prefix(ip, v4bits)
  if (net == "") { badip++; next }

  bytes = ($10 ~ /^-?[0-9]+$/ && $10 > 0) ? $10 : 0

  req[net]++
  vol[net] += bytes
  if (!((net SUBSEP ip) in seen)) { seen[net, ip] = 1; hosts[net]++ }
  if (!(net in ua) && q[agentIdx] != "") ua[net] = q[agentIdx]

  totalReq++
  totalVol += bytes
}
END {
  if (totalReq == 0) {
    printf "no matching lines%s\n", (matched ? " with a usable client IP" : "")
    exit
  }

  n = 0
  for (net in req) nets[++n] = net
  # partial selection sort - only the top N need ordering
  if (top > n) top = n
  for (i = 1; i <= top; i++) {
    m = i
    for (j = i + 1; j <= n; j++)
      if (req[nets[j]] > req[nets[m]]) m = j
    t = nets[i]; nets[i] = nets[m]; nets[m] = t
  }

  printf "%-4s %-34s %10s %8s %8s %11s  %s\n", "#", "CIDR", "REQUESTS", "SHARE", "IPS", "TRAFFIC", "USER-AGENT"
  for (i = 1; i <= top; i++) {
    net = nets[i]
    agent = ua[net]
    if (length(agent) > 60) agent = substr(agent, 1, 57) "..."
    printf "%-4d %-34s %10d %7.2f%% %8d %11s  %s\n", \
      i, net, req[net], 100 * req[net] / totalReq, hosts[net], human(vol[net]), agent
  }

  printf "\nmatched %d lines: %d requests from %d distinct prefixes, %s served\n", \
    matched, totalReq, n, human(totalVol)
  topReq = 0; topHosts = 0
  for (i = 1; i <= top; i++) { topReq += req[nets[i]]; topHosts += hosts[nets[i]] }
  printf "top %d prefixes: %d requests (%.2f%% of matched), %d distinct IPs\n", \
    top, topReq, 100 * topReq / totalReq, topHosts
  if (badip)     printf "skipped %d unparsable client IPs\n", badip
  if (malformed) printf "skipped %d malformed lines\n", malformed
}
'
