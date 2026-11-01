FROM mcr.microsoft.com/playwright:v1.61.1-noble@sha256:5b8f294aff9041b7191c34a4bab3ac270157a28774d4b0660e9743297b697e48
RUN apt-get update -qq && apt-get install --no-install-recommends --yes libnss3-tools=2:3.98-1ubuntu0.3 && rm -rf /var/lib/apt/lists/*
WORKDIR /qualification
COPY package.json package-lock.json ./
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY *.mjs trust-and-run.sh ./
ENTRYPOINT ["bash", "/qualification/trust-and-run.sh"]
