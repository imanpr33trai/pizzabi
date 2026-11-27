#!/usr/bin/env bash
# Non-production QA catalog seed against the local Pizzora Axum API and pizzora-pg18.
# Safe to re-run: checks for the QA category before creating rows.
set -euo pipefail

API="${API:-http://127.0.0.1:8080}"
DB_URL_ARGS=(-U pizzora -d pizzora)

owner_email="${PIZZORA_QA_OWNER_EMAIL:-owner@pizzora.local}"
owner_password="${PIZZORA_QA_OWNER_PASSWORD:?set PIZZORA_QA_OWNER_PASSWORD to the local QA owner password}"
customer_email="${PIZZORA_QA_CUSTOMER_EMAIL:-qa.customer@pizzora.local}"
customer_password="${PIZZORA_QA_CUSTOMER_PASSWORD:?set PIZZORA_QA_CUSTOMER_PASSWORD to the local QA customer password}"

jq_get() { python3 -c 'import json,sys; print(json.load(sys.stdin)'"$1"')'; }

login_token() {
  local email="$1" password="$2"
  curl -fsS -X POST "$API/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$password\"}" |
    jq_get "['access_token']"
}

owner_token="$(login_token "$owner_email" "$owner_password")"
auth=(-H "Authorization: Bearer $owner_token" -H 'Content-Type: application/json')

existing="$(docker exec pizzora-pg18 psql "${DB_URL_ARGS[@]}" -Atc \
  "SELECT count(*) FROM categories WHERE name = 'Phase 8 QA'")"
if [[ "$existing" != "0" ]]; then
  echo "QA catalog already present; nothing to seed."
  exit 0
fi

category_id="$(curl -fsS -X POST "$API/api/v1/owner/categories" "${auth[@]}" \
  -d '{"name":"Phase 8 QA","sort_order":1,"is_active":true}' | jq_get "['id']")"

create_product() {
  local name="$1" veg="$2" price="$3"
  curl -fsS -X POST "$API/api/v1/owner/products" "${auth[@]}" \
    -d "{\"category_id\":\"$category_id\",\"name\":\"$name\",\
\"description\":\"Seeded for Phase 8 runtime QA\",\"is_veg\":$veg,\
\"is_active\":true,\"is_taxable\":true,\
\"variants\":[{\"size_code\":\"REGULAR\",\"price_paise\":$price,\"is_active\":true},\
{\"size_code\":\"MEDIUM\",\"price_paise\":$((price + 15000)),\"is_active\":true},\
{\"size_code\":\"LARGE\",\"price_paise\":$((price + 30000)),\"is_active\":true}]}" |
    jq_get "['id']"
}

margherita_id="$(create_product 'Margherita Royale' true 24900)"
pepperoni_id="$(create_product 'Smoky Pepperoni' false 34900)"

for crust in 'Hand Tossed:0' 'Cheese Burst:9900' 'Thin & Crispy:2000'; do
  name="${crust%%:*}"
  price="${crust##*:}"
  curl -fsS -X POST "$API/api/v1/owner/crusts" "${auth[@]}" \
    -d "{\"name\":\"$name\",\"extra_price_paise\":$price,\"is_active\":true}" >/dev/null
done

topping_ids=()
for topping in 'Extra Cheese:5500' 'Jalapenos:3500' 'Smoked Olives:4500'; do
  name="${topping%%:*}"
  price="${topping##*:}"
  tid="$(curl -fsS -X POST "$API/api/v1/owner/toppings" "${auth[@]}" \
    -d "{\"name\":\"$name\",\"is_veg\":true,\"extra_price_paise\":$price,\"is_active\":true}" |
    jq_get "['id']")"
  topping_ids+=("$tid")
done

curl -fsS -X POST "$API/api/v1/owner/delivery-zones" "${auth[@]}" \
  -d '{"pincode":"560001","area_name":"Indiranagar QA","min_order_paise":20000,"delivery_fee_paise":4000,"estimated_mins":30,"is_active":true}' >/dev/null

curl -fsS -X POST "$API/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$customer_email\",\"password\":\"$customer_password\",\
\"full_name\":\"Phase 8 QA Customer\",\"phone\":\"9876543210\"}" >/dev/null || true
docker exec pizzora-pg18 psql "${DB_URL_ARGS[@]}" -c \
  "UPDATE users SET email_verified_at = now() WHERE email = '$customer_email'" >/dev/null

docker exec -i pizzora-pg18 psql "${DB_URL_ARGS[@]}" <<SQL
 INSERT INTO product_toppings (product_id, topping_id, sort_order)
 SELECT p.id, t.id, t.row_number
 FROM (VALUES ('$margherita_id'::uuid, 1), ('$pepperoni_id'::uuid, 2)) AS p(id, sort_order)
 CROSS JOIN LATERAL (
   SELECT id, row_number() OVER (ORDER BY name)::int AS row_number
   FROM toppings WHERE name IN ('Extra Cheese','Jalapenos','Smoked Olives')
 ) t
 ON CONFLICT DO NOTHING;

 INSERT INTO cheeses (product_id, name, extra_price_paise, sort_order)
 SELECT id, 'Mozzarella', 2000, 1 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;
 INSERT INTO cheeses (product_id, name, extra_price_paise, sort_order)
 SELECT id, 'None', 0, 2 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;

 INSERT INTO sauces (product_id, name, extra_price_paise, sort_order)
 SELECT id, 'Tomato', 0, 1 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;
 INSERT INTO sauces (product_id, name, extra_price_paise, sort_order)
 SELECT id, 'Pesto', 1500, 2 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;

 INSERT INTO standard_toppings (product_id, name, is_veg, extra_price_paise, is_default, sort_order)
 SELECT id, 'Basil', TRUE, 1000, TRUE, 1 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;
 INSERT INTO standard_toppings (product_id, name, is_veg, extra_price_paise, is_default, sort_order)
 SELECT id, 'Jalapeno', TRUE, 800, FALSE, 2 FROM products WHERE id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;

 INSERT INTO half_and_half_configs (
   product_id, name, left_topping_ids, right_topping_ids,
   half_and_half_surcharge_paise, sort_order
 )
 SELECT p.id,
        'Two Flavours',
        to_jsonb(ARRAY(SELECT id FROM toppings WHERE name = 'Extra Cheese')),
        to_jsonb(ARRAY(SELECT id FROM toppings WHERE name = 'Jalapenos')),
        5000,
        1
 FROM products p
 WHERE p.id IN ('$margherita_id'::uuid, '$pepperoni_id'::uuid)
 ON CONFLICT DO NOTHING;
SQL

echo "QA catalog seeded."
echo "category_id=$category_id"
echo "margherita_id=$margherita_id"
echo "pepperoni_id=$pepperoni_id"
echo "customer_email=$customer_email"
echo "customer_password=[redacted]"
