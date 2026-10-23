FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt
COPY backend ./backend
RUN javac -d backend backend/Server.java
COPY frontend ./frontend
COPY start.sh .
RUN sed -i 's/\r$//' start.sh && chmod +x start.sh
ENV DATA_DIR=/data BACKEND_PORT=8081
CMD ["bash", "start.sh"]
