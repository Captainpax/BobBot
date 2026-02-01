#!/bin/sh

# Start Node.js API in background
echo "Starting OSRS API..."
node /app/osrs-api/dist/index.js &

# Wrapper script to handle bot restarts without killing the Docker container.
# Exit code 2 means the bot wants to restart.
# Exit code 0 means the bot wants to shut down.

while true; do
    echo "Starting BobBot..."
    /app/bin/bobbot "$@"
    EXIT_CODE=$?
    
    if [ $EXIT_CODE -eq 2 ]; then
        echo "Bot requested restart (exit code 2). Restarting in 5 seconds..."
        sleep 5
    else
        echo "Bot exited with code $EXIT_CODE. Shutting down container."
        exit $EXIT_CODE
    fi
done
