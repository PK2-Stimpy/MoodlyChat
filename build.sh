#!/bin/bash
set -e

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
BUILD_DIR="$SCRIPT_DIR/build"

cd "$SCRIPT_DIR/src"

echo "Compiling..."
javac *.java

mkdir -p "$BUILD_DIR"
mv -f *.class "$BUILD_DIR/"

echo "Done. Classes are in $BUILD_DIR"