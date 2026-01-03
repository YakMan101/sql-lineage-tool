-- Raw products table (simulates source data)

CREATE TABLE raw_products (
    prod_id INTEGER PRIMARY KEY,
    prod_nm VARCHAR(200),
    prod_cat VARCHAR(100),
    unit_price DECIMAL(10, 2),
    is_available INTEGER
);

-- Sample data for testing
INSERT INTO raw_products VALUES 
    (501, 'Widget Pro', 'Electronics', 49.99, 1),
    (502, 'Gadget Max', 'Electronics', 149.50, 1),
    (503, 'Tool Basic', 'Tools', 29.99, 0);
