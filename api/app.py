# Import necessary libraries
import os
import sqlite3
import datetime
from flask import Flask, request, jsonify
from werkzeug.security import generate_password_hash, check_password_hash
import jwt # Requires PyJWT library: pip install PyJWT

# --- Configuration ---
DATABASE = 'users.db'
# IMPORTANT: Change this secret key in a real application!
# You can generate a strong secret key using: python -c 'import os; print(os.urandom(24))'
SECRET_KEY = 'your-very-secret-and-secure-key'
TOKEN_EXPIRATION_MINUTES = 60 # Token validity period

# --- Flask App Initialization ---
app = Flask(__name__)
app.config['SECRET_KEY'] = SECRET_KEY

# --- Database Functions ---

def get_db():
    """Opens a new database connection if there is none yet for the current application context."""
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row # Return rows as dictionary-like objects
    return conn

def init_db(populate=True):
    """Initializes the database schema and optionally populates it."""
    db = get_db()
    cursor = db.cursor()
    print("Initializing database...")
    # Drop table if it exists (for easy re-initialization during development)
    cursor.execute('DROP TABLE IF EXISTS users')
    # Create users table
    cursor.execute('''
        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL
        )
    ''')
    print("Table 'users' created.")

    # Populate with initial users if requested
    if populate:
        print("Populating database with initial users...")
        users_to_add = [
            ('John Old', 'johnold@email.com', '1234'),
            ('John Not Old', 'johnnotold@email.com', '1234')
        ]
        for name, email, password in users_to_add:
            # IMPORTANT: Hash the password before storing
            hashed_password = generate_password_hash(password)
            try:
                cursor.execute('INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)',
                               (name, email, hashed_password))
                print(f"Added user: {email}")
            except sqlite3.IntegrityError:
                print(f"User {email} already exists.") # Should not happen with DROP TABLE
        print("Initial users added.")

    db.commit()
    db.close()
    print("Database initialized successfully.")

def query_db(query, args=(), one=False):
    """Queries the database and returns results."""
    db = get_db()
    cur = db.execute(query, args)
    rv = cur.fetchall()
    db.close()
    return (rv[0] if rv else None) if one else rv

# --- Helper Functions ---

def generate_auth_token(user_id):
    """Generates the Auth Token"""
    try:
        payload = {
            'exp': datetime.datetime.utcnow() + datetime.timedelta(minutes=TOKEN_EXPIRATION_MINUTES),
            'iat': datetime.datetime.utcnow(),
            'sub': user_id # Subject of the token is the user ID
        }
        return jwt.encode(
            payload,
            app.config['SECRET_KEY'],
            algorithm='HS256'
        )
    except Exception as e:
        return e

# --- API Endpoints ---

@app.route('/api/auth/login', methods=['POST'])
def login():
    """
    Handles user login requests.
    Expects JSON payload with 'email' and 'password'.
    Returns user info and auth token on success, error otherwise.
    """
    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    email = data.get('email')
    password = data.get('password')

    if not email or not password:
        return jsonify({"error": "Missing email or password"}), 400

    # Find user by email
    user_row = query_db('SELECT * FROM users WHERE email = ?', [email], one=True)

    if user_row:
        # Check if the hashed password matches
        if check_password_hash(user_row['password_hash'], password):
            # Password matches, generate token
            token = generate_auth_token(user_row['id'])

            # Prepare user info response (matching Android data class)
            user_info = {
                "userId": str(user_row['id']), # Ensure userId is string if needed
                "email": user_row['email'],
                "name": user_row['name'],
                "authToken": token
            }
            return jsonify(user_info), 200
        else:
            # Password does not match
            return jsonify({"error": "Invalid credentials"}), 401
    else:
        # User not found
        return jsonify({"error": "User not found"}), 404

@app.route('/')
def index():
    """A simple index route to check if the server is running."""
    return "Flask Auth API Server is running!"

# --- Main Execution ---

if __name__ == '__main__':
    # Initialize database if it doesn't exist
    if not os.path.exists(DATABASE):
        print(f"Database '{DATABASE}' not found. Creating and populating...")
        init_db(populate=True)
    else:
        print(f"Database '{DATABASE}' already exists.")
        # Optional: You might want a command-line argument to force re-initialization
        # e.g., python app.py --init-db
        # For simplicity here, we assume existing DB is fine.

    # Run the Flask app
    # Host '0.0.0.0' makes it accessible on your network IP
    print("Starting Flask server on http://0.0.0.0:5000")
    app.run(host='10.200.23.240', port=2242, debug=True) # debug=True for development
