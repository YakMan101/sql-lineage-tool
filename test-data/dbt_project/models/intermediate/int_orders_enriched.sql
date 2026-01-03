-- Intermediate model: int_orders_enriched
-- Joins orders with customers to add customer details
--
-- LINEAGE: This model reads from stg_orders AND stg_customers
-- COLUMN EVOLUTION:
--   stg_orders.order_amount = line_total (renamed)

{{ config(materialized='table') }}

SELECT
    o.order_id,
    o.customer_id,
    c.first_name,
    c.last_name,
    c.email,
    o.order_amount AS line_total,
    o.order_date,
    o.status AS order_status
FROM {{ ref('stg_orders') }} o
LEFT JOIN {{ ref('stg_customers') }} c 
    ON o.customer_id = c.customer_id
