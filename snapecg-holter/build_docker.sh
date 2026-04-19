#!/bin/bash
# Build SnapECG Holter inside Docker with mounted volumes for logs, artifacts, and Gradle cache.
# Usage: ./build_docker.sh [gradle args...]
# Default: ./build_docker.sh testDebugUnitTest assembleDebug

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/.docker-build"
GRADLE_CACHE="$BUILD_DIR/gradle-cache"
BUILD_LOG="$BUILD_DIR/build.log"

mkdir -p "$BUILD_DIR" "$GRADLE_CACHE"

GRADLE_ARGS="${@:-testDebugUnitTest assembleDebug}"

echo "Building: $GRADLE_ARGS"
echo "Log: $BUILD_LOG"

docker run --rm \
    -v "$SCRIPT_DIR":/project \
    -v "$GRADLE_CACHE":/root/.gradle \
    -v "$BUILD_DIR":/build-output \
    -w /project \
    snapecg-holter \
    sh -c "./gradlew $GRADLE_ARGS --no-daemon 2>&1 | tee /build-output/build.log; cp -r app/build/outputs /build-output/ 2>/dev/null || true; cp -r app/build/reports /build-output/ 2>/dev/null || true; cp -r app/build/test-results /build-output/ 2>/dev/null || true"

EXIT=$?

if grep -q "BUILD SUCCESSFUL" "$BUILD_LOG"; then
    echo ""
    echo "BUILD SUCCESSFUL"
    TEST_REPORT=$(find "$BUILD_DIR/reports" -name "index.html" 2>/dev/null | head -1)
    [ -n "$TEST_REPORT" ] && echo "Test report: $TEST_REPORT"
    APK=$(find "$BUILD_DIR/outputs" -name "*.apk" 2>/dev/null | head -1)
    [ -n "$APK" ] && echo "APK: $APK"
else
    echo ""
    echo "BUILD FAILED — see $BUILD_LOG"
    grep -E "^e:|error:|FAILURE|BUILD FAILED" "$BUILD_LOG" | tail -10
fi

exit $EXIT
