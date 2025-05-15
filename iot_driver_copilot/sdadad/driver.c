#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <ctype.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>

// Maximum request size
#define REQ_BUF_SZ 4096
#define RESP_BUF_SZ 8192

// Helper to read env with default
const char* getenvd(const char* key, const char* def) {
    const char *val = getenv(key);
    return val ? val : def;
}

// JSON response for /info endpoint
const char* device_info_json() {
    return "{"
        "\"device_name\":\"sdadad\","
        "\"device_model\":\"sda\","
        "\"manufacturer\":\"sadasd\","
        "\"device_type\":\"dasdasd\","
        "\"primary_protocol\":\"sdadasd\""
        "}";
}

// Simulate current data points (could be fetched from device)
void get_device_data(char* buf, size_t sz) {
    // Example payload
    snprintf(buf, sz,
        "{"
            "\"data_points\": {"
                "\"dasdasdsd\": \"value\""
            "}"
        "}"
    );
}

// Simulate sending a command to the device
int process_device_command(const char* cmd_payload, char* resp, size_t sz) {
    // In a real driver, parse and send the command to the device
    // Here we echo back for demonstration
    snprintf(resp, sz, "{\"status\":\"success\",\"received\":%s}", cmd_payload);
    return 0;
}

// Minimal HTTP parser for path and method
void parse_http_request(const char* req, char* method, char* path) {
    sscanf(req, "%15s %255s", method, path);
}

// Helper: skip HTTP headers, return pointer to body
const char* skip_http_headers(const char* req) {
    const char* p = strstr(req, "\r\n\r\n");
    return p ? p + 4 : "";
}

// HTTP response helpers
void http_response(int client, const char* status, const char* content_type, const char* body) {
    char header[512];
    snprintf(header, sizeof(header),
        "HTTP/1.1 %s\r\n"
        "Content-Type: %s\r\n"
        "Content-Length: %zu\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Connection: close\r\n"
        "\r\n",
        status, content_type, strlen(body));
    send(client, header, strlen(header), 0);
    send(client, body, strlen(body), 0);
}

void http_response_404(int client) {
    http_response(client, "404 Not Found", "application/json", "{\"error\":\"Not found\"}");
}

void http_response_405(int client) {
    http_response(client, "405 Method Not Allowed", "application/json", "{\"error\":\"Method not allowed\"}");
}

// HTTP server main loop
void http_server_loop(int server_fd) {
    char req_buf[REQ_BUF_SZ];
    char resp_buf[RESP_BUF_SZ];

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client < 0) continue;

        ssize_t rlen = recv(client, req_buf, REQ_BUF_SZ-1, 0);
        if (rlen <= 0) { close(client); continue; }
        req_buf[rlen] = 0;

        char method[16] = {0}, path[256] = {0};
        parse_http_request(req_buf, method, path);

        if (strcmp(path, "/info") == 0 && strcmp(method, "GET") == 0) {
            http_response(client, "200 OK", "application/json", device_info_json());
        }
        else if (strcmp(path, "/data") == 0 && strcmp(method, "GET") == 0) {
            get_device_data(resp_buf, sizeof(resp_buf));
            http_response(client, "200 OK", "application/json", resp_buf);
        }
        else if (strcmp(path, "/cmd") == 0 && strcmp(method, "POST") == 0) {
            const char* body = skip_http_headers(req_buf);
            process_device_command(body, resp_buf, sizeof(resp_buf));
            http_response(client, "200 OK", "application/json", resp_buf);
        }
        else if (
            (strcmp(path, "/info") == 0 || strcmp(path, "/data") == 0) && strcmp(method, "POST") == 0
            ||
            (strcmp(path, "/cmd") == 0 && strcmp(method, "GET") == 0)
        ) {
            http_response_405(client);
        }
        else {
            http_response_404(client);
        }

        close(client);
    }
}

int main() {
    // Config from env
    const char *server_host = getenvd("SERVER_HOST", "0.0.0.0");
    const char *server_port_str = getenvd("SERVER_PORT", "8080");
    int server_port = atoi(server_port_str);

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) { perror("socket"); exit(1); }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr(server_host);
    addr.sin_port = htons(server_port);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind"); close(server_fd); exit(1);
    }

    if (listen(server_fd, 8) < 0) {
        perror("listen"); close(server_fd); exit(1);
    }

    printf("HTTP server listening on %s:%d\n", server_host, server_port);

    http_server_loop(server_fd);

    close(server_fd);
    return 0;
}