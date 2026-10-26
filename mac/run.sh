#!/bin/bash
# Builds and runs Drip as a menu bar app. First run downloads nothing; it only needs Xcode command line tools.
cd "$(dirname "$0")"
swift build -c release 2>&1 | tail -3
echo "Starting Drip in the menu bar. Press Ctrl+C here to stop it."
exec .build/release/Drip
