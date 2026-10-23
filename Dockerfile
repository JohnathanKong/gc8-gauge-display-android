FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG GRADLE_VERSION=8.9
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/android/.gradle \
    PATH=/opt/gradle/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip git bash && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" && \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/android-tools.zip && \
    unzip -q /tmp/android-tools.zip -d /tmp/android-cmdline && \
    mv /tmp/android-cmdline/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest" && \
    rm -rf /tmp/android-cmdline /tmp/android-tools.zip

RUN yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null && \
    sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
      "platform-tools" "platforms;android-35" "build-tools;35.0.0"

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv "/opt/gradle-${GRADLE_VERSION}" /opt/gradle && \
    rm /tmp/gradle.zip

RUN useradd -m -s /bin/bash android && \
    mkdir -p /workspace /home/android/.gradle /home/android/.android && \
    chown -R android:android /workspace /home/android/.gradle /home/android/.android /opt/android-sdk
USER android
WORKDIR /workspace
CMD ["bash"]
