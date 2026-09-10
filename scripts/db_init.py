"""Create the two MySQL schemas used by the app and integration tests.

The script intentionally uses pymysql because the Windows installation used for this
project does not expose a mysql.exe client on PATH.
"""
import argparse
import os
import pymysql


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reset", action="store_true", help="drop the two project schemas first")
    args = parser.parse_args()
    connection = pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            if args.reset:
                cursor.execute("DROP DATABASE IF EXISTS campus_errand_test")
                cursor.execute("DROP DATABASE IF EXISTS campus_errand")
            cursor.execute("CREATE DATABASE IF NOT EXISTS campus_errand CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            cursor.execute("CREATE DATABASE IF NOT EXISTS campus_errand_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            cursor.execute("SELECT VERSION()")
            print(f"MySQL ready: {cursor.fetchone()[0]}; schemas campus_errand and campus_errand_test")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
