from flask import Flask, render_template, request, redirect, url_for, jsonify, abort
from flask_socketio import SocketIO, join_room
import paho.mqtt.client as mqtt
import sqlite3
import json
import threading
import time
import os
import ipaddress
from itsdangerous import URLSafeTimedSerializer, BadSignature, SignatureExpired
from collections import deque
from datetime import datetime, timezone

app = Flask(__name__)
app.config['SECRET_KEY'] = os.environ.get('WC_SECRET_KEY') or os.urandom(32)
socketio = SocketIO(app, cors_allowed_origins="*")

DB_NAME = 'supermarket.db'
MQTT_BROKER = "127.0.0.1"
ASSIGNMENT_TTL_SEC = 45
READING_STALE_SEC = 10
PENDING_TTL_SEC = 15
MAX_WEIGHT_GRAMS = 30000
TOKEN_TTL_SEC = 60 * 60  # 1 hour
DEDUPE_WINDOW_SEC = 2
DEDUPE_WEIGHT_TOLERANCE_GRAMS = 5

_token_serializer = URLSafeTimedSerializer(app.config['SECRET_KEY'], salt='wc-auth-v1')


def validate_nif(nif: str) -> bool:
    return bool(nif) and nif.isdigit() and len(nif) == 9


def validate_pin(pin: str) -> bool:
    return bool(pin) and pin.isdigit() and len(pin) == 4


def issue_token(nif: str) -> str:
    return _token_serializer.dumps({"nif": nif})


def verify_token(token: str) -> str | None:
    try:
        payload = _token_serializer.loads(token, max_age=TOKEN_TTL_SEC)
        nif = (payload or {}).get('nif')
        return nif if validate_nif(nif) else None
    except (BadSignature, SignatureExpired):
        return None


def get_bearer_token() -> str | None:
    auth = request.headers.get('Authorization', '')
    if not auth:
        return None
    parts = auth.split(' ', 1)
    if len(parts) != 2:
        return None
    scheme, token = parts[0].strip(), parts[1].strip()
    if scheme.lower() != 'bearer' or not token:
        return None
    return token


def require_auth_nif() -> tuple[str | None, tuple[dict, int] | None]:
    token = get_bearer_token()
    if not token:
        return None, ({"status": "unauthorized", "message": "Missing token."}, 401)
    nif = verify_token(token)
    if not nif:
        return None, ({"status": "unauthorized", "message": "Invalid or expired token."}, 401)
    return nif, None

pending_requests = {}
pending_requests_lock = threading.Lock()

mqtt_connected = False
recent_errors = deque(maxlen=50)


def utc_now_iso():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def log_event(level: str, message: str, **fields):
    event = {
        "ts": utc_now_iso(),
        "level": level,
        "message": message,
        **fields,
    }
    try:
        print(json.dumps(event, ensure_ascii=False))
    except Exception:
        print(f"[{level.upper()}] {message} {fields}")

    if level.lower() in ("warn", "warning", "error"):
        recent_errors.appendleft(event)


def ensure_schema():
    if not os.path.exists(DB_NAME):
        log_event("warn", "db_missing", db=DB_NAME)
        return

    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    try:
        cursor.execute("PRAGMA table_info(active_carts)")
        active_cols = {row[1] for row in cursor.fetchall()}
        if "reading_id" not in active_cols:
            cursor.execute("ALTER TABLE active_carts ADD COLUMN reading_id INTEGER")
            log_event("info", "db_migration", table="active_carts", column="reading_id")

        cursor.execute("PRAGMA table_info(archive)")
        archive_cols = {row[1] for row in cursor.fetchall()}
        if "reading_id" not in archive_cols:
            cursor.execute("ALTER TABLE archive ADD COLUMN reading_id INTEGER")
            log_event("info", "db_migration", table="archive", column="reading_id")

        conn.commit()
    finally:
        conn.close()


ensure_schema()

# ==========================================
# ENTERPRISE DATABASE BUSINESS LOGIC
# ==========================================

def get_product(rfid):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT product_id, name, unit, price_per_unit_cents FROM products WHERE rfid_id=?", (rfid,))
    res = cursor.fetchone()
    conn.close()
    return res

def get_all_products():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT rfid_id, name, price_per_unit_cents, unit FROM products ORDER BY name ASC")
    res = cursor.fetchall()
    conn.close()
    return res

def add_or_update_product(rfid, name, unit, price_per_unit_cents):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        INSERT INTO products (rfid_id, name, unit, price_per_unit_cents)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(rfid_id) DO UPDATE SET
            name=excluded.name,
            unit=excluded.unit,
            price_per_unit_cents=excluded.price_per_unit_cents
        """,
        (rfid, name, unit, price_per_unit_cents)
    )
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

def create_customer(nif, name, email, pin):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    try:
        cursor.execute(
            "INSERT INTO customers (nif, name, email, pin) VALUES (?, ?, ?, ?)",
            (nif, name, email, pin)
        )
        conn.commit()
        return True
    except sqlite3.IntegrityError:
        return False
    finally:
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

def get_scale_assignment(scale_id):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        SELECT customer_nif,
               (strftime('%s','now') - strftime('%s', last_seen)) as last_seen_age
        FROM scale_assignments WHERE scale_id=?
        """,
        (scale_id,)
    )
    res = cursor.fetchone()
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

def add_to_cart(nif, scale_id, product_id, name, weight_grams, final_price_cents, reading_id=None):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("PRAGMA table_info(active_carts)")
    cols = {row[1] for row in cursor.fetchall()}
    if "reading_id" in cols:
        cursor.execute(
            "INSERT INTO active_carts (customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents, reading_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (nif, scale_id, product_id, name, weight_grams, final_price_cents, reading_id)
        )
    else:
        cursor.execute(
            "INSERT INTO active_carts (customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents) VALUES (?, ?, ?, ?, ?, ?)",
            (nif, scale_id, product_id, name, weight_grams, final_price_cents)
        )
    conn.commit()
    conn.close()

def log_reading(scale_id, rfid, weight_grams, product_id=None, product_name=None):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        INSERT INTO readings (scale_id, rfid, weight_grams, matched_product_id, matched_product_name)
        VALUES (?, ?, ?, ?, ?)
        """,
        (scale_id, rfid, weight_grams, product_id, product_name)
    )
    reading_id = cursor.lastrowid
    conn.commit()
    conn.close()
    return reading_id

def archive_cart(nif):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("PRAGMA table_info(archive)")
    cols = {row[1] for row in cursor.fetchall()}
    if "reading_id" in cols:
        cursor.execute('''INSERT INTO archive (customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents, reading_id)
                          SELECT customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents, reading_id
                          FROM active_carts WHERE customer_nif=?''', (nif,))
    else:
        cursor.execute('''INSERT INTO archive (customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents)
                          SELECT customer_nif, scale_id, product_id, product_name, weight_grams, final_price_cents
                          FROM active_carts WHERE customer_nif=?''', (nif,))
    cursor.execute("DELETE FROM active_carts WHERE customer_nif=?", (nif,))
    conn.commit()
    conn.close()


def is_duplicate_reading(scale_id, rfid, weight_grams):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        SELECT weight_grams,
               (strftime('%s','now') - strftime('%s', created_at)) as age_sec
        FROM readings
        WHERE scale_id=? AND rfid=?
        ORDER BY id DESC
        LIMIT 1
        """,
        (scale_id, rfid)
    )
    row = cursor.fetchone()
    conn.close()
    if not row:
        return False
    last_weight, age_sec = row
    try:
        age_sec = int(age_sec)
    except Exception:
        return False

    if age_sec <= DEDUPE_WINDOW_SEC and abs(int(last_weight) - int(weight_grams)) <= DEDUPE_WEIGHT_TOLERANCE_GRAMS:
        return True
    return False

def get_active_carts_formatted():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT c.customer_nif, cust.name, c.product_name, c.weight_grams, c.final_price_cents, c.scale_id 
                      FROM active_carts c 
                      LEFT JOIN customers cust ON c.customer_nif = cust.nif''')
    rows = cursor.fetchall()
    conn.close()
    
    carts = {}
    for row in rows:
        nif, cust_name, prod_name, weight_grams, price_cents, s_id = row
        label = f"{cust_name if cust_name else 'Unregistered'} ({nif})"
        if label not in carts:
            carts[label] = []
        carts[label].append({
            "name": prod_name,
            "weight": weight_grams,
            "price": price_cents / 100.0,
            "scale": s_id
        })
    return carts

def get_full_history():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT a.customer_nif, c.name, a.scale_id, a.product_name, a.weight_grams, a.final_price_cents, a.archived_at 
                      FROM archive a LEFT JOIN customers c ON a.customer_nif = c.nif ORDER BY a.archived_at DESC''')
    res = cursor.fetchall()
    conn.close()
    return res

def get_recent_reads(limit=10):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        '''SELECT scale_id, rfid, weight_grams, matched_product_name, created_at
           FROM readings ORDER BY created_at DESC LIMIT ?''',
        (limit,)
    )
    rows = cursor.fetchall()
    conn.close()

    return [
        {
            "scale_id": row[0],
            "rfid": row[1],
            "weight_grams": row[2],
            "product_name": row[3],
            "created_at": row[4]
        }
        for row in rows
    ]

def get_scale_statuses():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        '''SELECT s.scale_id, s.location, s.ip_address, s.rfid_tag,
                  sa.customer_nif, sa.last_seen,
                  (strftime('%s','now') - strftime('%s', sa.last_seen)) as last_seen_age,
                  r.last_reading,
                  (strftime('%s','now') - strftime('%s', r.last_reading)) as last_reading_age
           FROM scales s
           LEFT JOIN scale_assignments sa ON s.scale_id = sa.scale_id
           LEFT JOIN (
               SELECT scale_id, MAX(created_at) as last_reading
               FROM readings
               GROUP BY scale_id
           ) r ON s.scale_id = r.scale_id
           ORDER BY s.scale_id ASC'''
    )
    rows = cursor.fetchall()
    conn.close()

    statuses = []
    for row in rows:
        (
            scale_id,
            location,
            ip_address,
            rfid_tag,
            customer_nif,
            last_seen,
            last_seen_age,
            last_reading,
            last_reading_age
        ) = row

        assigned = customer_nif if (last_seen_age is not None and last_seen_age <= ASSIGNMENT_TTL_SEC) else None
        if last_reading_age is None:
            health = "idle"
        elif last_reading_age > READING_STALE_SEC:
            health = "stale"
        else:
            health = "live"

        statuses.append({
            "scale_id": scale_id,
            "location": location,
            "ip_address": ip_address,
            "rfid_tag": rfid_tag,
            "assigned_nif": assigned,
            "last_seen": last_seen,
            "last_reading": last_reading,
            "health": health
        })

    return statuses

def get_cart_meta():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        '''SELECT c.customer_nif, cust.name, MAX(c.created_at)
           FROM active_carts c
           LEFT JOIN customers cust ON c.customer_nif = cust.nif
           GROUP BY c.customer_nif'''
    )
    rows = cursor.fetchall()
    conn.close()

    meta = {}
    for row in rows:
        nif, cust_name, last_update = row
        label = f"{cust_name if cust_name else 'Unregistered'} ({nif})"
        meta[label] = {"last_update": last_update}
    return meta

def get_live_stats():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(DISTINCT customer_nif), COUNT(*), COALESCE(SUM(final_price_cents), 0) FROM active_carts")
    row = cursor.fetchone()
    conn.close()

    active_customers, item_count, total_cents = row
    return {
        "active_customers": active_customers,
        "item_count": item_count,
        "total_cents": total_cents
    }

def publish_scale_command(scale_id, command, payload=None):
    topic = f"supermarket/scale/{scale_id}/command"
    body = {"command": command}
    if payload:
        body.update(payload)
    mqtt_client.publish(topic, json.dumps(body), qos=1, retain=False)

def get_cart_payload_for_nif(nif):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT product_name, weight_grams, final_price_cents, scale_id
                      FROM active_carts WHERE customer_nif=?''', (nif,))
    rows = cursor.fetchall()
    conn.close()

    items = [
        {
            "name": row[0],
            "weight_grams": row[1],
            "price_cents": row[2],
            "scale": row[3]
        }
        for row in rows
    ]

    total_cents = sum(item["price_cents"] for item in items)
    return {"status": "success", "items": items, "total_cents": total_cents}

def is_scale_available(scale_id, nif):
    assignment = get_scale_assignment(scale_id)
    if assignment:
        assigned_nif, last_seen_age = assignment
        if last_seen_age is not None and last_seen_age <= ASSIGNMENT_TTL_SEC and assigned_nif != nif:
            return False
    pending = get_pending_request(scale_id)
    if pending:
        return False
    return True

def set_pending_request(scale_id, nif):
    with pending_requests_lock:
        pending_requests[scale_id] = {
            "nif": nif,
            "created_at": time.time()
        }

def get_pending_request(scale_id):
    with pending_requests_lock:
        pending = pending_requests.get(scale_id)
        if not pending:
            return None
        if time.time() - pending["created_at"] > PENDING_TTL_SEC:
            pending_requests.pop(scale_id, None)
            return None
        return pending

def clear_pending_request(scale_id):
    with pending_requests_lock:
        pending_requests.pop(scale_id, None)

def assign_scale_to_customer(scale_id, customer_nif):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        INSERT INTO scale_assignments (scale_id, customer_nif, assigned_at, last_seen)
        VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT(scale_id) DO UPDATE SET
            customer_nif=excluded.customer_nif,
            assigned_at=excluded.assigned_at,
            last_seen=excluded.last_seen
        """,
        (scale_id, customer_nif)
    )
    conn.commit()
    conn.close()

def touch_scale_assignment(scale_id):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("UPDATE scale_assignments SET last_seen=CURRENT_TIMESTAMP WHERE scale_id=?", (scale_id,))
    conn.commit()
    conn.close()

def cleanup_expired_assignments():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        DELETE FROM scale_assignments
        WHERE (strftime('%s','now') - strftime('%s', last_seen)) > ?
        """,
        (ASSIGNMENT_TTL_SEC,)
    )
    deleted = cursor.rowcount
    conn.commit()
    conn.close()
    return deleted


def cleanup_loop():
    while True:
        try:
            deleted = cleanup_expired_assignments()
            if deleted:
                socketio.emit('status_update', get_scale_statuses())
        except Exception as e:
            log_event("error", "cleanup_failed", error=str(e))
        time.sleep(10)

def get_assigned_customer(scale_id):
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute(
        """
        SELECT customer_nif
        FROM scale_assignments
        WHERE scale_id=?
          AND (strftime('%s','now') - strftime('%s', last_seen)) <= ?
        """,
        (scale_id, ASSIGNMENT_TTL_SEC)
    )
    res = cursor.fetchone()
    conn.close()
    return res[0] if res else None

# ==========================================
# MQTT STREAM INTERCEPTOR & TELEMETRY
# ==========================================
def on_connect(client, userdata, flags, rc):
    global mqtt_connected
    mqtt_connected = True
    log_event("info", "mqtt_connected", broker=MQTT_BROKER, rc=rc)
    client.subscribe("supermarket/scale/+/reading", qos=1)
    client.subscribe("supermarket/scale/+/status", qos=1)
    client.subscribe("supermarket/android/+/reset", qos=1)


def on_disconnect(client, userdata, rc):
    global mqtt_connected
    mqtt_connected = False
    log_event("warn", "mqtt_disconnected", rc=rc)

def on_message(client, userdata, msg):
    topic = msg.topic.split('/')
    try:
        if len(topic) >= 4 and topic[0] == "supermarket" and topic[1] == "scale" and topic[3] == "reading":
            scale_id_raw = topic[2]
            data = json.loads(msg.payload)
            rfid = data.get('rfid', '').strip().upper()
            weight_grams = int(round(float(data.get('weight', 0))))

            if weight_grams <= 0 or weight_grams > MAX_WEIGHT_GRAMS:
                pending = get_pending_request(scale_id_raw)
                target_nif = pending["nif"] if pending else (get_assigned_customer(scale_id_raw) or "GUEST_NIF")
                log_event(
                    "warn",
                    "invalid_weight",
                    scale_id=scale_id_raw,
                    nif=target_nif,
                    weight_grams=weight_grams,
                )
                socketio.emit('cart_error', {
                    "reason": "invalid_weight",
                    "scale_id": scale_id_raw,
                    "weight_grams": weight_grams
                }, to=target_nif)
                return

            target_nif = get_assigned_customer(scale_id_raw) or "GUEST_NIF"

            if is_duplicate_reading(scale_id_raw, rfid, weight_grams):
                log_event(
                    "info",
                    "reading_deduped",
                    scale_id=scale_id_raw,
                    rfid=rfid,
                    nif=target_nif,
                    weight_grams=weight_grams,
                )
                return

            product = get_product(rfid)
            if product:
                product_id, p_name, unit, price_per_unit_cents = product
                if unit == 'kg':
                    final_price_cents = int((weight_grams * price_per_unit_cents + 500) / 1000)
                else:
                    final_price_cents = int(price_per_unit_cents)

                reading_id = log_reading(scale_id_raw, rfid, weight_grams, product_id, p_name)
                add_to_cart(target_nif, scale_id_raw, product_id, p_name, weight_grams, final_price_cents, reading_id)
                clear_pending_request(scale_id_raw)
                socketio.emit('update_event', get_active_carts_formatted())
                socketio.emit('cart_meta', get_cart_meta())
                socketio.emit('cart_update', get_cart_payload_for_nif(target_nif), to=target_nif)
                socketio.emit('status_update', get_scale_statuses())
                socketio.emit('stats_update', get_live_stats())
                socketio.emit('read_event', {
                    "scale_id": scale_id_raw,
                    "rfid": rfid,
                    "weight_grams": weight_grams,
                    "product_name": p_name
                })
            else:
                log_reading(scale_id_raw, rfid, weight_grams)
                log_event("warn", "unknown_rfid", scale_id=scale_id_raw, rfid=rfid, nif=target_nif)
                socketio.emit('unknown_rfid_event', {'rfid': rfid, 'scale_id': scale_id_raw})
                socketio.emit('cart_error', {
                    "reason": "unknown_rfid",
                    "scale_id": scale_id_raw,
                    "rfid": rfid
                }, to=target_nif)
                socketio.emit('status_update', get_scale_statuses())
                socketio.emit('read_event', {
                    "scale_id": scale_id_raw,
                    "rfid": rfid,
                    "weight_grams": weight_grams,
                    "product_name": None
                })

        elif len(topic) >= 4 and topic[0] == "supermarket" and topic[1] == "scale" and topic[3] == "status":
            scale_id_raw = topic[2]
            data = json.loads(msg.payload)
            status = data.get('status')
            reason = data.get('reason')

            if status == "alive":
                touch_scale_assignment(scale_id_raw)
                socketio.emit('status_update', get_scale_statuses())
                return

            pending = get_pending_request(scale_id_raw)
            target_nif = pending["nif"] if pending else None

            if status == "tared" and target_nif:
                assign_scale_to_customer(scale_id_raw, target_nif)
                clear_pending_request(scale_id_raw)
                socketio.emit('scale_ready', {
                    "scale_id": scale_id_raw
                }, to=target_nif)
            elif status in ("busy", "error") and target_nif:
                clear_pending_request(scale_id_raw)
                socketio.emit('scale_error', {
                    "scale_id": scale_id_raw,
                    "reason": reason or status
                }, to=target_nif)

            socketio.emit('status_update', get_scale_statuses())

        elif len(topic) >= 4 and topic[0] == "supermarket" and topic[1] == "android" and topic[3] == "reset":
            customer_nif = topic[2]
            archive_cart(customer_nif)
            socketio.emit('update_event', get_active_carts_formatted())
            socketio.emit('cart_meta', get_cart_meta())
            socketio.emit('cart_update', get_cart_payload_for_nif(customer_nif), to=customer_nif)
            socketio.emit('stats_update', get_live_stats())

    except Exception as e:
        log_event("error", "mqtt_pipeline_failure", error=str(e), topic=msg.topic)

mqtt_client = mqtt.Client()
mqtt_client.on_connect = on_connect
mqtt_client.on_disconnect = on_disconnect
mqtt_client.on_message = on_message
mqtt_client.connect(MQTT_BROKER, 1883, 60)
threading.Thread(target=mqtt_client.loop_forever, daemon=True).start()
threading.Thread(target=cleanup_loop, daemon=True).start()

# ==========================================
# MOBILE APPLICATION REST API ENDPOINTS
# ==========================================
@app.route('/api/auth/login', methods=['POST'])
def api_login():
    data = request.json or {}
    nif = (data.get('nif') or '').strip()
    pin = (data.get('pin') or '').strip()

    if not validate_nif(nif) or not validate_pin(pin):
        return jsonify({"status": "invalid", "message": "Invalid credentials format."}), 400
    
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM customers WHERE nif=? AND pin=?", (nif, pin))
    user = cursor.fetchone()
    conn.close()
    
    if user:
        token = issue_token(nif)
        return jsonify({
            "status": "success",
            "client_name": user[0],
            "token": token,
            "expires_in": TOKEN_TTL_SEC
        })
    return jsonify({"status": "unauthorized", "message": "Invalid credentials provided."}), 401


@app.route('/api/auth/register', methods=['POST'])
def api_register():
    data = request.json or {}

    nif = (data.get('nif') or '').strip()
    pin = (data.get('pin') or '').strip()
    name = (data.get('name') or '').strip()
    email = (data.get('email') or '').strip()

    if not nif or not pin or not name or not email:
        return jsonify({"status": "invalid", "message": "Missing required fields."}), 400

    if not validate_nif(nif):
        return jsonify({"status": "invalid", "message": "NIF must be 9 digits."}), 400

    if not validate_pin(pin):
        return jsonify({"status": "invalid", "message": "PIN must be 4 digits."}), 400

    if '@' not in email or len(email) < 3:
        return jsonify({"status": "invalid", "message": "Invalid email."}), 400

    created = create_customer(nif, name, email, pin)
    if not created:
        return jsonify({"status": "exists", "message": "Account already exists."}), 409

    token = issue_token(nif)
    return jsonify({
        "status": "success",
        "client_name": name,
        "token": token,
        "expires_in": TOKEN_TTL_SEC
    }), 201

@app.route('/api/scale/assign', methods=['POST'])
def api_assign_scale():
    auth_nif, err = require_auth_nif()
    if err:
        return jsonify(err[0]), err[1]

    data = request.json or {}
    nif = (data.get('nif') or '').strip()
    scale_rfid = data.get('scale_rfid', '').strip().upper()

    if not validate_nif(nif) or nif != auth_nif:
        return jsonify({"status": "forbidden", "message": "NIF does not match token."}), 403

    if not scale_rfid:
        return jsonify({"status": "invalid", "message": "Missing scale tag."}), 400

    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute("SELECT scale_id FROM scales WHERE rfid_tag=?", (scale_rfid,))
    res = cursor.fetchone()
    conn.close()

    if not res:
        return jsonify({"status": "not_found", "message": "Unknown scale tag."}), 404

    scale_id = res[0]
    if not is_scale_available(scale_id, nif):
        return jsonify({"status": "busy", "message": "Scale is in use."}), 409

    set_pending_request(scale_id, nif)
    publish_scale_command(scale_id, "tare")

    socketio.emit('status_update', get_scale_statuses())
    return jsonify({"status": "pending", "scale_id": scale_id})

@app.route('/api/cart/<nif>', methods=['GET'])
def api_cart(nif):
    auth_nif, err = require_auth_nif()
    if err:
        return jsonify(err[0]), err[1]

    nif = (nif or '').strip()
    if not validate_nif(nif) or nif != auth_nif:
        return jsonify({"status": "forbidden", "message": "NIF does not match token."}), 403

    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    cursor.execute('''SELECT product_name, weight_grams, final_price_cents, scale_id
                      FROM active_carts WHERE customer_nif=?''', (nif,))
    rows = cursor.fetchall()
    conn.close()

    items = [
        {
            "name": row[0],
            "weight_grams": row[1],
            "price_cents": row[2],
            "scale": row[3]
        }
        for row in rows
    ]
    total_cents = sum(item["price_cents"] for item in items)

    return jsonify({"status": "success", "items": items, "total_cents": total_cents})

@app.route('/api/cart/reset', methods=['POST'])
def api_cart_reset():
    auth_nif, err = require_auth_nif()
    if err:
        return jsonify(err[0]), err[1]

    data = request.json or {}
    nif = (data.get('nif') or '').strip()

    if not validate_nif(nif) or nif != auth_nif:
        return jsonify({"status": "forbidden", "message": "NIF does not match token."}), 403

    archive_cart(nif)
    socketio.emit('update_event', get_active_carts_formatted())
    socketio.emit('cart_meta', get_cart_meta())
    socketio.emit('cart_update', get_cart_payload_for_nif(nif), to=nif)
    socketio.emit('stats_update', get_live_stats())

    return jsonify({"status": "success"})

@app.route('/api/scale/heartbeat', methods=['POST'])
def api_scale_heartbeat():
    _, err = require_auth_nif()
    if err:
        return jsonify(err[0]), err[1]

    data = request.json or {}
    scale_id = data.get('scale_id', '').strip()
    if scale_id:
        touch_scale_assignment(scale_id)
        socketio.emit('status_update', get_scale_statuses())
    return jsonify({"status": "success"})

# ==========================================
# WEB CONTROLLER TERMINALS (UI ROUTING)
# ==========================================
@socketio.on('connect')
def handle_connect():
    socketio.emit('update_event', get_active_carts_formatted())
    socketio.emit('cart_meta', get_cart_meta())
    socketio.emit('status_update', get_scale_statuses())
    socketio.emit('stats_update', get_live_stats())
    socketio.emit('reads_snapshot', get_recent_reads())

@socketio.on('register_android')
def handle_android_register(data):
    token = (data or {}).get('token', '').strip()
    nif = verify_token(token)
    if nif:
        join_room(nif)
        socketio.emit('cart_update', get_cart_payload_for_nif(nif), to=nif)

@socketio.on('android_heartbeat')
def handle_android_heartbeat(data):
    scale_id = (data or {}).get('scale_id', '').strip()
    if scale_id:
        touch_scale_assignment(scale_id)

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/admin')
def admin_dashboard():
    history_logs = get_full_history()
    total_revenue_cents = sum(item[5] for item in history_logs)
    total_revenue = round(total_revenue_cents / 100.0, 2)
    
    return render_template('admin.html', 
                           products=get_all_products(),
                           customers=get_all_customers(),
                           scales=get_all_scales(),
                           active_carts=get_active_carts_formatted(),
                           history=history_logs,
                           revenue=total_revenue,
                           recent_errors=list(recent_errors))


@app.route('/health')
def health():
    db_ok = True
    db_error = None
    try:
        conn = sqlite3.connect(DB_NAME)
        cursor = conn.cursor()
        cursor.execute("SELECT 1")
        conn.close()
    except Exception as e:
        db_ok = False
        db_error = str(e)

    payload = {
        "status": "ok" if (db_ok and mqtt_connected) else "degraded",
        "ts": utc_now_iso(),
        "db": {"ok": db_ok, "error": db_error},
        "mqtt": {"ok": mqtt_connected, "broker": MQTT_BROKER},
    }
    return jsonify(payload), (200 if payload["status"] == "ok" else 503)

@app.route('/admin/product/save', methods=['POST'])
def admin_save_product():
    raw_type = (request.form.get('type') or '').strip().lower()
    if raw_type not in ('bulk', 'unit'):
        abort(400, description='Invalid product type')
    unit = 'kg' if raw_type == 'bulk' else 'unit'

    price_raw = (request.form.get('price') or '').strip()
    try:
        price = float(price_raw)
    except ValueError:
        abort(400, description='Invalid price')
    if price < 0:
        abort(400, description='Invalid price')
    price_cents = int(round(price * 100))

    rfid = (request.form.get('rfid') or '').strip().upper()
    name = (request.form.get('name') or '').strip()
    if not rfid or not name:
        abort(400, description='Missing rfid or name')
    add_or_update_product(
        rfid,
        name,
        unit,
        price_cents
    )
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/product/delete/<rfid>')
def admin_delete_product(rfid):
    rfid = (rfid or '').strip().upper()
    if not rfid:
        abort(400, description='Invalid rfid')
    delete_product(rfid)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/customer/save', methods=['POST'])
def admin_save_customer():
    nif = (request.form.get('nif') or '').strip()
    pin = (request.form.get('pin') or '').strip()
    name = (request.form.get('name') or '').strip()
    email = (request.form.get('email') or '').strip()

    if not validate_nif(nif):
        abort(400, description='Invalid NIF')
    if not validate_pin(pin):
        abort(400, description='Invalid PIN')
    if not name:
        abort(400, description='Missing name')

    add_or_update_customer(nif, name, email, pin)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/customer/delete/<nif>')
def admin_delete_customer(nif):
    nif = (nif or '').strip()
    if not validate_nif(nif):
        abort(400, description='Invalid NIF')
    delete_customer(nif)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/scale/save', methods=['POST'])
def admin_save_scale():
    scale_id = (request.form.get('scale_id') or '').strip().upper()
    location = (request.form.get('location') or '').strip()
    ip_address_raw = (request.form.get('ip_address') or '').strip()
    rfid_tag = (request.form.get('rfid_tag') or '').strip().upper()

    if not scale_id:
        abort(400, description='Invalid scale_id')
    if not location:
        abort(400, description='Missing location')
    try:
        ipaddress.ip_address(ip_address_raw)
    except ValueError:
        abort(400, description='Invalid ip_address')
    if not rfid_tag:
        abort(400, description='Missing rfid_tag')

    add_or_update_scale(scale_id, location, ip_address_raw, rfid_tag)
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/scale/delete/<scale_id>')
def admin_delete_scale(scale_id):
    scale_id = (scale_id or '').strip().upper()
    if not scale_id:
        abort(400, description='Invalid scale_id')
    delete_scale(scale_id)
    return redirect(url_for('admin_dashboard'))

if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=5000, debug=True, use_reloader=False)