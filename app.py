"""Cozy Cluster front end (Flask). Serves the page and forwards /api calls to the Java backend."""
import os

import requests
from flask import Flask, Response, jsonify, request, send_from_directory

BACKEND = f"http://127.0.0.1:{os.environ.get('BACKEND_PORT', '8081')}"
HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWED = {"homes", "bookings"}

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 3 * 1024 * 1024


@app.get("/")
def index():
    resp = send_from_directory(HERE, "index.html")
    resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.get("/healthz")
def healthz():
    return "ok"


@app.errorhandler(413)
def too_big(_e):
    return jsonify(error="That upload is too large. Try a smaller photo."), 413


@app.route("/api/<name>", methods=["GET", "POST"])
def proxy(name):
    if name not in ALLOWED:
        return jsonify(error="Not found"), 404
    try:
        if request.method == "POST":
            body = request.get_json(silent=True)
            if not isinstance(body, dict):
                return jsonify(error="Invalid request"), 400
            data = {str(k): "" if v is None else str(v) for k, v in body.items()}
            r = requests.post(f"{BACKEND}/api/{name}", data=data, timeout=15)
        else:
            r = requests.get(f"{BACKEND}/api/{name}", params=request.args, timeout=15)
    except requests.RequestException:
        return jsonify(error="Server is waking up, please try again"), 502
    return Response(r.content, status=r.status_code, mimetype="application/json")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "10000")))
