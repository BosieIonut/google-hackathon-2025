# app.py
# Main Flask application file.

import os
import datetime
import sqlite3 # Import for specific exception handling
from functools import wraps # For the decorator
from flask import Flask, request, jsonify, g # Import g for context-local storage
from werkzeug.security import check_password_hash, generate_password_hash
import jwt # Requires PyJWT library: pip install PyJWT
from collections import deque, defaultdict # Import deque and defaultdict for queues

# Import database functions from database.py
import database

# --- Configuration ---
SECRET_KEY = 'your-very-secret-and-secure-key' # CHANGE THIS!
TOKEN_EXPIRATION_MINUTES = 60
ALLOWED_USER_TYPES = ('guardian', 'protege', None) # Define allowed types including None

# --- Flask App Initialization ---
app = Flask(__name__)
app.config['SECRET_KEY'] = SECRET_KEY

# --- Authentication Decorator ---

def token_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        token = None
        # Check for token in Authorization header (Bearer scheme)
        if 'Authorization' in request.headers:
            auth_header = request.headers['Authorization']
            try:
                # Split "Bearer <token>"
                token = auth_header.split(" ")[1]
            except IndexError:
                return jsonify({"error": "Invalid Authorization header format. Use 'Bearer <token>'."}), 401

        if not token:
            return jsonify({"error": "Authentication token is missing"}), 401

        try:
            # Decode the token using the secret key
            payload = jwt.decode(token, app.config['SECRET_KEY'], algorithms=["HS256"])
            user_id = payload['sub'] # Get user ID from 'sub' claim
            # Fetch the current user from DB and store it in Flask's g object
            # g is context-local and available throughout the request
            # query_db now returns a dictionary or None
            current_user = database.query_db('SELECT * FROM users WHERE id = ?', [user_id], one=True)
            if not current_user:
                 return jsonify({"error": "User associated with token not found"}), 401
            g.current_user = current_user # Store user dict in g

        except jwt.ExpiredSignatureError:
            return jsonify({"error": "Token has expired"}), 401
        except jwt.InvalidTokenError:
            return jsonify({"error": "Token is invalid"}), 401
        except Exception as e:
            print(f"Token validation error: {e}")
            return jsonify({"error": "An error occurred during token validation"}), 500

        # Call the actual route function, passing g.current_user implicitly via context
        return f(*args, **kwargs)
    return decorated

# --- Helper Functions ---

def generate_auth_token(user_id):
    """Generates the Auth Token"""
    try:
        payload = {
            'exp': datetime.datetime.utcnow() + datetime.timedelta(minutes=TOKEN_EXPIRATION_MINUTES),
            'iat': datetime.datetime.utcnow(),
            'sub': user_id # Subject of the token is the user ID
        }
        token = jwt.encode(
            payload,
            app.config['SECRET_KEY'],
            algorithm='HS256'
        )
        # PyJWT usually returns a string in recent versions for HS256
        return token
    except Exception as e:
        print(f"Error generating token: {e}")
        return None

# --- API Endpoints ---

@app.route('/api/auth/login', methods=['POST'])
def login():
    """Handles user login. Returns user info including type and friend."""
    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    email = data.get('email')
    password = data.get('password')

    if not email or not password:
        return jsonify({"error": "Missing email or password"}), 400

    # query_db returns a dictionary or None
    user_row = database.query_db('SELECT * FROM users WHERE email = ?', [email], one=True)

    # Check if user_row is not None and password matches
    if user_row and check_password_hash(user_row['password_hash'], password):
        token = generate_auth_token(user_row['id'])
        if token is None:
             return jsonify({"error": "Could not generate authentication token"}), 500

        # Prepare user info from the dictionary
        user_info = {
            "userId": str(user_row['id']),
            "email": user_row['email'],
            "name": user_row['name'],
            "user_type": user_row['user_type'],     # Added
            "friend_email": user_row['friend_email'], # Added (can be null)
            "authToken": token
        }
        return jsonify(user_info), 200
    else:
        # Handles both user not found and incorrect password
        return jsonify({"error": "Invalid credentials"}), 401


@app.route('/api/user/profile', methods=['PUT'])
@token_required
def update_profile():
    """Updates the authenticated user's profile (name, email, password)."""
    # Access the current user data (dictionary) stored in g by the decorator
    current_user = g.current_user

    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    name = data.get('name')
    email = data.get('email')
    password = data.get('password') # New password

    # Basic validation
    if name is None and email is None and password is None: # Check if all are None explicitly
        return jsonify({"error": "No update data provided (name, email, or password)"}), 400

    try:
        # Call the database function to update details
        success = database.update_user_details(
            user_id=current_user['id'],
            name=name, # Pass value or None directly
            email=email,
            password=password
        )

        if success:
            # Fetch updated user data to return
            updated_user = database.query_db(
                'SELECT id, name, email, user_type, friend_email FROM users WHERE id = ?',
                [current_user['id']],
                one=True
            )
            if updated_user:
                # Prepare response format
                response_user = {
                    "userId": str(updated_user['id']),
                    "name": updated_user['name'],
                    "email": updated_user['email'],
                    "user_type": updated_user['user_type'],
                    "friend_email": updated_user['friend_email']
                }
                return jsonify({"message": "Profile updated successfully", "user": response_user}), 200
            else:
                # Should not happen if update succeeded, but handle defensively
                return jsonify({"error": "Failed to retrieve updated profile"}), 500
        else:
            # update_user_details might return False for non-Integrity errors handled within it
             return jsonify({"error": "Profile update failed"}), 500

    except sqlite3.IntegrityError as e:
         # Catch specific IntegrityError re-raised from database.py for email uniqueness
         if "Email already exists" in str(e):
              return jsonify({"error": f"Email '{email}' is already in use."}), 409 # Conflict
         else:
              # Handle other potential integrity errors if necessary
              print(f"Database integrity error during update: {e}")
              return jsonify({"error": f"Database integrity error occurred."}), 400
    except Exception as e:
        print(f"Error updating profile: {e}")
        return jsonify({"error": "An unexpected error occurred during profile update."}), 500


@app.route('/api/alert/send', methods=['POST']) # New Endpoint - No Authentication
def send_alert():
    """
    Receives alert data and (in the future) triggers a notification.
    Does not require authentication.
    Expects JSON: {"recipient_email": "...", "sender_email": "...", "alert_type": "..."}
    """
    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    recipient_email = data.get('recipient_email')
    sender_email = data.get('sender_email')
    alert_type = data.get('alert_type')

    print(f"Received alert data: {data}") # Debugging log
    # Basic validation for required fields
    if not recipient_email:
        return jsonify({"error": "Missing 'recipient_email'"}), 400
    if not sender_email:
        return jsonify({"error": "Missing 'sender_email'"}), 400
    if not alert_type:
        return jsonify({"error": "Missing 'alert_type'"}), 400

    # --- Placeholder for Android Notification Logic ---
    # Aici vei adăuga codul pentru a trimite notificarea către aplicația Android.
    # Acest lucru implică de obicei trimiterea datelor (recipient_email, sender_email, alert_type)
    # către un serviciu precum Firebase Cloud Messaging (FCM) sau un alt mecanism de push notification.
    # Exemplu conceptual:
    # notification_service.send_push_notification(
    #     recipient_device_token, # Ai nevoie de token-ul dispozitivului destinatarului (obținut din DB sau altundeva)
    #     title="Alertă Nouă",
    #     body=f"Ai primit o alertă de tip '{alert_type}' de la {sender_email}",
    #     data={"sender": sender_email, "type": alert_type, "recipient": recipient_email} # Date suplimentare
    # )
    print(f"Received alert: To={recipient_email}, From={sender_email}, Type={alert_type}")
    print("--> Placeholder: Send Android notification here <--")
    # --- End Placeholder ---

    # Return success response
    return jsonify({
        "message": "Alert received successfully. Notification pending.",
        "recipient": recipient_email,
        "sender": sender_email,
        "type": alert_type
    }), 200

ALLOWED_NOTIFICATION_TYPES = ('check_ok', 'yes_ok', 'no_ok', 'fall_detected', 'bpm_low', 'bpm_high', 'not_well') # Define allowed notification types

notification_queues = defaultdict(lambda: {'queue': deque(), 'present': set()})



@app.route('/api/notify/send', methods=['POST'])
def send_notification():
    """
    Receives notification data and adds it to the recipient's in-memory queue.
    Does not require authentication.
    Expects JSON: {"recipient_email": "...", "sender_email": "...", "notification_type": "..."}
    """
    if not request.is_json: return jsonify({"error": "Request must be JSON"}), 400
    data = request.get_json()
    recipient_email = data.get('recipient_email')
    sender_email = data.get('sender_email')
    notification_type = data.get('notification_type')
    if not recipient_email: return jsonify({"error": "Missing 'recipient_email'"}), 400
    if not sender_email: return jsonify({"error": "Missing 'sender_email'"}), 400
    if not notification_type: return jsonify({"error": "Missing 'notification_type'"}), 400
    if notification_type not in ALLOWED_NOTIFICATION_TYPES:
        return jsonify({"error": f"Invalid 'notification_type'. Allowed: {ALLOWED_NOTIFICATION_TYPES}"}), 400

    # Add to queue, avoiding duplicates
    queue_data = notification_queues[recipient_email]
    notification_tuple = (sender_email, notification_type)

    if notification_tuple not in queue_data['present']:
        queue_data['present'].add(notification_tuple)
        queue_data['queue'].append({"sender_email": sender_email, "type": notification_type})
        print(f"Notification added to queue for {recipient_email}: From={sender_email}, Type={notification_type}")
        message = "Notification added to queue."
    else:
        print(f"Duplicate notification skipped for {recipient_email}: From={sender_email}, Type={notification_type}")
        message = "Duplicate notification skipped."

    # return jsonify({"message": message, "recipient": recipient_email, "sender": sender_email, "type": notification_type}), 200
    return '',200 # 200 No Content for success without body


@app.route('/api/notifications/check', methods=['GET']) # New endpoint to check inbox
@token_required # Requires authentication to know whose inbox to check
def check_notification_inbox():
    """
    Checks the authenticated user's notification queue and returns the oldest notification.
    Removes the notification from the queue upon retrieval.
    """
    recipient_email = g.current_user['email']

    if recipient_email in notification_queues and notification_queues[recipient_email]['queue']:
        # Queue exists and is not empty
        queue_data = notification_queues[recipient_email]
        notification = queue_data['queue'].popleft() # Get and remove the oldest notification
        notification_tuple = (notification['sender_email'], notification['type'])
        queue_data['present'].remove(notification_tuple) # Remove from the presence set

        print(f"Notification retrieved for {recipient_email}: {notification}")
        return jsonify(notification), 200
    else:
        # Queue is empty or doesn't exist for this user
        # print(f"No new notifications for {recipient_email}")
        # Return an empty object to indicate no notifications
        # Alternatively, could return 204 No Content, but 200 with empty body is often easier for clients
        return jsonify({}), 200

@app.route('/api/user/type', methods=['PUT']) # New endpoint to change user type
@token_required
def change_user_type():
    """Changes the authenticated user's type (guardian, protege, or None) and handles unlinking."""
    current_user = g.current_user
    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    new_type = data.get('user_type', 'missing') # Use default to detect missing key

    # Validate input type
    if new_type == 'missing':
         return jsonify({"error": "Missing 'user_type' in request body."}), 400

    # Allow null/None explicitly, map common string representations to None
    if isinstance(new_type, str) and new_type.strip().lower() in ['null', 'none', '']:
         new_type = None
    elif new_type not in ALLOWED_USER_TYPES: # Check against allowed types (including None)
         return jsonify({"error": f"Invalid user_type '{new_type}'. Allowed types: 'guardian', 'protege', or null."}), 400

    try:
        success = database.update_user_type(
            user_id=current_user['id'],
            user_email=current_user['email'], # Pass email for logging/debugging
            new_type=new_type
        )
        if success:
            # Fetch updated user data to return
            updated_user = database.query_db('SELECT id, name, email, user_type, friend_email FROM users WHERE id = ?', [current_user['id']], one=True)
            if updated_user:
                 response_user = {
                    "userId": str(updated_user['id']), "name": updated_user['name'],
                    "email": updated_user['email'], "user_type": updated_user['user_type'],
                    "friend_email": updated_user['friend_email']
                 }
                 return jsonify({"message": f"User type successfully changed to '{new_type if new_type is not None else 'None'}'", "user": response_user}), 200
            else:
                 # This really shouldn't happen if the update was successful
                 return jsonify({"error": "Failed to retrieve updated profile after type change."}), 500
        else:
            # Error occurred within update_user_type (already logged)
            return jsonify({"error": "Failed to update user type due to a database issue."}), 500

    except ValueError as e: # Catch invalid type error from database layer
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        print(f"Error changing user type: {e}")
        return jsonify({"error": "An unexpected error occurred while changing user type."}), 500


@app.route('/api/user/link/guardian', methods=['POST'])
@token_required
def request_guardian():
    """Allows a protege to link with a guardian."""
    current_user = g.current_user
    # Check user type
    if current_user['user_type'] != 'protege':
        return jsonify({"error": "Only users with type 'protege' can request a guardian link."}), 403 # Forbidden
    # Check if already linked
    if current_user['friend_email']:
        return jsonify({"error": f"You are already linked with {current_user['friend_email']}. Remove the existing link first."}), 409 # Conflict
    # Check request format
    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    guardian_email = data.get('guardian_email')

    # Validate guardian email
    if not guardian_email:
        return jsonify({"error": "Missing 'guardian_email' in request body"}), 400
    if not isinstance(guardian_email, str) or '@' not in guardian_email:
         return jsonify({"error": "Invalid 'guardian_email' format."}), 400
    if guardian_email == current_user['email']:
         return jsonify({"error": "Cannot link to yourself."}), 400

    # Find and validate the guardian
    guardian_user = database.query_db('SELECT * FROM users WHERE email = ?', [guardian_email], one=True)
    if not guardian_user:
        return jsonify({"error": f"Guardian with email '{guardian_email}' not found."}), 404 # Not Found
    if guardian_user['user_type'] != 'guardian':
        return jsonify({"error": f"The user '{guardian_email}' is not a 'guardian'."}), 400 # Bad Request
    if guardian_user['friend_email']:
        return jsonify({"error": f"Guardian '{guardian_email}' is already linked with another user."}), 409 # Conflict

    # Attempt to link
    try:
        success = database.link_users(protege_email=current_user['email'], guardian_email=guardian_email)
        if success:
            return jsonify({"message": f"Successfully linked with guardian {guardian_email}"}), 200
        else:
             # link_users might return False for non-Integrity errors
             return jsonify({"error": "Failed to link users due to a database issue."}), 500
    except sqlite3.IntegrityError as e:
         # Catch specific error from link_users
         if "already linked" in str(e) or "UNIQUE constraint failed" in str(e):
              # Re-check state as it might have changed
              current_user_refreshed = database.query_db('SELECT friend_email FROM users WHERE id = ?', [current_user['id']], one=True)
              guardian_user_refreshed = database.query_db('SELECT friend_email FROM users WHERE email = ?', [guardian_email], one=True)
              if current_user_refreshed and current_user_refreshed['friend_email']: return jsonify({"error": "Linking failed: You are already linked."}), 409
              elif guardian_user_refreshed and guardian_user_refreshed['friend_email']: return jsonify({"error": f"Linking failed: Guardian '{guardian_email}' is already linked."}), 409
              else: return jsonify({"error": "Linking failed due to a conflict or database issue."}), 409
         else:
              print(f"Database integrity error during linking: {e}")
              return jsonify({"error": f"Database integrity error occurred during linking."}), 500
    except Exception as e:
        print(f"Error linking users: {e}")
        return jsonify({"error": "An unexpected error occurred while linking users."}), 500


@app.route('/api/user/unlink', methods=['POST'])
@token_required
def unlink_friend():
    """Removes the link between the authenticated user and their friend."""
    current_user = g.current_user
    # Check if user is linked
    if not current_user['friend_email']:
        return jsonify({"message": "You are not currently linked with anyone."}), 200 # Or 404 if preferred

    friend_email = current_user['friend_email'] # Store before removing

    try:
        success = database.remove_link(current_user['email'])
        if success:
            return jsonify({"message": f"Successfully unlinked from {friend_email}."}), 200
        else:
            # remove_link handles its own errors generally, returning False
            return jsonify({"error": "Failed to remove link due to a database issue."}), 500
    except Exception as e:
        print(f"Error unlinking user: {e}")
        return jsonify({"error": "An unexpected error occurred while unlinking."}), 500
latest_monitor_data = {
    "temperature": None,
    "humidity": None,
    "timestamp": None # Optional: Store when data was last received
}

@app.route('/api/guardians/available', methods=['GET']) # New endpoint for available guardians
@token_required # Optional: Decide if this needs authentication
def list_available_guardians():
    """Returns a list of guardians who are not currently linked to a protege."""
    
    
    try:
        guardians = database.get_available_guardians()
        # get_available_guardians returns a list of dicts or None
        if guardians is None:
             # This might indicate a DB query error logged in database.py
             return jsonify({"error": "Could not retrieve guardian list."}), 500

        # Returns the list directly (can be empty [])
        # The list contains dicts like {"name": "...", "email": "..."}
        return jsonify(guardians), 200
    except Exception as e:
        print(f"Error fetching available guardians: {e}")
        return jsonify({"error": "An unexpected error occurred while fetching guardians."}), 500


# >> Monitoring Endpoints (No Auth) <<
@app.route('/api/monitor/up', methods=['GET'])
def monitor_up():
    """Simple health check endpoint."""
    print("Health check received.")
    # Returns an empty response with a 200 OK status code
    return '', 200 # 200 No Content

@app.route('/api/monitor/data', methods=['PUT'])
def monitor_data():
    """Receives monitoring data (temperature, humidity) and stores it in memory."""
    global latest_monitor_data # Declare intent to modify the global variable

    if not request.is_json:
        return jsonify({"error": "Request must be JSON"}), 400

    data = request.get_json()
    temperature = data.get('temperature')
    humidity = data.get('humidity')

    # Basic validation - check if keys exist
    if 'temperature' not in data:
        return jsonify({"error": "Missing 'temperature' key"}), 400
    if 'humidity' not in data:
        return jsonify({"error": "Missing 'humidity' key"}), 400

    # --- Store Data In Memory ---
    # Update the global dictionary with the new values
    latest_monitor_data['temperature'] = temperature
    latest_monitor_data['humidity'] = humidity
    latest_monitor_data['timestamp'] = datetime.datetime.utcnow().isoformat() + 'Z' # Store timestamp in ISO format UTC

    print(f"Received and stored monitoring data: Temperature={temperature}, Humidity={humidity} at {latest_monitor_data['timestamp']}")
    # --- End Store Data ---

    return jsonify({"message": "Monitoring data received and stored successfully."}), 200

@app.route('/api/monitor/current', methods=['GET']) # New GET endpoint
def get_current_monitor_data():
    """Returns the latest monitoring data stored in memory."""
    # Simply return the current state of the global dictionary
    # The dictionary contains 'temperature', 'humidity', and 'timestamp'
    return jsonify(latest_monitor_data), 200

@app.route('/')
def index():
    """A simple index route to check if the server is running."""
    return "Flask Auth API Server is running!"

# --- Main Execution ---

if __name__ == '__main__':
    db_path = database.DATABASE
    # Create and initialize DB only if it doesn't exist
    if not os.path.exists(db_path):
        print(f"Database '{db_path}' not found. Creating and populating...")
        database.init_db(populate=True) # Creates schema and adds initial users
    else:
        print(f"Database '{db_path}' already exists. Assuming schema is up-to-date.")
        # In a real app, you'd use migrations (e.g., with Flask-Migrate/Alembic)
        # to handle schema changes on existing databases.
        # For this example, if schema changes, delete the .db file and restart.

    print(f"Starting Flask server on http://0.0.0.0:5000 (SECRET_KEY: {'Set' if SECRET_KEY != 'your-very-secret-and-secure-key' else '!!!Using Default - CHANGE IT!!!'})")
    # Use debug=False in production!
    app.run(host='10.41.61.38', port=2242, debug=True)

