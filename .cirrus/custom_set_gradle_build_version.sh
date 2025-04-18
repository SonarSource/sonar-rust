#!/bin/bash

# Original script is not compatible with Busybox shell used in Alpine Linux in arm64.Dockerfile container

# Transform Gradle project version to semver-like version without SNAPSHOT and with build number.
# If BUILD_NUMBER is unset, then it must be passed as an argument.

set -euo pipefail

BUILD_NUMBER=${BUILD_NUMBER:=$1}

current_version=$(gradle properties --no-scan | grep 'version:' | tr -d "[:space:]" | cut -d ":" -f 2)
release_version="${current_version/-SNAPSHOT/}"
if [[ "${release_version}" =~ ^[0-9]+\.[0-9]+$ ]]; then
  release_version="${release_version}.0"
fi
release_version="${release_version}.${BUILD_NUMBER}"

echo "Replacing version $current_version with $release_version"
sed -i.bak "s/$current_version/$release_version/g" gradle.properties
export PROJECT_VERSION=$release_version
