{{ config(materialized='table') }}

SELECT
    c.customer_id,
    c.email,
    c.first_name,
    c.last_name,
    c.country,
    c.created_at,
    COUNT(DISTINCT o.order_id) AS total_orders,
    SUM(o.amount) AS lifetime_value,
    MAX(o.order_date) AS last_order_date
FROM {{ ref('stg_customers') }} AS c
LEFT JOIN {{ ref('stg_orders') }} AS o
    ON c.customer_id = o.customer_id
GROUP BY
    c.customer_id,
    c.email,
    c.first_name,
    c.last_name,
    c.country,
    c.created_at
