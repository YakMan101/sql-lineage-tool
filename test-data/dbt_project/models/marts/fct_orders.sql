-- Mart model: fct_orders (Fact table)
-- Final orders fact table for analytics
--
-- LINEAGE: This model reads from int_orders_enriched
-- COLUMN EVOLUTION:
--   order_id -> order_key (surrogate key naming)
--   line_total -> total_amount

{{ config(materialized='table') }}

SELECT
    -- Surrogate key
    {{ dbt_utils.generate_surrogate_key(['order_id']) }} AS order_key,
    
    -- Foreign keys
    order_id,
    customer_id AS customer_key,
    
    -- Customer dimensions
    first_name,
    last_name,
    email,
    order_status,
    
    -- Measures
    line_total AS total_amount,
    
    -- Dates
    order_date
    
FROM {{ ref('int_orders_enriched') }}
WHERE order_status != 'cancelled'
