FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"

RUN apt-get update && apt-get install -y --no-install-recommends unzip wget && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

WORKDIR /project
COPY . .

# Generate gradlew if missing
RUN if [ ! -f gradlew ]; then \
        gradle wrapper --gradle-version 8.5 2>/dev/null || \
        (echo '#!/bin/sh\nexec gradle "$@"' > gradlew && chmod +x gradlew); \
    fi && \
    chmod +x gradlew 2>/dev/null || true

# Default: run unit tests then build APK
CMD ["sh", "-c", "./gradlew testDebugUnitTest --no-daemon && ./gradlew assembleDebug --no-daemon"]
