#!/bin/bash
set -euxo pipefail

trap 'echo "Script exited with code $?"' EXIT

export GIT_SHA1=${CIRRUS_CHANGE_IN_REPO}
export GITHUB_BASE_BRANCH=${CIRRUS_BASE_BRANCH:-}
export GITHUB_BRANCH=${CIRRUS_BRANCH}
export GITHUB_REPO=${CIRRUS_REPO_FULL_NAME}
export PULL_REQUEST=${CIRRUS_PR:-}
export SONAR_HOST_URL=${SONAR_HOST_URL:-https://sonarcloud.io}
export JAVA_BINARIES_FOLDERS=file-sources-service/api-lambda,file-sources-service/archiver-lambda
export PROJECT_KEY=${PROJECT_KEY:-SonarSource_sonar-rust}
export SONAR_TOKEN=${SONAR_TOKEN:-}

echo "[DEBUG] GIT_SHA1: ${GIT_SHA1}"
echo "[DEBUG] GITHUB_BASE_BRANCH: ${GITHUB_BASE_BRANCH}"
echo "[DEBUG] GITHUB_BRANCH: ${GITHUB_BRANCH}"
echo "[DEBUG] GITHUB_REPO: ${GITHUB_REPO}"
echo "[DEBUG] PULL_REQUEST: ${PULL_REQUEST}"
echo "[DEBUG] SONAR_HOST_URL: ${SONAR_HOST_URL}"
echo "[DEBUG] PROJECT_KEY: ${PROJECT_KEY}"


echo "[DEBUG] Entering main conditional: PULL_REQUEST='${PULL_REQUEST}', GITHUB_BRANCH='${GITHUB_BRANCH}'"
if [[ "${PULL_REQUEST}" ]] || [[ "${GITHUB_BRANCH}" == "master" ]]; then
  scanner_params=()

  if [[ "${GITHUB_BASE_BRANCH}" ]]; then
    git fetch origin "${GITHUB_BASE_BRANCH}"
  fi

  if [[ "${PULL_REQUEST}" ]]; then
    scanner_params+=("-Dsonar.analysis.prNumber=${PULL_REQUEST}")
  fi

  scanner_params+=(
    "-Dsonar.host.url=${SONAR_HOST_URL}"
    "-Dsonar.token=${SONAR_TOKEN}"
    "-Dsonar.qualitygate.wait=false"
    "-Dsonar.analysis.pipeline=${CIRRUS_BUILD_ID}"
    "-Dsonar.analysis.repository=${GITHUB_REPO}"
    "-Dsonar.analysis.sha1=${GIT_SHA1}"
    "-Dsonar.organization=sonarsource"
    "-Dsonar.projectKey=${PROJECT_KEY}"
    # "-Dsonar.java.binaries=${JAVA_BINARIES_FOLDERS}"
    # "-Dsonar.python.version=3"
    #"-Dsonar.python.coverage.reportPaths=**/build/coverage.xml"
    #"-Dsonar.coverage.jacoco.xmlReportPaths=**/build/jacoco-coverage.xml"
    "-Dsonar.gradle.scanAll=true"
    "-Dsonar.exclusions=tools,**/build/**/*"
    "-Dsonar.links.ci=https://cirrus-ci.com/github/SonarSource/sonar-rust"
    "-Dsonar.links.scm=https://github.com/SonarSource/sonar-rust"
    "-Dsonar.links.issue=https://jira.sonarsource.com/projects/SKUNK"
    "-Dsonar.rust.lcov.reportPaths=analyzer/target/llvm-cov-target/coverage.lcov"
    "-Dsonar.rust.cargo.manifestPaths=analyzer/Cargo.toml"
    # "-Dsonar.test.inclusions=**/src/test/**,**/tests/**"
  )

  echo "[DEBUG] Running sonar-scanner with params: ${scanner_params[*]}"
  gradle --no-daemon --info --stacktrace --console plain build sonar "${scanner_params[@]}"
else
  echo "[DEBUG] Skipping scan: neither PULL_REQUEST nor GITHUB_BRANCH=master."
fi