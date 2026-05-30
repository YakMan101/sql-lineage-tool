{{ config(materialized='view') }}

SELECT
    product_id,
    product_name,
    category,
    CAST(price AS NUMERIC) AS price
FROM {{ source('raw', 'products') }}
