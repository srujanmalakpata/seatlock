#!/usr/bin/env bash
# End-to-end smoke test against a running instance (default http://localhost:8080):
# health -> create event -> hold a seat -> retry the hold with the same Idempotency-Key ->
# confirm -> check availability -> scrape Prometheus. Requires curl and python3.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "1. health"
curl -fsS "$BASE_URL/actuator/health" | json "['status']"

echo "2. create event"
EVENT_ID=$(curl -fsS -X POST "$BASE_URL/api/v1/events" -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Test","venue":"Hall","startsAt":"2030-01-01T20:00:00Z",
       "sections":[{"name":"FLOOR","rows":2,"seatsPerRow":5,"priceCents":4500}]}' | json "['id']")
echo "   event $EVENT_ID"

SEAT_ID=$(curl -fsS "$BASE_URL/api/v1/events/$EVENT_ID/seats?status=AVAILABLE&size=1" \
  | json "['items'][0]['id']")

echo "3. hold seat $SEAT_ID (twice, same Idempotency-Key)"
HOLD_BODY="{\"seatIds\":[\"$SEAT_ID\"],\"customerRef\":\"smoke\"}"
KEY="smoke-$(date +%s%N)"
HOLD_1=$(curl -fsS -X POST "$BASE_URL/api/v1/events/$EVENT_ID/holds" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$HOLD_BODY" | json "['id']")
HOLD_2=$(curl -fsS -X POST "$BASE_URL/api/v1/events/$EVENT_ID/holds" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$HOLD_BODY" | json "['id']")
[ "$HOLD_1" = "$HOLD_2" ] || { echo "idempotent replay returned a different hold"; exit 1; }
echo "   hold $HOLD_1 (replayed identically)"

echo "4. a second customer cannot hold the same seat"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/events/$EVENT_ID/holds" \
  -H 'Content-Type: application/json' -d "{\"seatIds\":[\"$SEAT_ID\"],\"customerRef\":\"other\"}")
[ "$STATUS" = "409" ] || { echo "expected 409, got $STATUS"; exit 1; }
echo "   409 Conflict"

echo "5. confirm"
curl -fsS -X POST "$BASE_URL/api/v1/holds/$HOLD_1/confirm" | json "['status']"

echo "6. availability"
BOOKED=$(curl -fsS "$BASE_URL/api/v1/events/$EVENT_ID/availability" | json "['booked']")
[ "$BOOKED" = "1" ] || { echo "expected 1 booked seat, got $BOOKED"; exit 1; }
echo "   1 of 10 seats booked"

echo "7. prometheus"
curl -fsS "$BASE_URL/actuator/prometheus" | grep -E '^seats_(holds_placed|bookings_confirmed)_total'

echo "SMOKE TEST PASSED"
