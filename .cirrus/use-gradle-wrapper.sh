#!/bin/bash

# Infra script "regular_gradle_build_deploy_analyze" prefers to use gradle from the image instead of the wrapper, this is done to force use
# of wrapper

function gradle {
  ./gradlew "$@"
}

export -f gradle
