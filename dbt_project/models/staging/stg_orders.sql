{{ config(materialized='view') }}

SELECT
    order_id,
    customer_id,
    CAST(order_date AS DATE) AS order_date,
    UPPER(status) AS status,
    CAST(amount AS NUMERIC) AS amount
FROM {{ source('raw', 'orders') }}
