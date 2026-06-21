{{ config(materialized='view') }}

SELECT
    order_id,
    customer_id,
    CAST(order_date AS DATE) AS order_date,
    UPPER(status) AS status,
    CAST(amount AS NUMERIC) AS amount,
    CASE
        WHEN amount >= 500 THEN 'high'
        WHEN amount >= 100 THEN 'medium'
        ELSE 'low'
    END AS order_value_tier,
    CASE
        WHEN UPPER(status) IN ('CANCELLED', 'RETURNED') THEN TRUE
        ELSE FALSE
    END AS is_closed,
    COALESCE(CAST(amount AS NUMERIC), 0) AS amount_with_default,
    CONCAT(CAST(order_id AS STRING), '-', CAST(customer_id AS STRING)) AS order_key
FROM {{ source('raw', 'orders') }}
