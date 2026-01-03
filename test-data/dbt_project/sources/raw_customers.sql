-- Raw customers table (simulates source data)
-- This would typically be loaded from an external system

CREATE TABLE raw_customers (
    id INTEGER PRIMARY KEY,
    first_nm VARCHAR(100),
    last_nm VARCHAR(100),
    email_addr VARCHAR(255),
    created_dt TIMESTAMP,
    updated_dt TIMESTAMP,
    is_active INTEGER  -- 0 or 1
);

-- Sample data for testing
INSERT INTO raw_customers VALUES 
    (1, 'John', 'Doe', 'john.doe@email.com', '2024-01-01', '2024-06-15', 1),
    (2, 'Jane', 'Smith', 'jane.smith@email.com', '2024-02-15', '2024-06-20', 1),
    (3, 'Bob', 'Wilson', 'bob.w@email.com', '2024-03-10', NULL, 0);
