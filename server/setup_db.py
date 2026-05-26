import sqlite3
import os

# Remove old database if exists to ensure a clean setup
if os.path.exists('supermarket.db'):
    os.remove('supermarket.db')

conn = sqlite3.connect('supermarket.db')
cursor = conn.cursor()

# 1. PRODUCTS TABLE
cursor.execute('''CREATE TABLE IF NOT EXISTS products (
                    rfid_id TEXT PRIMARY KEY,
                    name TEXT,
                    price REAL,
                    type TEXT DEFAULT 'bulk')''') # 'bulk' or 'unit'

# 2. CUSTOMERS TABLE (NIF + PIN Authentication)
cursor.execute('''CREATE TABLE IF NOT EXISTS customers (
                    nif TEXT PRIMARY KEY,
                    name TEXT,
                    email TEXT,
                    pin TEXT)''')

# 3. SCALES TABLE (Hardware Tracking via IP and RFID tag Pairing)
cursor.execute('''CREATE TABLE IF NOT EXISTS scales (
                    scale_id TEXT PRIMARY KEY,
                    location TEXT,
                    ip_address TEXT,
                    rfid_tag TEXT UNIQUE)''')

# 4. ACTIVE CARTS TABLE
cursor.execute('''CREATE TABLE IF NOT EXISTS active_carts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_nif TEXT,
                    scale_id TEXT,
                    product_name TEXT,
                    weight REAL,
                    final_price REAL)''')

# 5. ARCHIVED ORDERS TABLE (Historical Sales Log)
cursor.execute('''CREATE TABLE IF NOT EXISTS archive (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_nif TEXT,
                    scale_id TEXT,
                    product_name TEXT,
                    weight REAL,
                    final_price REAL,
                    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

# --- INITIAL DEMO DATA ---
cursor.execute("INSERT INTO products VALUES ('A1B2C3D4', 'Organic Apples', 2.50, 'bulk')")
cursor.execute("INSERT INTO products VALUES ('04A1B2C3', 'Mineral Water 1.5L', 0.65, 'unit')")

cursor.execute("INSERT INTO customers VALUES ('251342990', 'Martim Silva', 'martim@email.com', '1234')")
cursor.execute("INSERT INTO customers VALUES ('200100400', 'John Doe', 'john.doe@email.com', '4321')")

cursor.execute("INSERT INTO scales VALUES ('SCALE_01', 'Produce Section 1', '192.168.1.51', 'E2004100')")
cursor.execute("INSERT INTO scales VALUES ('SCALE_02', 'Beverage Aisle 2', '192.168.1.52', 'F3005200')")

conn.commit()
conn.close()
print("Success: Professional Relational Database Schema 'supermarket.db' initialized.")