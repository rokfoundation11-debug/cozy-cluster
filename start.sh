#!/bin/bash
# Runs the Java backend (localhost only) and the Python front end (public $PORT).
java -Xmx192m -XX:+UseSerialGC -cp /app/backend Server &
gunicorn --chdir /app/frontend -b 0.0.0.0:${PORT:-10000} -w 1 --threads 8 --timeout 60 app:app &
wait -n
echo "A process exited, stopping so Render restarts the service" >&2
exit 1
