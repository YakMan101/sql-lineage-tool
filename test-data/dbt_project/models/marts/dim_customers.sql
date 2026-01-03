-- Mart model: dim_customers (Dimension table)
-- Final customers dimension table for analytics
--
-- LINEAGE: This model reads from int_customer_orders
-- COLUMN EVOLUTION:
--   customer_id -> customer_key (surrogate key naming)
--   first_name + last_name -> full_name (concatenation)

{{ config(materialized='table') }}

SELECT
    -- Surrogate key
    {{ dbt_utils.generate_surrogate_key(['customer_id']) }} AS customer_key,
    
    -- Natural key
    customer_id,
    
    -- Attributes
    first_name,
    last_name,
    CONCAT(first_name, ' ', last_name) AS full_name,
    email,
    
    -- Metrics
    total_orders,
    total_spent,
    CASE 
        WHEN total_orders >= 10 THEN 'platinum'
        WHEN total_orders >= 5 THEN 'gold'
        WHEN total_orders >= 1 THEN 'silver'
        ELSE 'bronze'
    END AS customer_tier,
    
    -- Dates
    first_order_date,
    last_order_date,
    
    -- Flags
    CASE WHEN total_orders > 0 THEN TRUE ELSE FALSE END AS has_ordered
    
FROM {{ ref('int_customer_orders') }}
