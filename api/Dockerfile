FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/fusion-api ./cmd/server

FROM alpine:3.20
RUN adduser -D -u 10001 fusion
USER fusion
COPY --from=build /out/fusion-api /usr/local/bin/fusion-api
EXPOSE 8080
ENTRYPOINT ["fusion-api"]
