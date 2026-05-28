import sqlite3
import os

# Remove old database if exists to ensure a clean setup
if os.path.exists('supermarket.db'):
    os.remove('supermarket.db')

conn = sqlite3.connect('supermarket.db')
cursor = conn.cursor()

# 1. PRODUCTS TABLE
cursor.execute('''CREATE TABLE IF NOT EXISTS products (
                    product_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rfid_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    unit TEXT NOT NULL CHECK (unit IN ('kg', 'unit')),
                    price_per_unit_cents INTEGER NOT NULL)''')

# 2. CUSTOMERS TABLE (NIF + PIN Authentication)
cursor.execute('''CREATE TABLE IF NOT EXISTS customers (
                    nif TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    email TEXT NOT NULL,
                    pin TEXT NOT NULL)''')

# 3. SCALES TABLE (Hardware Tracking via IP and RFID tag Pairing)
cursor.execute('''CREATE TABLE IF NOT EXISTS scales (
                    scale_id TEXT PRIMARY KEY,
                    location TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    rfid_tag TEXT NOT NULL UNIQUE)''')

# 4. ACTIVE CARTS TABLE
cursor.execute('''CREATE TABLE IF NOT EXISTS active_carts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_nif TEXT NOT NULL,
                    scale_id TEXT NOT NULL,
                    product_id INTEGER NOT NULL,
                    product_name TEXT NOT NULL,
                    weight_grams INTEGER NOT NULL,
                    final_price_cents INTEGER NOT NULL,
                    reading_id INTEGER,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

# 5. ARCHIVED ORDERS TABLE (Historical Sales Log)
cursor.execute('''CREATE TABLE IF NOT EXISTS archive (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_nif TEXT NOT NULL,
                    scale_id TEXT NOT NULL,
                    product_id INTEGER NOT NULL,
                    product_name TEXT NOT NULL,
                    weight_grams INTEGER NOT NULL,
                    final_price_cents INTEGER NOT NULL,
                    reading_id INTEGER,
                    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

# 6. SCALE ASSIGNMENTS (Persistence with TTL/heartbeat)
cursor.execute('''CREATE TABLE IF NOT EXISTS scale_assignments (
                    scale_id TEXT PRIMARY KEY,
                    customer_nif TEXT NOT NULL,
                    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

# 7. READINGS LOG (Operational Timeline)
cursor.execute('''CREATE TABLE IF NOT EXISTS readings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scale_id TEXT NOT NULL,
                    rfid TEXT NOT NULL,
                    weight_grams INTEGER NOT NULL,
                    matched_product_id INTEGER,
                    matched_product_name TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

# --- INITIAL DEMO DATA ---
cursor.execute("INSERT INTO products (rfid_id, name, unit, price_per_unit_cents) VALUES ('A1B2C3D4', 'Organic Apples', 'kg', 250)")
cursor.execute("INSERT INTO products (rfid_id, name, unit, price_per_unit_cents) VALUES ('04A1B2C3', 'Mineral Water 1.5L', 'unit', 65)")

cursor.execute("INSERT INTO customers VALUES ('251342990', 'Martim Silva', 'martim@email.com', '1234')")
cursor.execute("INSERT INTO customers VALUES ('200100400', 'John Doe', 'john.doe@email.com', '4321')")

cursor.execute("INSERT INTO scales VALUES ('SCALE_01', 'Produce Section 1', '192.168.0.10', 'E2004100')")
cursor.execute("INSERT INTO scales VALUES ('SCALE_02', 'Beverage Aisle 2', '192.168.0.11', 'F3005200')")

conn.commit()
conn.close()
print("Success: Professional Relational Database Schema 'supermarket.db' initialized.")