{{ config(materialized='table') }}

SELECT
    o.order_id,
    o.order_date,
    o.status,
    o.customer_id,
    o.first_name,
    o.last_name,
    o.country,
    SUM(i.line_total) AS total_order_value,
    COUNT(i.product_id) AS num_items
FROM {{ ref('int_orders_with_customers') }} AS o
LEFT JOIN {{ ref('int_order_items') }} AS i
    ON o.order_id = i.order_id
GROUP BY
    o.order_id,
    o.order_date,
    o.status,
    o.customer_id,
    o.first_name,
    o.last_name,
    o.country
