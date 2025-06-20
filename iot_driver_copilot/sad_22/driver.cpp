#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <thread>
#include <map>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <algorithm>
#include <cstdio>
#include <cctype>
#include <csignal>
#include <netinet/in.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <arpa/inet.h>
#include <jsoncpp/json/json.h> // Requires libjsoncpp-dev

// Helper: Read all from socket until delimiter (HTTP request end)
std::string recv_until(int sockfd, const std::string& delim) {
    std::string data;
    char buf[1024];
    size_t matched = 0;
    while (true) {
        ssize_t n = recv(sockfd, buf, sizeof(buf), 0);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; ++i) {
            data += buf[i];
            if (buf[i] == delim[matched]) {
                ++matched;
                if (matched == delim.size()) return data;
            } else {
                matched = (buf[i] == delim[0]) ? 1 : 0;
            }
        }
        if (data.size() > 65536) break; // sanity
    }
    return data;
}

// Helper: Base64 encode
static const std::string base64_chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
std::string base64_encode(const unsigned char* bytes_to_encode, unsigned int in_len) {
    std::string ret;
    int i = 0;
    int j = 0;
    unsigned char char_array_3[3];
    unsigned char char_array_4[4];
    while (in_len--) {
        char_array_3[i++] = *(bytes_to_encode++);
        if (i == 3) {
            char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
            char_array_4[1] = ((char_array_3[0] & 0x03) << 4) +
                              ((char_array_3[1] & 0xf0) >> 4);
            char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) +
                              ((char_array_3[2] & 0xc0) >> 6);
            char_array_4[3] = char_array_3[2] & 0x3f;
            for(i = 0; i < 4 ; i++)
                ret += base64_chars[char_array_4[i]];
            i = 0;
        }
    }
    if (i) {
        for(j = i; j < 3; j++)
            char_array_3[j] = '\0';
        char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
        char_array_4[1] = ((char_array_3[0] & 0x03) << 4) +
                          ((char_array_3[1] & 0xf0) >> 4);
        char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) +
                          ((char_array_3[2] & 0xc0) >> 6);
        char_array_4[3] = char_array_3[2] & 0x3f;
        for (j = 0; (j < i + 1); j++)
            ret += base64_chars[char_array_4[j]];
        while((i++ < 3))
            ret += '=';
    }
    return ret;
}

// Simple placeholder for device binary data acquisition
std::vector<uint8_t> get_device_binary_data(const std::string& filter = "", int page = 0) {
    // Simulate binary data (random bytes)
    std::vector<uint8_t> data(32);
    srand(time(NULL) + page);
    for (size_t i = 0; i < data.size(); ++i) {
        data[i] = rand() % 256;
    }
    return data;
}

// Simple placeholder for sending command to device
std::string execute_device_command(const std::string& cmd, const Json::Value& params) {
    // Simulate device command execution
    Json::Value result;
    result["status"] = "success";
    result["command"] = cmd;
    result["params"] = params;
    Json::FastWriter writer;
    return writer.write(result);
}

struct HttpRequest {
    std::string method;
    std::string uri;
    std::map<std::string, std::string> headers;
    std::string body;
    std::map<std::string, std::string> query_params;
};

HttpRequest parse_http_request(const std::string& raw) {
    HttpRequest req;
    std::istringstream iss(raw);
    std::string line;
    getline(iss, line);
    std::istringstream reqline(line);
    reqline >> req.method;
    reqline >> req.uri;

    // Parse query params
    auto qpos = req.uri.find('?');
    if (qpos != std::string::npos) {
        std::string path = req.uri.substr(0, qpos);
        std::string query = req.uri.substr(qpos + 1);
        req.uri = path;
        std::istringstream qss(query);
        std::string pair;
        while (getline(qss, pair, '&')) {
            auto eq = pair.find('=');
            if (eq != std::string::npos) {
                std::string key = pair.substr(0, eq);
                std::string val = pair.substr(eq + 1);
                req.query_params[key] = val;
            }
        }
    }

    // Headers
    while (getline(iss, line) && line != "\r") {
        auto colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = line.substr(0, colon);
            std::string val = line.substr(colon + 1);
            key.erase(std::remove_if(key.begin(), key.end(), ::isspace), key.end());
            val.erase(0, val.find_first_not_of(" \t\r\n"));
            val.erase(val.find_last_not_of(" \t\r\n") + 1);
            req.headers[key] = val;
        }
    }

    // Body
    std::string body;
    while (getline(iss, line)) {
        body += line + "\n";
    }
    req.body = body;
    return req;
}

void send_http_response(int sockfd, int status, const std::string& status_text, const std::string& content_type, const std::string& body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << " " << status_text << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    oss << body;
    send(sockfd, oss.str().c_str(), oss.str().size(), 0);
}

// Handler for /data GET
void handle_data(int sockfd, const HttpRequest& req) {
    std::string filter = "";
    int page = 0;
    if (req.query_params.count("filter")) filter = req.query_params.at("filter");
    if (req.query_params.count("page")) page = atoi(req.query_params.at("page").c_str());
    auto data = get_device_binary_data(filter, page);
    std::string encoded = base64_encode(data.data(), data.size());
    Json::Value resp;
    resp["data"] = encoded;
    resp["length"] = (int)data.size();
    resp["filter"] = filter;
    resp["page"] = page;
    Json::FastWriter writer;
    send_http_response(sockfd, 200, "OK", "application/json", writer.write(resp));
}

// Handler for /commands POST
void handle_commands(int sockfd, const HttpRequest& req) {
    Json::Value root;
    Json::Reader reader;
    if (!reader.parse(req.body, root)) {
        send_http_response(sockfd, 400, "Bad Request", "application/json", "{\"error\":\"Invalid JSON\"}");
        return;
    }
    if (!root.isMember("command")) {
        send_http_response(sockfd, 400, "Bad Request", "application/json", "{\"error\":\"Missing 'command' field\"}");
        return;
    }
    std::string cmd = root["command"].asString();
    Json::Value params = root.get("parameters", Json::Value());
    std::string result = execute_device_command(cmd, params);
    send_http_response(sockfd, 200, "OK", "application/json", result);
}

void process_connection(int client_sock) {
    std::string request_raw = recv_until(client_sock, "\r\n\r\n");
    if (request_raw.empty()) {
        close(client_sock);
        return;
    }
    HttpRequest req = parse_http_request(request_raw);

    // Read more if POST with a body
    if (req.method == "POST" && req.headers.count("Content-Length")) {
        int content_length = atoi(req.headers["Content-Length"].c_str());
        if ((int)req.body.length() < content_length) {
            std::vector<char> extra(content_length - req.body.length());
            int got = recv(client_sock, extra.data(), extra.size(), 0);
            if (got > 0) req.body.append(extra.data(), got);
        }
    }

    if (req.method == "GET" && req.uri == "/data") {
        handle_data(client_sock, req);
    } else if (req.method == "POST" && req.uri == "/commands") {
        handle_commands(client_sock, req);
    } else {
        send_http_response(client_sock, 404, "Not Found", "text/plain", "404 Not Found\n");
    }

    close(client_sock);
}

std::atomic<bool> running(true);
void signal_handler(int signo) {
    running = false;
}

int main() {
    // Environment variables
    const char* env_http_port = std::getenv("HTTP_PORT");
    const char* env_server_host = std::getenv("SERVER_HOST");
    const char* env_device_ip = std::getenv("DEVICE_IP");
    int http_port = env_http_port ? atoi(env_http_port) : 8080;
    std::string server_host = env_server_host ? env_server_host : "0.0.0.0";
    std::string device_ip = env_device_ip ? env_device_ip : "127.0.0.1";

    // Setup signal handling
    std::signal(SIGINT, signal_handler);
    std::signal(SIGTERM, signal_handler);

    // Create server socket
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) { perror("socket"); exit(1); }
    int opt = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(http_port);
    addr.sin_addr.s_addr = inet_addr(server_host.c_str());
    if (bind(sockfd, (sockaddr*)&addr, sizeof(addr)) < 0) { perror("bind"); exit(1); }
    if (listen(sockfd, 16) < 0) { perror("listen"); exit(1); }
    std::cout << "HTTP DeviceShifu Driver listening on " << server_host << ":" << http_port << std::endl;

    while (running) {
        sockaddr_in cliaddr;
        socklen_t clilen = sizeof(cliaddr);
        int client_sock = accept(sockfd, (sockaddr*)&cliaddr, &clilen);
        if (client_sock < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }
        std::thread(process_connection, client_sock).detach();
    }
    close(sockfd);
    return 0;
}