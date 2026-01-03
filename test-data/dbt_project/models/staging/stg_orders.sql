-- Staging model: stg_orders
-- Cleans and renames columns from raw_orders
--
-- LINEAGE: This model reads from raw_orders
-- COLUMN EVOLUTION:
--   ord_id -> order_id
--   cust_id -> customer_id
--   prod_id -> product_id
--   amt -> order_amount
--   qty -> quantity
--   ord_dt -> order_date
--   ship_dt -> shipped_date
--   status_cd -> order_status

{{ config(materialized='view') }}

SELECT
    ord_id AS order_id,
    cust_id AS customer_id,
    amt AS order_amount,
    ord_date AS order_date,
    status
FROM {{ ref('raw_orders') }}
WHERE ord_id IS NOT NULL
