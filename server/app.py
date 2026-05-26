from flask import Flask, render_template, request, redirect, url_for, jsonify
from flask_socketio import SocketIO
import paho.mqtt.client as mqtt
import sqlite3
import json
import threading

app = Flask(__name__)
socketio = SocketIO(app, cors_allowed_origins="*")

DB_NAME = 'supermarket.db'
MQTT_BROKER = "127.0.0.1"

# ==========================================
# ENTERPRISE DATABASE BUSINESS LOGIC
# ==========================================

def get_product(rfid):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT name, price, type FROM products WHERE rfid_id=?", (rfid,))
    res = cursor.fetchone()
    conn.close()
    return res

def get_all_products():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT rfid_id, name, price, type FROM products ORDER BY name ASC")
    res = cursor.fetchall()
    conn.close()
    return res

def add_or_update_product(rfid, name, price, p_type):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("INSERT OR REPLACE INTO products (rfid_id, name, price, type) VALUES (?, ?, ?, ?)", (rfid, name, price, p_type))
    conn.commit()
    conn.close()

def delete_product(rfid):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("DELETE FROM products WHERE rfid_id=?", (rfid,))
    conn.commit()
    conn.close()

def get_all_customers():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT nif, name, email, pin FROM customers ORDER BY name ASC")
    res = cursor.fetchall()
    conn.close()
    return res

def add_or_update_customer(nif, name, email, pin):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("INSERT OR REPLACE INTO customers (nif, name, email, pin) VALUES (?, ?, ?, ?)", (nif, name, email, pin))
    conn.commit()
    conn.close()

def delete_customer(nif):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("DELETE FROM customers WHERE nif=?", (nif,))
    conn.commit()
    conn.close()

def get_all_scales():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT scale_id, location, ip_address, rfid_tag FROM scales ORDER BY scale_id ASC")
    res = cursor.fetchall()
    conn.close()
    return res

def add_or_update_scale(scale_id, location, ip, rfid_tag):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("INSERT OR REPLACE INTO scales (scale_id, location, ip_address, rfid_tag) VALUES (?, ?, ?, ?)", (scale_id, location, ip, rfid_tag))
    conn.commit()
    conn.close()

def delete_scale(scale_id):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("DELETE FROM scales WHERE scale_id=?", (scale_id,))
    conn.commit()
    conn.close()

def add_to_cart(nif, scale_id, name, weight, price):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("INSERT INTO active_carts (customer_nif, scale_id, product_name, weight, final_price) VALUES (?, ?, ?, ?, ?)",
                   (nif, scale_id, name, weight, price))
    conn.commit()
    conn.close()

def archive_cart(nif):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''INSERT INTO archive (customer_nif, scale_id, product_name, weight, final_price)
                      SELECT customer_nif, scale_id, product_name, weight, final_price 
                      FROM active_carts WHERE customer_nif=?''', (nif,))
    cursor.execute("DELETE FROM active_carts WHERE customer_nif=?", (nif,))
    conn.commit()
    conn.close()

def get_active_carts_formatted():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT c.customer_nif, cust.name, c.product_name, c.weight, c.final_price, c.scale_id 
                      FROM active_carts c 
                      LEFT JOIN customers cust ON c.customer_nif = cust.nif''')
    rows = cursor.fetchall()
    conn.close()
    
    carts = {}
    for row in rows:
        nif, cust_name, prod_name, weight, price, s_id = row
        label = f"{cust_name if cust_name else 'Unregistered'} ({nif})"
        if label not in carts:
            carts[label] = []
        carts[label].append({"name": prod_name, "weight": weight, "price": price, "scale": s_id})
    return carts

def get_full_history():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT a.customer_nif, c.name, a.scale_id, a.product_name, a.weight, a.final_price, a.archived_at 
                      FROM archive a LEFT JOIN customers c ON a.customer_nif = c.nif ORDER BY a.archived_at DESC''')
    res = cursor.fetchall()
    conn.close()
    return res

# ==========================================
# MQTT STREAM INTERCEPTOR & TELEMETRY
# ==========================================
def on_connect(client, userdata, flags, rc):
    print("[MQTT] Successfully connected to infrastructure broker.")
    client.subscribe("supermercado/balanca/+/reading")
    client.subscribe("supermercado/android/+/reset")

def on_message(client, userdata, msg):
    topic = msg.topic.split('/')
    try:
        if "leitura" in topic:
            scale_id_raw = topic[2] 
            data = json.loads(msg.payload)
            rfid = data.get('rfid').strip().upper()
            weight = float(data.get('weight'))
            
            # Simulated matching algorithm: Fallback to the first customer in the database
            conn = sqlite3.connect(DB_NAME)
            cursor = conn.cursor()
            cursor.execute("SELECT nif FROM customers LIMIT 1")
            mock_user = cursor.fetchone()
            conn.close()
            target_nif = mock_user[0] if mock_user else "GUEST_NIF"

            product = get_product(rfid)
            if product:
                p_name, base_price, p_type = product
                final_price = round((weight / 1000.0) * base_price, 2) if p_type == 'bulk' else round(base_price, 2)
                
                add_to_cart(target_nif, scale_id_raw, p_name, weight, final_price)
                socketio.emit('update_event', get_active_carts_formatted())
            else:
                socketio.emit('unknown_rfid_event', {'rfid': rfid, 'scale_id': scale_id_raw})
                
        elif "reset" in topic:
            customer_nif = topic[2]
            archive_cart(customer_nif)
            socketio.emit('update_event', get_active_carts_formatted())

    except Exception as e:
        print(f"[MQTT ERROR] Streaming pipeline failure: {e}")

mqtt_client = mqtt.Client()
mqtt_client.on_connect = on_connect
mqtt_client.on_message = on_message
mqtt_client.connect(MQTT_BROKER, 1883, 60)
threading.Thread(target=mqtt_client.loop_forever, daemon=True).start()

# ==========================================
# MOBILE APPLICATION REST API ENDPOINTS
# ==========================================
@app.route('/api/auth/login', methods=['POST'])
def api_login():
    data = request.json
    nif = data.get('nif')
    pin = data.get('pin')
    
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM customers WHERE nif=? AND pin=?", (nif, pin))
    user = cursor.fetchone()
    conn.close()
    
    if user:
        return jsonify({"status": "success", "client_name": user[0]})
    return jsonify({"status": "unauthorized", "message": "Invalid credentials provided."}), 401

# ==========================================
# WEB CONTROLLER TERMINALS (UI ROUTING)
# ==========================================
@socketio.on('connect')
def handle_connect():
    socketio.emit('update_event', get_active_carts_formatted())

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/admin')
def admin_dashboard():
    history_logs = get_full_history()
    total_revenue = sum(item[5] for item in history_logs)
    
    return render_template('admin.html', 
                           products=get_all_products(),
                           customers=get_all_customers(),
                           scales=get_all_scales(),
                           active_carts=get_active_carts_formatted(),
                           history=history_logs,
                           revenue=total_revenue)

@app.route('/admin/product/save', methods=['POST'])
def admin_save_product():
    add_or_update_product(request.form.get('rfid').strip().upper(), request.form.get('name').strip(), float(request.form.get('price')), request.form.get('type'))
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/product/delete/<rfid>')
def admin_delete_product(rfid):
    delete_product(rfid)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/customer/save', methods=['POST'])
def admin_save_customer():
    add_or_update_customer(request.form.get('nif').strip(), request.form.get('name').strip(), request.form.get('email').strip(), request.form.get('pin').strip())
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/customer/delete/<nif>')
def admin_delete_customer(nif):
    delete_customer(nif)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/scale/save', methods=['POST'])
def admin_save_scale():
    add_or_update_scale(request.form.get('scale_id').strip().upper(), request.form.get('location').strip(), request.form.get('ip_address').strip(), request.form.get('rfid_tag').strip().upper())
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/scale/delete/<scale_id>')
def admin_delete_scale(scale_id):
    delete_scale(scale_id)
    return redirect(url_for('admin_dashboard'))

if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=5000, debug=True, use_reloader=False)