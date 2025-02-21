ARG CIRRUS_AWS_ACCOUNT=275878209202
FROM ${CIRRUS_AWS_ACCOUNT}.dkr.ecr.eu-central-1.amazonaws.com/base:j17-latest

USER root

RUN apt-get update && \
    apt-get -y install rustup gcc-mingw-w64 musl-tools musl-dev build-essential autoconf libtool pkg-config && \
    apt-get clean


USER sonarsource

RUN <<EOF
rustup default 1.84.1
rustup component add llvm-tools
cargo install cargo-llvm-cov
rustup target add x86_64-pc-windows-gnu
rustup target add x86_64-unknown-linux-musl
rustup target add x86_64-unknown-linux-gnu
EOF
