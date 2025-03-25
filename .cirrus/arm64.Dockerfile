ARG CIRRUS_AWS_ACCOUNT=275878209202
FROM ${CIRRUS_AWS_ACCOUNT}.dkr.ecr.eu-central-1.amazonaws.com/base:java-17

USER root

RUN apk add --no-cache autoconf bash build-base musl-dev pkgconfig rustup

USER sonarsource

RUN <<EOF
rustup-init -y
source "$HOME/.cargo/env"
rustup default 1.85.0
rustup component add llvm-tools clippy
cargo install cargo-llvm-cov
EOF
