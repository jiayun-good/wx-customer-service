#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ctype.h>
#include <errno.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>

#define BUF_SIZE 8192
#define SMALL_BUF 256

// Helper to read environment variable or fallback
char* get_env(const char* name, const char* fallback) {
    char* val = getenv(name);
    if (!val || !*val) return (char*)fallback;
    return val;
}

// Mock function to simulate device data retrieval
// In a real use case, this must connect to the device using sdadasd protocol and fetch data
char* fetch_device_data() {
    // Return dummy JSON. Replace with actual protocol logic.
    return "{\"temperature\":22.5,\"humidity\":55}";
}

// Mock function to simulate device command execution
// In a real use case, this must send the command to the device using sdadasd protocol
int send_device_command(const char* cmd_json, char* response, size_t max_len) {
    snprintf(response, max_len, "{\"status\":\"ok\",\"command_received\":%s}", cmd_json);
    return 0;
}

// Server responds with device info
void handle_info(int client) {
    char resp[BUF_SIZE];
    snprintf(resp, sizeof(resp),
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        "Connection: close\r\n"
        "\r\n"
        "{"
            "\"device_name\":\"sdadad\","
            "\"device_model\":\"sda\","
            "\"manufacturer\":\"sadasd\","
            "\"device_type\":\"dasdasd\","
            "\"primary_protocol\":\"sdadasd\""
        "}");
    send(client, resp, strlen(resp), 0);
}

// Server responds with device data
void handle_data(int client) {
    char* data = fetch_device_data();
    char resp[BUF_SIZE];
    snprintf(resp, sizeof(resp),
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        "Connection: close\r\n"
        "\r\n"
        "%s", data);
    send(client, resp, strlen(resp), 0);
}

// Server handles command POST
void handle_cmd(int client, const char* req_body) {
    char resp_json[BUF_SIZE];
    send_device_command(req_body, resp_json, sizeof(resp_json));
    char resp[BUF_SIZE];
    snprintf(resp, sizeof(resp),
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        "Connection: close\r\n"
        "\r\n"
        "%s", resp_json);
    send(client, resp, strlen(resp), 0);
}

// Read a line from socket, up to maxlen or \n, returns length read
ssize_t read_line(int fd, char* buf, size_t maxlen) {
    ssize_t n, rc;
    char c;
    for (n = 0; n < maxlen - 1; n++) {
        rc = recv(fd, &c, 1, 0);
        if (rc == 1) {
            buf[n] = c;
            if (c == '\n') {
                n++;
                break;
            }
        } else if (rc == 0) {
            break;
        } else {
            if (errno == EINTR) continue;
            return -1;
        }
    }
    buf[n] = 0;
    return n;
}

// Parse HTTP request, return method, path, and if POST, the body
void parse_http_request(int client, char* method, char* path, char* body, size_t body_size) {
    char line[SMALL_BUF];
    int content_length = 0;
    int is_post = 0;

    // Read request line
    read_line(client, line, sizeof(line));
    sscanf(line, "%s %s", method, path);

    // Read headers
    while (1) {
        ssize_t n = read_line(client, line, sizeof(line));
        if (n <= 2) break; // \r\n alone means end of headers
        if (strncasecmp(line, "Content-Length:", 15) == 0) {
            content_length = atoi(line + 15);
        }
        if (strcasecmp(method, "POST") == 0) is_post = 1;
    }
    // Read body if POST
    if (is_post && content_length > 0 && body_size > 0) {
        int total = 0, n;
        while (total < content_length && total < (int)body_size - 1) {
            n = recv(client, body + total, content_length - total, 0);
            if (n <= 0) break;
            total += n;
        }
        body[total] = 0;
    } else if (body_size > 0) {
        body[0] = 0;
    }
}

// Main HTTP handler
void handle_client(int client) {
    char method[SMALL_BUF], path[SMALL_BUF], body[BUF_SIZE];

    parse_http_request(client, method, path, body, sizeof(body));

    if (strcasecmp(method, "GET") == 0 && strcmp(path, "/info") == 0) {
        handle_info(client);
    } else if (strcasecmp(method, "GET") == 0 && strcmp(path, "/data") == 0) {
        handle_data(client);
    } else if (strcasecmp(method, "POST") == 0 && strcmp(path, "/cmd") == 0) {
        handle_cmd(client, body);
    } else {
        const char* resp =
            "HTTP/1.1 404 Not Found\r\n"
            "Content-Type: text/plain\r\n"
            "Connection: close\r\n"
            "\r\n"
            "404 Not Found\n";
        send(client, resp, strlen(resp), 0);
    }
    close(client);
}

int main() {
    const char* host = get_env("DRIVER_SERVER_HOST", "0.0.0.0");
    int port = atoi(get_env("DRIVER_SERVER_PORT", "8080"));

    int server_fd, client_fd;
    struct sockaddr_in addr, client_addr;
    socklen_t client_len = sizeof(client_addr);

    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        perror("socket");
        exit(1);
    }
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = inet_addr(host);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        exit(1);
    }
    if (listen(server_fd, 10) < 0) {
        perror("listen");
        exit(1);
    }

    printf("HTTP server listening on %s:%d\n", host, port);

    while (1) {
        client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) continue;
        handle_client(client_fd);
    }
    close(server_fd);
    return 0;
}