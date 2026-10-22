#!/usr/bin/env bash
# Compiles and runs TestHarness.java against the SAME core logic files that
# ship inside the app (app/src/main/java/com/mtagj/bptracker/core/*.java) —
# no Android SDK needed, just a plain JDK. Run from anywhere; paths are
# resolved relative to this script.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/com/mtagj/bptracker/core
cp ../app/src/main/java/com/mtagj/bptracker/core/*.java build/com/mtagj/bptracker/core/
javac -d build/out build/com/mtagj/bptracker/core/*.java TestHarness.java
java -cp build/out TestHarness
