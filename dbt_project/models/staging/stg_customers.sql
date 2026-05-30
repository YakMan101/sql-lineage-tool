{{ config(materialized='view') }}

SELECT
    customer_id,
    LOWER(email) AS email,
    first_name,
    last_name,
    CAST(created_at AS TIMESTAMP) AS created_at,
    country
FROM {{ source('raw', 'customers') }}
