/****************************************************************************
 * apps/examples/bme680/bme680_main.c
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The
 * ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 ****************************************************************************/

/****************************************************************************
 * Included Files
 ****************************************************************************/

#include <nuttx/config.h>
#include <nuttx/sensors/sensor.h>
#include <nuttx/sensors/bme680.h>
#include <stdio.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <time.h>
#include <stdlib.h> // For exit() if needed, snprintf dependency
#include <string.h> // For strlen(), snprintf(), memset(), memcpy()

/* --- Network Includes --- */
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
/* ------------------------ */

/* --- Network Defines --- */
#define SERVER_IP "10.200.23.240"
#define SERVER_PORT 2242

#define REQUEST_BUF_SIZE 512
#define RESPONSE_BUF_SIZE 512
/* ----------------------- */

#define NB_LOWERHALFS 3

/* Structure used when polling the sub-sensors */
struct data
{
  void *data_struct;
  uint16_t data_size;
};

/****************************************************************************
 * Private Functions
 ****************************************************************************/

/* --- HTTP Client Helper Functions --- */

// Function to create and connect a socket
static int connect_to_server(const char *server_ip, int port)
{
  int sockfd;
  struct sockaddr_in server_addr;

  // Create socket
  sockfd = socket(AF_INET, SOCK_STREAM, 0);
  if (sockfd < 0)
  {
    perror("ERROR: Failed to create socket");
    return -1;
  }

  // Prepare the sockaddr_in structure
  memset(&server_addr, 0, sizeof(server_addr));
  server_addr.sin_family = AF_INET;
  server_addr.sin_port = htons(port);

  // Convert IPv4 address from text to binary form
  if (inet_pton(AF_INET, server_ip, &server_addr.sin_addr) <= 0)
  {
    perror("ERROR: Invalid address/ Address not supported");
    close(sockfd);
    return -1;
  }

  if (connect(sockfd, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0)
  {
    fprintf(stderr, "ERROR: Connection Failed to %s:%d (errno=%d)\n", server_ip, port, errno);
    close(sockfd);
    return -1;
  }

  printf("Connected to server %s:%d\n", server_ip, port);
  return sockfd;
}

// Function to send the health check GET request
static int send_health_check(const char *server_ip, int port)
{
  int sockfd;
  char request_buf[REQUEST_BUF_SIZE];
  char response_buf[RESPONSE_BUF_SIZE];
  int bytes_sent;
  int bytes_received;
  int ret = 0; // Success

  sockfd = connect_to_server(server_ip, port);
  if (sockfd < 0)
  {
    return -1; // Connection failed
  }

  // Construct the HTTP GET request
  snprintf(request_buf, REQUEST_BUF_SIZE,
           "GET /api/monitor/up HTTP/1.1\r\n"
           "Host: %s:%d\r\n"
           "Connection: close\r\n" // Tell server to close connection after response
           "\r\n",                 // End of headers
           server_ip, port);

  // Send the request
  bytes_sent = send(sockfd, request_buf, strlen(request_buf), 0);
  if (bytes_sent < 0)
  {
    fprintf(stderr, "ERROR: Failed to send health check request (errno=%d)\n", errno);
    ret = -1;
  }
  else
  {
    // Minimal response check (optional but recommended)
    memset(response_buf, 0, RESPONSE_BUF_SIZE);
    // Add timeout to recv? Set socket options SO_RCVTIMEO or use select/poll
    bytes_received = recv(sockfd, response_buf, RESPONSE_BUF_SIZE - 1, 0);
    if (bytes_received < 0)
    {
      fprintf(stderr, "WARNING: Failed to receive health check response (errno=%d)\n", errno);
    }
    else if (bytes_received > 0)
    {
      if (strstr(response_buf, "HTTP/1.1 200 OK") == NULL &&
          strstr(response_buf, "HTTP/1.0 200 OK") == NULL)
      {
        fprintf(stderr, "WARNING: Health check did not return HTTP 200 OK.\n");
      }
    }
    // If bytes_received == 0, server closed connection as expected for 'Connection: close'
  }

  close(sockfd);
  return ret;
}

// Function to send monitoring data via PUT request
static int send_monitoring_data(const char *server_ip, int port, float temperature, float humidity)
{
  int sockfd;
  char request_buf[REQUEST_BUF_SIZE];
  char json_body[100];                  // Buffer for JSON payload
  char response_buf[RESPONSE_BUF_SIZE]; // For reading response
  int content_length;
  int bytes_sent;
  int bytes_received;
  int header_len;
  int total_len;
  int ret = 0; // Success

  sockfd = connect_to_server(server_ip, port);
  if (sockfd < 0)
  {
    return -1; // Connection failed
  }

  // Create JSON body
  // Ensure temperature/humidity are valid numbers before sending
  snprintf(json_body, sizeof(json_body),
           "{\"temperature\": %.2f, \"humidity\": %.2f}",
           temperature, humidity);
  content_length = strlen(json_body);

  // Construct the HTTP PUT request headers
  header_len = snprintf(request_buf, REQUEST_BUF_SIZE,
                        "PUT /api/monitor/data HTTP/1.1\r\n"
                        "Host: %s:%d\r\n"
                        "Content-Type: application/json\r\n"
                        "Content-Length: %d\r\n"
                        "Connection: close\r\n"
                        "\r\n",
                        server_ip, port, content_length);

  if (header_len < 0 || header_len >= REQUEST_BUF_SIZE)
  {
    fprintf(stderr, "ERROR: Failed to format PUT request headers or buffer too small.\n");
    close(sockfd);
    return -1;
  }

  // Check if there's enough space for headers + body
  total_len = header_len + content_length;
  if (total_len >= REQUEST_BUF_SIZE)
  {
    fprintf(stderr, "ERROR: Request buffer too small for headers and JSON body (%d needed, %d available).\n",
            total_len, REQUEST_BUF_SIZE);
    close(sockfd);
    return -1;
  }

  // Append JSON body to the request buffer
  memcpy(request_buf + header_len, json_body, content_length);

  // Send the complete request (headers + body)
  bytes_sent = send(sockfd, request_buf, total_len, 0);
  if (bytes_sent < 0)
  {
    fprintf(stderr, "ERROR: Failed to send monitoring data request (errno=%d)\n", errno);
    ret = -1;
  }
  else if (bytes_sent < total_len)
  {
    fprintf(stderr, "WARNING: Incomplete monitoring data request sent (%d/%d bytes)\n",
            bytes_sent, total_len);
    ret = -1; // Treat incomplete send as error
  }
  else
  {
    // Read the response (optional but good practice)
    memset(response_buf, 0, RESPONSE_BUF_SIZE);
    // Add timeout to recv? Set socket options SO_RCVTIMEO or use select/poll
    bytes_received = recv(sockfd, response_buf, RESPONSE_BUF_SIZE - 1, 0);

    if (bytes_received < 0)
    {
      fprintf(stderr, "WARNING: Failed to receive monitoring data response (errno=%d)\n", errno);
    }
    else if (bytes_received > 0)
    {
      if (strstr(response_buf, "HTTP/1.1 200 OK") == NULL &&
          strstr(response_buf, "HTTP/1.0 200 OK") == NULL)
      {
        fprintf(stderr, "WARNING: Monitoring data PUT did not return HTTP 200 OK.\n");
      }
      else if (strstr(response_buf, "Monitoring data received successfully.") == NULL)
      {
        fprintf(stderr, "WARNING: Did not receive expected success message in PUT response body.\n");
      }
    }
  }

  close(sockfd);
  return ret;
}
/* ----------------------------------- */

/****************************************************************************
 * Public Functions
 ****************************************************************************/

/****************************************************************************
 * bme680_main
 ****************************************************************************/

int main(int argc, FAR char *argv[])
{
  int baro_fd;
  int hum_fd;
  uint16_t seconds;
  int ret;

  /* This example works when all of the sub-sensors of
   * the BME680 are enabled.
   */

  struct sensor_baro baro_data;
  struct sensor_humi humi_data;

  /* Open each lowerhalf file to be able to read the data.
   * When the pressure measurement is deactivated, sensor_temp0 should
   * be opened instead (to get the temperature measurement).
   */

  baro_fd = open("/dev/uorb/sensor_baro0", O_RDONLY | O_NONBLOCK);
  if (baro_fd < 0)
  {
    printf("Failed to open barometer lowerhalf.\n");
    return -1;
  }

  hum_fd = open("/dev/uorb/sensor_humi0", O_RDONLY | O_NONBLOCK);
  if (hum_fd < 0)
  {
    printf("Failed to open humidity sensor lowerhalf.\n");
    close(baro_fd); // Close already opened file
    return -1;
  }

  /* Configure the sensor */

  struct bme680_config_s config;

  /* Set oversampling */

  config.temp_os = BME680_OS_2X;
  config.press_os = BME680_OS_16X;
  config.filter_coef = BME680_FILTER_COEF3;
  config.hum_os = BME680_OS_1X;

  /* Set heater parameters */

  config.target_temp = 300;     /* degrees Celsius */
  config.amb_temp = 30;         /* degrees Celsius */
  config.heater_duration = 100; /* milliseconds */

  config.nb_conv = 0;

  // Note: Calibration might block, ensure it's handled appropriately
  printf("Calibrating sensor...\n");
  ret = ioctl(baro_fd, SNIOC_CALIBRATE, &config);
  if (ret < 0)
  {
    perror("Failed to calibrate sensor via ioctl");
    close(baro_fd);
    close(hum_fd);
    return ret;
  }
  printf("Sensor calibration command sent.\n");

  struct pollfd pfds[] = {
      {.fd = baro_fd, .events = POLLIN},
      {.fd = hum_fd, .events = POLLIN}};

  struct data sensor_data[] = {
      {.data_struct = &baro_data, .data_size = sizeof(struct sensor_baro)},
      {.data_struct = &humi_data, .data_size = sizeof(struct sensor_humi)}};

  // Reduced stabilization time from original example for faster testing
  // Increase this (e.g., back to 60 or more) for better accuracy.
  seconds = 15;

  /* Wait some time for the sensor to accomodate to the surroundings. */
  printf("Waiting for sensor stabilization (%u seconds)...\n", seconds);
  time_t start_time = time(NULL);
  int reads_during_stabilization = 0;

  // Clear initial potentially invalid data
  memset(&baro_data, 0, sizeof(baro_data));
  memset(&humi_data, 0, sizeof(humi_data));

  while (time(NULL) < start_time + seconds)
  {
    // Poll with a timeout to avoid blocking indefinitely
    ret = poll(pfds, 2, 1000); // Use 2 since we only opened baro and humi
    if (ret < 0)
    {
      perror("Could not poll sensor during stabilization.");
      // Maybe continue and try again?
    }
    else if (ret > 0)
    {
      // Read available data but ignore values during stabilization
      for (int i = 0; i < 2; i++) // Use 2
      {
        if (pfds[i].revents & POLLIN)
        {
          ret = read(pfds[i].fd, sensor_data[i].data_struct,
                     sensor_data[i].data_size);
          if (ret == sensor_data[i].data_size)
          {
            reads_during_stabilization++;
          }
          else if (ret < 0 && errno != EAGAIN)
          {
            fprintf(stderr, "Error reading sensor %d during stabilization: %d\n", i, errno);
          }
          pfds[i].revents = 0; // Reset revents
        }
      }
      printf("."); // Show progress
      fflush(stdout);
    }
    // Small delay to prevent busy-waiting
    usleep(200 * 1000); // 200ms
  }
  printf("\nSensor stabilization complete (Read %d times).\n", reads_during_stabilization);

  // Perform a final read attempt to get the measurement
  bool final_read_ok = false;
  for (int attempt = 0; attempt < 5; attempt++)
  {
    ret = poll(pfds, 2, 500); // 500ms timeout
    if (ret < 0)
    {
      perror("Final poll failed");
      break; // Exit attempt loop
    }
    if (ret > 0)
    {
      bool read_baro = false;
      bool read_humi = false;
      if (pfds[0].revents & POLLIN)
      {
        ret = read(pfds[0].fd, sensor_data[0].data_struct, sensor_data[0].data_size);
        if (ret == sensor_data[0].data_size)
          read_baro = true;
        else if (ret < 0 && errno != EAGAIN)
          fprintf(stderr, "Final read error baro: %d\n", errno);
      }
      if (pfds[1].revents & POLLIN)
      {
        ret = read(pfds[1].fd, sensor_data[1].data_struct, sensor_data[1].data_size);
        if (ret == sensor_data[1].data_size)
          read_humi = true;
        else if (ret < 0 && errno != EAGAIN)
          fprintf(stderr, "Final read error humi: %d\n", errno);
      }


      if (read_baro && read_humi)
      {
        final_read_ok = true;
        break; // Got both readings
      }
    }
    usleep(100 * 1000); // Wait 100ms before retrying final read
  }

  if (!final_read_ok)
  {
    fprintf(stderr, "ERROR: Failed to get final sensor readings after stabilization attempt.\n");
    // Attempt to close files even on failure
    close(baro_fd);
    close(hum_fd);
    return -1;
  }

  printf("\nFinal Sensor Readings:\n");
  printf("  Temperature [C] = %f\n", baro_data.temperature);
  printf("  Humidity [%%rH]  = %f\n", humi_data.humidity);

  close(baro_fd);
  close(hum_fd);
  printf("Sensor file descriptors closed.\n");

  /* --- Send Data to API Server --- */

  // 1. Send Health Check (Optional, but good practice)
  printf("\n--- Sending Health Check to %s:%d ---\n", SERVER_IP, SERVER_PORT);
  if (send_health_check(SERVER_IP, SERVER_PORT) == 0)
  {
    printf("Health check successful or server acknowledged.\n");
  }
  else
  {
    fprintf(stderr, "Health check failed. Check network/server.\n");
  }

  // 2. Send Monitoring Data
  printf("\n--- Sending Monitoring Data to %s:%d ---\n", SERVER_IP, SERVER_PORT);
  // Use the data read earlier into baro_data and humi_data
  if (send_monitoring_data(SERVER_IP, SERVER_PORT, baro_data.temperature, humi_data.humidity) == 0)
  {
    printf("Monitoring data sent successfully.\n");
  }
  else
  {
    fprintf(stderr, "Failed to send monitoring data.\n");
    // Consider returning an error code if sending data is critical
    // return -1;
  }

  /* ------------------------------- */

  printf("\nBME680 example finished.\n");
  return 0;
}