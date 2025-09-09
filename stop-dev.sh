#!/bin/bash

# CuraSnap AI Development Services Stop Script
# Stops all services started by start-dev.sh

echo "🛑 Stopping CuraSnap AI development services..."

if [ -f "pids.tmp" ]; then
    echo "📋 Found PID file, stopping services..."
    
    while read -r pid; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "⏹️  Stopping service (PID: $pid)..."
            kill "$pid" 2>/dev/null
        fi
    done < pids.tmp
    
    # Wait a moment for graceful shutdown
    sleep 2
    
    # Force kill any remaining processes
    while read -r pid; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "💥 Force stopping service (PID: $pid)..."
            kill -9 "$pid" 2>/dev/null
        fi
    done < pids.tmp
    
    rm -f pids.tmp
    echo "✅ All services stopped successfully!"
else
    echo "❌ No PID file found. Services may not have been started with start-dev.sh"
    echo "💡 Trying to stop common development processes..."
    
    # Try to stop common processes by name
    pkill -f "spring-boot:run" 2>/dev/null && echo "🔧 Stopped Spring Boot"
    pkill -f "npm run dev" 2>/dev/null && echo "🌐 Stopped Frontend"  
    pkill -f "uvicorn.*8001" 2>/dev/null && echo "🤖 Stopped Agent Service"
    pkill -f "uvicorn.*8002" 2>/dev/null && echo "🎤 Stopped Transcription Service"
fi

echo "🏁 Cleanup complete!"