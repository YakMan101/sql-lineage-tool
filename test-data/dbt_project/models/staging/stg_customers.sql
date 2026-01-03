-- Staging model: stg_customers
-- Cleans and renames columns from raw_customers
-- 
-- LINEAGE: This model reads from raw_customers
-- COLUMN EVOLUTION:
--   id -> customer_id
--   first_nm -> first_name  
--   last_nm -> last_name
--   email_addr -> email
--   is_active (int) -> is_active (boolean)

{{ config(materialized='view') }}

SELECT
    id AS customer_id,
    first_name,
    last_name,
    email,
    created_at
FROM {{ ref('raw_customers') }}
WHERE id IS NOT NULL
