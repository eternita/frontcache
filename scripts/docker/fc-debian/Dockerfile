FROM debian:jessie

RUN apt-get update && apt-get install -y --no-install-recommends \
                ca-certificates \
                curl \
                wget \
                vim \
                apt-utils \
                unzip \
                jq \
        && rm -rf /var/lib/apt/lists/*
