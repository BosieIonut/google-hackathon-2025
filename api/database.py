# database.py
# Contains functions for interacting with the SQLite database.

import sqlite3
import os
from werkzeug.security import generate_password_hash

# --- Configuration ---
DATABASE = 'users.db'
# Define allowed types, adding None implicitly by removing NOT NULL
ALLOWED_USER_TYPES = ('guardian', 'protege')

# --- Database Functions ---

def get_db():
    """Opens a new database connection."""
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db(populate=True):
    """Initializes the database schema and optionally populates it."""
    db = None
    try:
        db = get_db()
        cursor = db.cursor()
        print("Initializing database schema...")
        cursor.execute('DROP TABLE IF EXISTS users')
        # Create users table: user_type can now be NULL
        cursor.execute(f'''
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                user_type TEXT CHECK(user_type IS NULL OR user_type IN {ALLOWED_USER_TYPES}), -- Allow NULL
                friend_email TEXT UNIQUE -- Link constraint remains
            )
        ''')
        print("Table 'users' created. 'user_type' can be NULL.")

        if populate:
            print("Populating database with initial users...")
            # Use the new names provided by the user
            users_to_add = [
                ('John', 'john@email.com', '12', 'guardian'),
                ('Johny', 'johny@email.com', '12', 'protege'),
                ('No Type User', 'notype@email.com', '12', None) # Example of NULL type
            ]
            added_count = 0
            for name, email, password, user_type in users_to_add:
                hashed_password = generate_password_hash(password)
                try:
                    cursor.execute('SELECT id FROM users WHERE email = ?', (email,))
                    existing_user = cursor.fetchone()
                    if not existing_user:
                        cursor.execute('''
                            INSERT INTO users (name, email, password_hash, user_type, friend_email)
                            VALUES (?, ?, ?, ?, NULL)
                        ''', (name, email, hashed_password, user_type))
                        print(f"Added user: {email} (Type: {user_type if user_type else 'None'})")
                        added_count += 1
                    else:
                        print(f"User {email} already exists, skipping.")
                except sqlite3.IntegrityError as e:
                    if 'CHECK constraint failed' in str(e):
                         print(f"Error: Invalid user_type '{user_type}' for {email}. Allowed types: {ALLOWED_USER_TYPES} or NULL.")
                    else:
                         print(f"Integrity error for user {email}: {e}, skipping.")
                except Exception as e:
                    print(f"An unexpected error occurred adding user {email}: {e}")

            if added_count > 0:
                print(f"{added_count} initial users added.")
            else:
                print("No new initial users were added.")

            # --- Auto-link John and Johny ---
            if added_count >= 2: # Ensure both users were likely added
                try:
                    print("Attempting to auto-link 'john@email.com' and 'johny@email.com'...")
                    # Link John (guardian) to Johny (protege)
                    cursor.execute("UPDATE users SET friend_email = ? WHERE email = ?", ('johny@email.com', 'john@email.com'))
                    # Link Johny (protege) to John (guardian)
                    cursor.execute("UPDATE users SET friend_email = ? WHERE email = ?", ('john@email.com', 'johny@email.com'))
                    print("Successfully linked 'john@email.com' and 'johny@email.com'.")
                except sqlite3.Error as link_err:
                    # This might fail if UNIQUE constraint is violated (e.g., one was already linked somehow)
                    print(f"Could not auto-link users: {link_err}")
                    # Consider rolling back the linking part if needed, but commit might proceed for user inserts
            # --- End Auto-link ---


        db.commit() # Commit all changes (inserts and links)
        print("Database initialized successfully.")
    except sqlite3.Error as e:
        print(f"An error occurred during database initialization: {e}")
        if db:
            db.rollback() # Rollback all changes if any error occurred before commit
    finally:
        if db:
            db.close()

def query_db(query, args=(), one=False):
    """Queries the database and returns results as dictionaries."""
    db = None
    try:
        db = get_db()
        db.text_factory = str
        cur = db.execute(query, args)
        rv = cur.fetchall()
        results = [dict(row) for row in rv]
        return (results[0] if results else None) if one else results
    except sqlite3.Error as e:
        print(f"Database query error: {e}")
        return None
    finally:
        if db:
            db.close()

def execute_db(query, args=()):
    """Executes a query that modifies the database (INSERT, UPDATE, DELETE)."""
    db = None
    success = False
    try:
        db = get_db()
        # Use a context manager for connection to handle commit/rollback
        with db:
            db.execute(query, args)
        success = True
    except sqlite3.Error as e:
        print(f"Database execution error: {e}")
        # Context manager handles rollback on exception
        # Re-raise specific errors
        if "UNIQUE constraint failed: users.email" in str(e):
            raise sqlite3.IntegrityError("Email already exists")
        if "UNIQUE constraint failed: users.friend_email" in str(e):
             raise sqlite3.IntegrityError("User is already linked as a friend")
        if "CHECK constraint failed" in str(e):
             raise sqlite3.IntegrityError(f"Invalid user type provided. Allowed: {ALLOWED_USER_TYPES} or None.")
    # No finally needed for db.close() when using 'with db:'
    return success


def update_user_details(user_id, name=None, email=None, password=None):
    """Updates a user's name, email, or password hash."""
    fields_to_update = []
    params = []

    if name is not None:
        fields_to_update.append("name = ?")
        params.append(name)
    if email is not None:
        fields_to_update.append("email = ?")
        params.append(email)
    if password is not None:
        hashed_password = generate_password_hash(password)
        fields_to_update.append("password_hash = ?")
        params.append(hashed_password)

    if not fields_to_update:
        print("No fields provided for update.")
        return False

    query = f"UPDATE users SET {', '.join(fields_to_update)} WHERE id = ?"
    params.append(user_id)

    try:
        return execute_db(query, tuple(params))
    except sqlite3.IntegrityError as e:
         if "Email already exists" in str(e):
              print(f"Update failed: Email '{email}' already exists.")
              raise
         else:
              print(f"Update failed due to integrity constraint: {e}")
              return False


def link_users(protege_email, guardian_email):
    """Links a protege and a guardian by setting their friend_email fields."""
    db = None
    try:
        db = get_db()
        # Use context manager for transaction
        with db:
            # Update protege's friend_email
            db.execute("UPDATE users SET friend_email = ? WHERE email = ?", (guardian_email, protege_email))
            # Update guardian's friend_email
            db.execute("UPDATE users SET friend_email = ? WHERE email = ?", (protege_email, guardian_email))
        print(f"Successfully linked {protege_email} and {guardian_email}")
        return True
    except sqlite3.Error as e:
        print(f"Database error during linking: {e}")
        # Context manager handles rollback
        if "UNIQUE constraint failed: users.friend_email" in str(e):
             raise sqlite3.IntegrityError("One or both users are already linked.")
        return False


def remove_link(user_email):
    """Removes the friend link for a user and their friend."""
    db = None
    try:
        db = get_db()
        # Find the friend's email first
        cursor_find = db.cursor()
        cursor_find.execute("SELECT friend_email FROM users WHERE email = ?", (user_email,))
        result = cursor_find.fetchone()
        friend_email = result['friend_email'] if result else None

        # Use context manager for transaction
        with db:
            # Remove link from the user
            db.execute("UPDATE users SET friend_email = NULL WHERE email = ?", (user_email,))
            # Remove link from the friend if they exist and are linked back
            if friend_email:
                db.execute("UPDATE users SET friend_email = NULL WHERE email = ? AND friend_email = ?", (friend_email, user_email))

        print(f"Successfully removed link for {user_email}" + (f" and {friend_email}" if friend_email else ""))
        return True
    except sqlite3.Error as e:
        print(f"Database error during link removal: {e}")
        # Context manager handles rollback
        return False


def update_user_type(user_id, user_email, new_type):
    """Updates the user_type for a user and handles unlinking if necessary."""
    if new_type is not None and new_type not in ALLOWED_USER_TYPES:
        raise ValueError(f"Invalid user type '{new_type}'. Allowed types are {ALLOWED_USER_TYPES} or None.")

    db = None
    try:
        db = get_db()
        # Use context manager for transaction
        with db:
            # Get current user details (type and friend) within the transaction
            cursor_get = db.cursor()
            cursor_get.execute("SELECT user_type, friend_email FROM users WHERE id = ?", (user_id,))
            current_data = cursor_get.fetchone()
            if not current_data:
                raise ValueError("User not found.")

            current_type = current_data['user_type']
            current_friend = current_data['friend_email']

            # If type is not changing, do nothing (commit will happen, but no changes made)
            if current_type == new_type:
                print(f"User {user_email} already has type '{new_type}'. No change needed.")
                # No need to return early, let the transaction complete harmlessly
            else:
                print(f"Changing type for {user_email} from '{current_type}' to '{new_type}'")

                # --- Unlinking Logic ---
                if current_friend:
                    print(f"User {user_email} is linked to {current_friend}. Unlinking before type change.")
                    # Unlink the current user
                    db.execute("UPDATE users SET friend_email = NULL WHERE id = ?", (user_id,))
                    # Unlink the friend
                    db.execute("UPDATE users SET friend_email = NULL WHERE email = ?", (current_friend,))
                    print(f"Unlinked {user_email} and {current_friend}.")

                # --- Update User Type ---
                db.execute("UPDATE users SET user_type = ? WHERE id = ?", (new_type, user_id))
                print(f"Updated type for user {user_id} ({user_email}) to '{new_type}'.")

        return True # Transaction committed successfully

    except (sqlite3.Error, ValueError) as e:
        print(f"Error updating user type for user ID {user_id}: {e}")
        # Context manager handles rollback
        if isinstance(e, ValueError):
            raise e # Re-raise specific errors if needed by caller
        return False # Indicate failure for database errors


def get_available_guardians():
    """Fetches all users who are guardians and do not have a friend linked."""
    query = """
        SELECT name, email
        FROM users
        WHERE user_type = 'guardian' AND friend_email IS NULL
        ORDER BY name
    """
    return query_db(query, args=(), one=False)

