FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY src/ .
RUN go mod init extmcp-guardrail && \
    go mod tidy && \
    CGO_ENABLED=0 go build -o /guardrail .

FROM alpine:3.20
COPY --from=builder /guardrail /guardrail
EXPOSE 9001
ENTRYPOINT ["/guardrail"]
