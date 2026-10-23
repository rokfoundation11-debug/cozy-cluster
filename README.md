# Cozy Cluster
Python (Flask) front end + Java (JDK only) backend, one Docker service.

## Deploy on Render
1. Push this folder to a GitHub repo (Dockerfile must be at the repo root).
2. Render > New > Web Service > pick the repo. Runtime: Docker. Done.
3. Homes and bookings are saved in /data. Free plans lose it on redeploy; add a Disk mounted at /data (paid) to keep it.

## Run locally
    docker build -t cozy . && docker run -p 10000:10000 cozy
