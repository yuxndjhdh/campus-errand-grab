#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-smoke-password-123}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD for the admin bootstrap account}"

json() { curl -fsS -H 'Content-Type: application/json' "$@"; echo; }
extract() { python -c 'import json,sys; print(json.load(sys.stdin)["data"][sys.argv[1]])' "$1"; }

suffix="$(date +%s)"
publisher=$(json -X POST "$BASE/api/auth/register" -d "{\"username\":\"smoke-publisher-$suffix\",\"nickname\":\"smoke-publisher\",\"password\":\"$SMOKE_PASSWORD\"}")
publisher_login=$(json -X POST "$BASE/api/auth/login" -d "{\"username\":\"smoke-publisher-$suffix\",\"password\":\"$SMOKE_PASSWORD\"}")
publisher_token=$(printf '%s' "$publisher_login" | extract accessToken)

taker=$(json -X POST "$BASE/api/auth/register" -d "{\"username\":\"smoke-taker-$suffix\",\"nickname\":\"smoke-taker\",\"password\":\"$SMOKE_PASSWORD\"}")
taker_login=$(json -X POST "$BASE/api/auth/login" -d "{\"username\":\"smoke-taker-$suffix\",\"password\":\"$SMOKE_PASSWORD\"}")
taker_token=$(printf '%s' "$taker_login" | extract accessToken)

json -X POST "$BASE/api/users/me/recharge" -H "Authorization: Bearer $publisher_token" \
  -d '{"amountCents":100000,"idemKey":"smoke-recharge-'"$suffix"'"}'
order=$(json -X POST "$BASE/api/orders" -H "Authorization: Bearer $publisher_token" \
  -d '{"title":"buy coffee","detail":"library","rewardCents":560,"claimTtlSeconds":120}')
order_id=$(printf '%s' "$order" | extract id)
json -X POST "$BASE/api/orders/$order_id/grab" -H "Authorization: Bearer $taker_token"
json -X POST "$BASE/api/orders/$order_id/deliver" -H "Authorization: Bearer $taker_token"

admin_login=$(json -X POST "$BASE/api/auth/login" -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}")
admin_token=$(printf '%s' "$admin_login" | extract accessToken)
json -X POST "$BASE/api/admin/recon/run" -H "Authorization: Bearer $admin_token" -d '{}'
json "$BASE/api/admin/stats" -H "Authorization: Bearer $admin_token"
