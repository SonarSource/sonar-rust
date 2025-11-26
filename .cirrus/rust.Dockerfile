ARG CIRRUS_AWS_ACCOUNT=275878209202
FROM ${CIRRUS_AWS_ACCOUNT}.dkr.ecr.eu-central-1.amazonaws.com/base:j17-latest

USER root

RUN apt-get update \
    && apt-get -y install autoconf build-essential gcc-mingw-w64 git libtool musl-dev musl-tools pkg-config rustup \
    && apt-get clean


# Build and install aarch64-linux-musl cross-compiler
# This approach is based on https://github.com/cross-rs/cross/blob/main/docker/musl.sh
RUN <<EOF
  git clone https://github.com/richfelker/musl-cross-make.git
  # we checkout a specific commit to guarantee reproducibility (tags can be moved)
  git checkout 6f3701d08137496d5aac479e3a3977b5ae993c1f
  cd musl-cross-make
  echo -e "TARGET = aarch64-linux-musl\nOUTPUT = /usr/local\n" >> config.mak
  make
  make install
EOF

USER sonarsource

RUN <<EOF
rustup default 1.91.1
rustup component add llvm-tools
cargo install cargo-llvm-cov
rustup target add x86_64-pc-windows-gnu
rustup target add x86_64-unknown-linux-musl
rustup target add x86_64-unknown-linux-gnu
rustup target add aarch64-unknown-linux-musl
EOF
