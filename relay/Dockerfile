FROM golang:1.25-bookworm AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/dsh-links-relay ./cmd/dsh-links-relay

# Seed named-volume roots with the ownership used by the two runtime roles.
RUN install -d -m 0555 /out/rootfs/etc/dsh-links-relay \
    && install -d -m 0700 -o 10001 -g 10000 /out/rootfs/var/lib/dsh-links-relay \
    && install -d -m 0770 -o 10001 -g 10000 /out/rootfs/run/dsh-links-relay

# No shell, package manager, or ambient utilities in the runtime image.
FROM scratch
COPY --from=build /out/rootfs/ /
COPY --from=build /out/dsh-links-relay /usr/local/bin/dsh-links-relay

USER 10001:10000
ENTRYPOINT ["/usr/local/bin/dsh-links-relay"]
CMD ["control", "--config", "/etc/dsh-links-relay/config.toml"]
