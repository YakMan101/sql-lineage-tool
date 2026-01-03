-- Raw orders table (simulates source data)

CREATE TABLE raw_orders (
    ord_id INTEGER PRIMARY KEY,
    cust_id INTEGER,
    prod_id INTEGER,
    amt DECIMAL(10, 2),
    qty INTEGER,
    ord_dt DATE,
    ship_dt DATE,
    status_cd VARCHAR(10)
);

-- Sample data for testing
INSERT INTO raw_orders VALUES 
    (101, 1, 501, 99.99, 2, '2024-06-01', '2024-06-03', 'SHIPPED'),
    (102, 1, 502, 149.50, 1, '2024-06-05', NULL, 'PENDING'),
    (103, 2, 501, 99.99, 1, '2024-06-10', '2024-06-12', 'SHIPPED');
