# Sample dbt Models for Testing

## Structure

```
test-data/
├── sources/           # Raw source tables
│   ├── raw_customers.sql
│   ├── raw_orders.sql
│   └── raw_products.sql
└── models/
    ├── staging/       # Clean raw data
    │   ├── stg_customers.sql
    │   ├── stg_orders.sql
    │   └── stg_products.sql
    ├── intermediate/  # Transformations
    │   ├── int_orders_enriched.sql
    │   └── int_customer_orders.sql
    └── marts/         # Analytics tables
        ├── fct_orders.sql
        └── dim_customers.sql
```

## Lineage Flow

```
raw_customers ─────┬──> stg_customers ──┬──> int_customer_orders ──> dim_customers
                   │                    │
raw_orders ────────┼──> stg_orders ─────┼──> int_orders_enriched ──> fct_orders
                   │                    │
raw_products ──────┴──> stg_products ───┘
```

## Column Evolution Examples

| Source Column      | Staging Column      | Final Column        |
|--------------------|---------------------|---------------------|
| raw_customers.id   | stg_customers.customer_id | dim_customers.customer_key |
| raw_orders.ord_id  | stg_orders.order_id | fct_orders.order_key |
| raw_orders.amt     | stg_orders.order_amount | fct_orders.total_amount |

## Testing Endpoints

```bash
# Track lineage
curl -X POST http://localhost:8080/api/lineage/track \
  -H "Content-Type: text/plain" \
  --data-binary "@models/staging/stg_orders.sql"

# Column evolution
curl -X POST http://localhost:8080/api/lineage/evolution \
  -H "Content-Type: text/plain" \
  --data-binary "@models/intermediate/int_orders_enriched.sql"
```
