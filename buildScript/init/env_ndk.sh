#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/AppData/Local/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

if command -v cygpath >/dev/null 2>&1; then
  [ -n "$ANDROID_HOME" ] && export ANDROID_HOME="$(cygpath -u "$ANDROID_HOME" 2>/dev/null || printf '%s' "$ANDROID_HOME")"
  [ -n "$ANDROID_NDK_HOME" ] && export ANDROID_NDK_HOME="$(cygpath -u "$ANDROID_NDK_HOME" 2>/dev/null || printf '%s' "$ANDROID_NDK_HOME")"
  [ -n "$NDK" ] && export NDK="$(cygpath -u "$NDK" 2>/dev/null || printf '%s' "$NDK")"
fi

_NDK="$ANDROID_HOME/ndk/25.0.8775105"
[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_NDK_HOME"
[ -f "$_NDK/source.properties" ] || _NDK="$NDK"
[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_HOME/ndk-bundle"

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found."
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
