ARG CIRRUS_AWS_ACCOUNT=275878209202
FROM ${CIRRUS_AWS_ACCOUNT}.dkr.ecr.eu-central-1.amazonaws.com/base:j17-latest

USER root

RUN apt-get update && apt-get -y install rustup && apt-get clean

USER sonarsource

RUN rustup default 1.84.1 \\
  && rustup component add llvm-tools \\
  && cargo install cargo-llvm-cov
