#!/usr/bin/env python3
"""Non-production end-to-end Phase 8 API smoke: quote -> cart -> checkout -> reorder."""
import json
import os
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
CUSTOMER_EMAIL = os.environ.get("PIZZORA_QA_CUSTOMER_EMAIL", "qa.customer@pizzora.local")
CUSTOMER_PASSWORD = os.environ.get("PIZZORA_QA_CUSTOMER_PASSWORD")
if not CUSTOMER_PASSWORD:
    raise SystemExit("Set PIZZORA_QA_CUSTOMER_PASSWORD to the local non-production QA password")
CUSTOMER = (CUSTOMER_EMAIL, CUSTOMER_PASSWORD)


def call(method, path, body=None, token=None, extra_headers=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read().decode()
        raise RuntimeError(f"{method} {path} -> HTTP {err.code}: {raw}") from err


status, tokens = call("POST", "/api/v1/auth/login", {
    "email": CUSTOMER[0], "password": CUSTOMER[1],
})
assert status == 200
token = tokens["access_token"]
print(f"customer login: {status}")

status, menu = call("GET", "/api/v1/catalog/menu")
assert status == 200
product = menu["products"][0]
variant = product["variants"][0]
crust = menu["crusts"][0]
topping = menu["toppings"][0]
cheese = next(c for c in menu["cheeses"] if c["product_id"] == product["id"])
sauce = next(s for s in menu["sauces"] if s["product_id"] == product["id"])
standard = next(
    t for t in menu["standard_toppings"]
    if t["product_id"] == product["id"] and not t["is_default"]
)
hah = next(h for h in menu["half_and_half_configs"] if h["product_id"] == product["id"])
print(
    "menu arrays:",
    {k: len(menu[k]) for k in
     ("cheeses", "sauces", "standard_toppings", "half_and_half_configs")},
)

item = {
    "id": None,
    "product_id": product["id"],
    "variant_id": variant["id"],
    "crust_id": crust["id"],
    "topping_ids": [topping["id"]],
    "quantity": 1,
    "client_unit_price_paise": None,
    "cheese_id": cheese["id"],
    "sauce_id": sauce["id"],
    "standard_topping_ids": [standard["id"]],
    "half_and_half_config_id": hah["id"],
    "half_and_half_left_topping_ids": hah["left_topping_ids"],
    "half_and_half_right_topping_ids": hah["right_topping_ids"],
}

status, quote = call("POST", "/api/v1/pricing/quote", {
    "items": [item], "fulfillment_type": "PICKUP",
}, token)
assert status == 200
breakdown = quote["breakdown"]
extra_keys = (
    "cheese_extra_paise", "sauce_extra_paise", "standard_toppings_extra_paise",
    "half_and_half_surcharge_paise", "modifier_extra_paise",
)
print("quote breakdown:", {k: breakdown[k] for k in extra_keys})
assert breakdown["modifier_extra_paise"] > 0
assert breakdown["cheese_extra_paise"] > 0
assert breakdown["half_and_half_surcharge_paise"] > 0

status, cart = call("PUT", "/api/v1/cart", {"items": [item]}, token)
assert status == 200
status, cart = call("GET", "/api/v1/cart", token=token)
assert status == 200
view = cart["items"][0]
print("cart resolved modifiers:", {
    "cheese": view["cheese"]["name"],
    "sauce": view["sauce"]["name"],
    "standards": [v["name"] for v in view["standard_toppings"]],
    "half_and_half": view["half_and_half"]["name"],
})
assert view["cheese"]["cheese_id"] == cheese["id"]
assert view["half_and_half"]["half_and_half_config_id"] == hah["id"]

status, order = call("POST", "/api/v1/checkout", {
    "items": [item],
    "fulfillment_type": "PICKUP",
    "payment_method": "COD",
    "clear_cart": True,
}, token, extra_headers={"Idempotency-Key": f"phase8-smoke-{int(__import__('time').time())}"})
assert status == 201 or status == 200
order_id = order.get("order_id") or order.get("id") or order["order"]["id"]
print(f"checkout order: {order_id}")

status, reorder = call("POST", f"/api/v1/orders/{order_id}/reorder", token=token)
assert status == 200
print("reorder top-level keys:", sorted(reorder.keys()))
assert reorder["canReorder"] is True
assert reorder["items"] and reorder["items"][0]["isAvailable"] is True
assert "unavailable" in reorder
print("reorder item keys:", sorted(reorder["items"][0].keys()))
print("LOCAL API SMOKE PASS")
