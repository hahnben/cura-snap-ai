#!/bin/bash

# CuraSnap AI Development Services Startup Script
# Starts all required services for local development

echo "🚀 Starting CuraSnap AI development services..."
echo ""

# Function to check if directory exists
check_directory() {
    if [ ! -d "$1" ]; then
        echo "❌ Directory $1 not found!"
        exit 1
    fi
}

# Check all required directories exist
check_directory "backend"
check_directory "frontend" 
check_directory "agent_service"
check_directory "transcription_service"

# Function to start service in background
start_service() {
    local service_name=$1
    local directory=$2
    local command=$3
    
    echo "📦 Starting $service_name..."
    cd "$directory"
    
    # Start service in background and capture PID
    eval "$command" &
    local pid=$!
    echo "$pid" >> ../pids.tmp
    
    echo "✅ $service_name started (PID: $pid)"
    cd ..
}

# Remove any existing PID file
rm -f pids.tmp

echo "Starting services in parallel..."
echo ""

# Start Backend (Spring Boot)
start_service "Backend (Spring Boot)" "backend" "./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"

# Start Frontend (React) 
start_service "Frontend (React)" "frontend" "npm run dev"

# Start Agent Service (FastAPI)
start_service "Agent Service" "agent_service" "pipenv run uvicorn app.main:app --reload --port 8001"

# Start Transcription Service (FastAPI)
start_service "Transcription Service" "transcription_service" "pipenv run uvicorn app.main:app --reload --port 8002"

echo ""
echo "🎉 All services started successfully!"
echo ""
echo "Services running on:"
echo "  📊 Backend:        http://localhost:8080"
echo "  ⚛️  Frontend:       http://localhost:3000" 
echo "  🤖 Agent Service:  http://localhost:8001"
echo "  🎤 Transcription:  http://localhost:8002"
echo ""
echo "📋 To stop all services, run: ./stop-dev.sh"
echo "📝 Service PIDs saved to: pids.tmp"
echo ""
echo "Press Ctrl+C to view logs or use 'tail -f' on individual service logs"

# Wait for user input to keep script running
trap 'echo ""; echo "🛑 Stopping all services..."; kill $(cat pids.tmp 2>/dev/null) 2>/dev/null; rm -f pids.tmp; echo "✅ All services stopped"; exit 0' INT

# Keep script running to capture logs
wait