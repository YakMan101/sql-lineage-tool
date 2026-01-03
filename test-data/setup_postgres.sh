#!/bin/bash
# PostgreSQL setup script for dbt lineage tool

set -e

echo "🔧 Setting up PostgreSQL for dbt..."

# Start PostgreSQL service
echo "Starting PostgreSQL service..."
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Create database and user
echo "Creating database and user..."
sudo -u postgres psql <<EOF
-- Create user if not exists
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'dbt_user') THEN
    CREATE USER dbt_user WITH PASSWORD 'dbt_password';
    RAISE NOTICE 'User dbt_user created';
  ELSE
    RAISE NOTICE 'User dbt_user already exists, skipping';
  END IF;
END
\$\$;

-- Create database if not exists
SELECT 'CREATE DATABASE dbt_lineage_test OWNER dbt_user'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'dbt_lineage_test')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE dbt_lineage_test TO dbt_user;
EOF

# Connect to the database and create schemas
echo "Creating schemas..."
PGPASSWORD=dbt_password psql -h localhost -U dbt_user -d dbt_lineage_test <<EOF
CREATE SCHEMA IF NOT EXISTS raw;
CREATE SCHEMA IF NOT EXISTS staging;
CREATE SCHEMA IF NOT EXISTS intermediate;
CREATE SCHEMA IF NOT EXISTS marts;
CREATE SCHEMA IF NOT EXISTS analytics;

-- Grant permissions
GRANT ALL ON SCHEMA raw TO dbt_user;
GRANT ALL ON SCHEMA staging TO dbt_user;
GRANT ALL ON SCHEMA intermediate TO dbt_user;
GRANT ALL ON SCHEMA marts TO dbt_user;
GRANT ALL ON SCHEMA analytics TO dbt_user;
EOF

echo "✅ PostgreSQL setup complete!"
echo ""
echo "Database: dbt_lineage_test"
echo "User: dbt_user"
echo "Password: dbt_password"
echo ""
echo "Next steps:"
echo "1. Update test-data/profiles.yml with your password"
echo "2. Run: dbt debug (to test connection)"
echo "3. Run: dbt seed (to load CSV data)"
echo "4. Run: dbt run (to build models)"
