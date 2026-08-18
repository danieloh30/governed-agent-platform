package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"regexp"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

var dangerousHeaderPattern = regexp.MustCompile(`(?i)(x-mcp-|x-forwarded-|x-real-ip)`)

var blockedToolPatterns = []string{
	"__proto__",
	"constructor",
	"../",
	"eval(",
	"exec(",
	"<script",
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "9001"
	}

	lis, err := net.Listen("tcp", ":"+port)
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}

	s := grpc.NewServer()
	RegisterExtMcpServer(s, &guardrailServer{})

	log.Printf("ExtMCP guardrail server listening on :%s", port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
}

type guardrailServer struct {
	UnimplementedExtMcpServer
}

func (s *guardrailServer) CheckRequest(req *CheckRequestMessage) (*CheckResponseMessage, error) {
	if req.Method == "tools/call" {
		if err := sanitizeHeaders(req.Headers); err != nil {
			return denyResponse(-32001, fmt.Sprintf("header sanitization failed: %v", err)), nil
		}

		if err := checkToolPoisoning(req.Params); err != nil {
			return denyResponse(-32001, fmt.Sprintf("tool poisoning detected: %v", err)), nil
		}
	}

	return &CheckResponseMessage{Action: &CheckResponseMessage_Pass{}}, nil
}

func (s *guardrailServer) CheckResponse(req *CheckRequestMessage) (*CheckResponseMessage, error) {
	if req.Method == "tools/list" {
		return appendGuardrailMarker(req)
	}
	return &CheckResponseMessage{Action: &CheckResponseMessage_Pass{}}, nil
}

func sanitizeHeaders(headers map[string]string) error {
	for key, value := range headers {
		if dangerousHeaderPattern.MatchString(key) {
			if strings.Contains(value, "\r") || strings.Contains(value, "\n") {
				return fmt.Errorf("header %q contains CRLF injection attempt", key)
			}

			if len(value) > 256 {
				return fmt.Errorf("header %q exceeds maximum length (256 bytes)", key)
			}
		}
	}
	return nil
}

func checkToolPoisoning(params json.RawMessage) error {
	var p struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if err := json.Unmarshal(params, &p); err != nil {
		return fmt.Errorf("malformed tool call params")
	}

	lower := strings.ToLower(p.Name)
	for _, pattern := range blockedToolPatterns {
		if strings.Contains(lower, pattern) {
			return fmt.Errorf("tool name %q contains blocked pattern %q", p.Name, pattern)
		}
	}

	argStr := string(p.Arguments)
	for _, pattern := range blockedToolPatterns {
		if strings.Contains(strings.ToLower(argStr), pattern) {
			return fmt.Errorf("tool arguments contain blocked pattern %q", pattern)
		}
	}

	return nil
}

func appendGuardrailMarker(req *CheckRequestMessage) (*CheckResponseMessage, error) {
	var result struct {
		Tools []struct {
			Name        string          `json:"name"`
			Description string          `json:"description"`
			InputSchema json.RawMessage `json:"inputSchema"`
		} `json:"tools"`
	}
	if err := json.Unmarshal(req.Result, &result); err != nil {
		return &CheckResponseMessage{Action: &CheckResponseMessage_Pass{}}, nil
	}

	for i := range result.Tools {
		result.Tools[i].Description += " [guardrail-verified]"
	}

	mutated, _ := json.Marshal(result)
	return &CheckResponseMessage{
		Action: &CheckResponseMessage_Mutate{
			Result: mutated,
		},
	}, nil
}

func denyResponse(code int, message string) *CheckResponseMessage {
	return &CheckResponseMessage{
		Action: &CheckResponseMessage_Deny{
			Error: &JsonRpcError{
				Code:    int32(code),
				Message: message,
			},
		},
	}
}

// Stub types matching the ExtMCP gRPC protocol.
// In production, generate from the agentgateway proto file.

type CheckRequestMessage struct {
	Method  string            `json:"method"`
	Headers map[string]string `json:"headers"`
	Params  json.RawMessage   `json:"params"`
	Result  json.RawMessage   `json:"result"`
}

type CheckResponseMessage struct {
	Action interface{} `json:"action"`
}

type JsonRpcError struct {
	Code    int32  `json:"code"`
	Message string `json:"message"`
}

type ExtMcpServer interface {
	CheckRequest(*CheckRequestMessage) (*CheckResponseMessage, error)
	CheckResponse(*CheckRequestMessage) (*CheckResponseMessage, error)
}

type UnimplementedExtMcpServer struct{}

func (UnimplementedExtMcpServer) CheckRequest(*CheckRequestMessage) (*CheckResponseMessage, error) {
	return nil, status.Errorf(codes.Unimplemented, "not implemented")
}

func (UnimplementedExtMcpServer) CheckResponse(*CheckRequestMessage) (*CheckResponseMessage, error) {
	return nil, status.Errorf(codes.Unimplemented, "not implemented")
}

func RegisterExtMcpServer(s *grpc.Server, srv ExtMcpServer) {
	// Registration would use the generated proto descriptor in production.
	// This stub shows the guardrail logic pattern.
	log.Println("ExtMCP guardrail service registered")
}
