-- Staging model: stg_products
-- Cleans and renames columns from raw_products
--
-- LINEAGE: This model reads from raw_products
-- COLUMN EVOLUTION:
--   prod_id -> product_id
--   prod_nm -> product_name
--   prod_cat -> category
--   unit_price -> price
--   is_available (int) -> is_available (boolean)

{{ config(materialized='view') }}

SELECT
    prod_id AS product_id,
    prod_name AS product_name,
    category,
    price
FROM {{ ref('raw_products') }}
WHERE prod_id IS NOT NULL
