{{ config(materialized='view') }}

SELECT
    o.order_id,
    o.order_date,
    o.status,
    o.amount,
    c.customer_id,
    c.email,
    c.first_name,
    c.last_name,
    c.country
FROM {{ ref('stg_orders') }} AS o
LEFT JOIN {{ ref('stg_customers') }} AS c
    ON o.customer_id = c.customer_id
