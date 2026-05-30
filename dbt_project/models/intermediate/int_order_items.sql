{{ config(materialized='view') }}

SELECT
    oi.order_id,
    oi.product_id,
    oi.quantity,
    p.product_name,
    p.category,
    p.price,
    ROUND(oi.quantity * p.price, 2) AS line_total
FROM {{ source('raw', 'order_items') }} AS oi
LEFT JOIN {{ ref('stg_products') }} AS p
    ON oi.product_id = p.product_id
