#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <vector>
#include <unordered_map>
#include <algorithm>
#include <cstring>
#include <thread>
#include <mutex>
#include <netinet/in.h>
#include <unistd.h>

#define BUFFER_SIZE 8192

// Utility: URL decode
std::string url_decode(const std::string& in) {
    std::string out; out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        if (in[i] == '%') {
            int val;
            std::istringstream is(in.substr(i + 1, 2));
            if (is >> std::hex >> val) {
                out += static_cast<char>(val);
                i += 2;
            }
        } else if (in[i] == '+') {
            out += ' ';
        } else {
            out += in[i];
        }
    }
    return out;
}

// Parse query string into map
std::unordered_map<std::string, std::string> parse_query(const std::string& qs) {
    std::unordered_map<std::string, std::string> params;
    std::istringstream ss(qs);
    std::string pair;
    while (std::getline(ss, pair, '&')) {
        auto eq = pair.find('=');
        if (eq != std::string::npos) {
            params[url_decode(pair.substr(0, eq))] = url_decode(pair.substr(eq + 1));
        }
    }
    return params;
}

// Parse HTTP headers into map
std::unordered_map<std::string, std::string> parse_headers(const std::vector<std::string>& lines) {
    std::unordered_map<std::string, std::string> headers;
    for (size_t i = 1; i < lines.size(); ++i) {
        auto delim = lines[i].find(":");
        if (delim != std::string::npos) {
            std::string key = lines[i].substr(0, delim);
            std::string value = lines[i].substr(delim + 1);
            key.erase(std::remove_if(key.begin(), key.end(), ::isspace), key.end());
            value.erase(0, value.find_first_not_of(" \t"));
            headers[key] = value;
        }
    }
    return headers;
}

// Read a full HTTP request from socket
bool read_http_request(int client_fd, std::string& out_request) {
    char buffer[BUFFER_SIZE];
    int received = recv(client_fd, buffer, BUFFER_SIZE - 1, 0);
    if (received <= 0) return false;
    buffer[received] = '\0';
    out_request = buffer;
    return true;
}

// Simple HTTP response sender
void send_http_response(int client_fd, int status_code, const std::string& status_msg, const std::string& content_type, const std::string& body) {
    std::ostringstream resp;
    resp << "HTTP/1.1 " << status_code << " " << status_msg << "\r\n";
    resp << "Content-Type: " << content_type << "\r\n";
    resp << "Content-Length: " << body.length() << "\r\n";
    resp << "Connection: close\r\n";
    resp << "\r\n";
    resp << body;
    std::string resp_str = resp.str();
    send(client_fd, resp_str.c_str(), resp_str.length(), 0);
}

// HTTP response for XML
void send_http_response_xml(int client_fd, const std::string& body) {
    send_http_response(client_fd, 200, "OK", "application/xml; charset=utf-8", body);
}

// HTTP response for JSON
void send_http_response_json(int client_fd, const std::string& body) {
    send_http_response(client_fd, 200, "OK", "application/json; charset=utf-8", body);
}

// HTTP 404
void send_404(int client_fd) {
    send_http_response(client_fd, 404, "Not Found", "text/plain", "404 Not Found");
}

// HTTP 405
void send_405(int client_fd) {
    send_http_response(client_fd, 405, "Method Not Allowed", "text/plain", "405 Method Not Allowed");
}

// HTTP 400
void send_400(int client_fd, const std::string& msg) {
    send_http_response(client_fd, 400, "Bad Request", "text/plain", msg);
}

// Simulated XML data points
std::string get_device_data_xml(const std::unordered_map<std::string, std::string>& params) {
    // Simulate filtering/limits if specified
    std::string filter = params.count("filter") ? params.at("filter") : "";
    int limit = params.count("limit") ? std::stoi(params.at("limit")) : 1;
    int offset = params.count("offset") ? std::stoi(params.at("offset")) : 0;
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        << "<DeviceData>"
        << "<DeviceName>test1</DeviceName>"
        << "<DeviceModel>dsa</DeviceModel>"
        << "<Manufacturer>adsads</Manufacturer>"
        << "<Type>ads</Type>"
        << "<DataPoints>";
    for (int i = 0; i < limit; ++i) {
        xml << "<DataPoint>"
            << "<Name>adsads</Name>"
            << "<Value>123" << offset + i << "</Value>";
        if (!filter.empty()) xml << "<Filter>" << filter << "</Filter>";
        xml << "</DataPoint>";
    }
    xml << "</DataPoints>"
        << "</DeviceData>";
    return xml.str();
}

// Simulate command execution
std::string post_device_command(const std::string& cmd_payload) {
    // Here, just echo back status success.
    std::ostringstream json;
    json << "{"
        << "\"status\": \"accepted\","
        << "\"executed\": true,"
        << "\"payload_echo\": " << cmd_payload
        << "}";
    return json.str();
}

// Handle a single HTTP request in a thread
void handle_client(int client_fd) {
    std::string request;
    if (!read_http_request(client_fd, request)) {
        close(client_fd);
        return;
    }

    // Split request into lines
    std::vector<std::string> lines;
    std::istringstream iss(request);
    std::string line;
    while (std::getline(iss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        lines.push_back(line);
        if (line.empty()) break; // End of headers
    }
    if (lines.empty()) {
        send_400(client_fd, "Malformed request");
        close(client_fd);
        return;
    }

    // Parse request line
    std::istringstream rl(lines[0]);
    std::string method, path, version;
    rl >> method >> path >> version;
    std::string url_path = path, query_str = "";
    auto qpos = path.find('?');
    if (qpos != std::string::npos) {
        url_path = path.substr(0, qpos);
        query_str = path.substr(qpos + 1);
    }
    std::unordered_map<std::string, std::string> query_params = parse_query(query_str);

    auto headers = parse_headers(lines);

    // Handle /data (GET)
    if (method == "GET" && url_path == "/data") {
        std::string body = get_device_data_xml(query_params);
        send_http_response_xml(client_fd, body);
        close(client_fd);
        return;
    }
    // Handle /commands (POST)
    else if (method == "POST" && url_path == "/commands") {
        // Read the POST body
        std::string body;
        auto cl_it = headers.find("Content-Length");
        int content_length = 0;
        if (cl_it != headers.end()) {
            content_length = std::stoi(cl_it->second);
        }
        if (content_length > 0) {
            if ((size_t)request.length() > request.find("\r\n\r\n") + 4)
                body = request.substr(request.find("\r\n\r\n") + 4);
            while ((int)body.length() < content_length) {
                char buf[BUFFER_SIZE];
                int r = recv(client_fd, buf, std::min(BUFFER_SIZE, content_length - (int)body.length()), 0);
                if (r <= 0) break;
                body.append(buf, r);
            }
        }
        // Must have a body (command)
        if (body.empty()) {
            send_400(client_fd, "Empty command payload");
            close(client_fd);
            return;
        }
        std::string resp = post_device_command(body);
        send_http_response_json(client_fd, resp);
        close(client_fd);
        return;
    }
    // Unsupported method/path
    else if ((url_path == "/data" && method != "GET") ||
             (url_path == "/commands" && method != "POST")) {
        send_405(client_fd);
        close(client_fd);
        return;
    } else {
        send_404(client_fd);
        close(client_fd);
        return;
    }
}

// Main HTTP server loop
int main() {
    // Get config from environment
    const char* ENV_PORT = std::getenv("HTTP_PORT");
    const char* ENV_HOST = std::getenv("HTTP_HOST");
    std::string server_host = ENV_HOST ? ENV_HOST : "0.0.0.0";
    int server_port = ENV_PORT ? std::atoi(ENV_PORT) : 8080;

    // Create socket
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        std::cerr << "Failed to create socket\n";
        return 1;
    }

    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(server_port);

    if (bind(listen_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
        close(listen_fd);
        return 1;
    }

    if (listen(listen_fd, 10) < 0) {
        std::cerr << "Listen failed\n";
        close(listen_fd);
        return 1;
    }

    std::cout << "HTTP server running on " << server_host << ":" << server_port << std::endl;

    while (true) {
        sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(listen_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) continue;
        std::thread(handle_client, client_fd).detach();
    }
    close(listen_fd);
    return 0;
}