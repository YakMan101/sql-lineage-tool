-- Intermediate model: int_customer_orders
-- Aggregates order data per customer
--
-- LINEAGE: This model reads from stg_orders AND stg_customers
-- COLUMN EVOLUTION:
--   Aggregations create new columns: total_orders, total_spent, first_order_date, last_order_date

{{ config(materialized='table') }}

SELECT
    c.customer_id,
    c.first_name,
    c.last_name,
    c.email,
    COUNT(o.order_id) AS total_orders,
    SUM(o.order_amount) AS total_spent,
    MIN(o.order_date) AS first_order_date,
    MAX(o.order_date) AS last_order_date
FROM {{ ref('stg_customers') }} c
LEFT JOIN {{ ref('stg_orders') }} o 
    ON c.customer_id = o.customer_id
GROUP BY 
    c.customer_id,
    c.first_name,
    c.last_name,
    c.email
