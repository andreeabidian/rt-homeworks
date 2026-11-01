// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.

#include "config.h"
#include "connection.h"

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
float read_temperature(void) {
    return temperatureRead();
}

// ==========================================
// VARIABILE GLOBALE ȘI STRUCTURI
// ==========================================

volatile uint32_t current_period_ms = PERIOD_MS_INITIAL;
volatile float current_threshold = THRESHOLD_INITIAL;

struct AlarmEvent {
  float temperature;
  float threshold;
};

#define COMMAND_MAX_LEN 50
struct CommandEvent {
  char text[COMMAND_MAX_LEN];
};

QueueHandle_t alarmQueue;
QueueHandle_t commandQueue;

void periodicTelemetryTask(void *pvParameters);
void sporadicAlarmTask(void *pvParameters);
void aperiodicCommandTask(void *pvParameters);

// ==========================================
// TODO 3 (Partea 1) - Receptarea comenzilor din MQTT
// ==========================================
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    
    // Pune comanda în coadă cu timeout 0 (non-blocant)
    CommandEvent cmdEvent;
    snprintf(cmdEvent.text, COMMAND_MAX_LEN, "%s", text);
    xQueueSend(commandQueue, &cmdEvent, 0); 
}

// ==========================================
// TODO 0 - SETUP
// ==========================================
void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0); 
    
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    // a) Crearea cozilor
    alarmQueue = xQueueCreate(10, sizeof(AlarmEvent));
    commandQueue = xQueueCreate(5, sizeof(CommandEvent));

    if (alarmQueue == NULL || commandQueue == NULL) {
        Serial.println("Eroare la crearea cozilor!");
        while(1); 
    }

    wifi_connect();
    time_sync();

    // Pornirea MQTT după crearea cozilor
    mqtt_start();

    // b) Crearea Taskurilor
    xTaskCreate(aperiodicCommandTask, "AperiodicTask", 4096, NULL, 5, NULL);
    xTaskCreate(periodicTelemetryTask, "PeriodicTask", 4096, NULL, 4, NULL);
    xTaskCreate(sporadicAlarmTask,     "SporadicTask", 4096, NULL, 3, NULL);
}

void loop() {
    delay(1000);
}

// ==========================================
// TODO 1 - TASK PERIODIC (Telemetrie)
// ==========================================
void periodicTelemetryTask(void *pvParameters) {
    TickType_t xLastWakeTime = xTaskGetTickCount();

    for(;;) {
        float temp = read_temperature();

        // Publicare telemetrie
        char payload[100];
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f", DEVICE_ID, (long long)epoch_ms(), temp);
        mqtt_publish("telemetry", payload);

        // Verificare prag
        if (temp > current_threshold) {
            AlarmEvent alarm;
            alarm.temperature = temp;
            alarm.threshold = current_threshold;
            
            xQueueSend(alarmQueue, &alarm, 0);
        }

        // Așteptare absolută
        vTaskDelayUntil(&xLastWakeTime, pdMS_TO_TICKS(current_period_ms));
    }
}

// ==========================================
// TODO 2 - TASK SPORADIC (Alarme)
// ==========================================
void sporadicAlarmTask(void *pvParameters) {
    uint32_t lastAlarmTime = 0;

    for(;;) {
        AlarmEvent alarm;
        
        // Așteptare blocantă pentru o alarmă
        if (xQueueReceive(alarmQueue, &alarm, portMAX_DELAY) == pdTRUE) {
            
            uint32_t currentTime = millis();

            // Filtrare alarme prea dese
            if (currentTime - lastAlarmTime < MIN_INTERARRIVAL_MS) {
                Serial.println("[sporadic] Alarmă respinsă (a venit prea repede).");
            } else {
                char payload[100];
                snprintf(payload, sizeof(payload), "%s;%lld;%.2f;%.2f", 
                         DEVICE_ID, (long long)epoch_ms(), alarm.temperature, alarm.threshold);
                
                mqtt_publish("alarm", payload);
                lastAlarmTime = currentTime;
                
                Serial.println("[sporadic] Alarmă publicată cu succes.");
            }
        }
    }
}

// ==========================================
// TODO 3 (Partea 2) - TASK APERIODIC (Comenzi)
// ==========================================
void aperiodicCommandTask(void *pvParameters) {
    for(;;) {
        CommandEvent cmd;
        
        // Așteptare blocantă pentru o comandă
        if (xQueueReceive(commandQueue, &cmd, portMAX_DELAY) == pdTRUE) {
            
            char action[30];
            float param = 0;
            int parsedArgs = sscanf(cmd.text, "%s %f", action, &param);
            
            char statusPayload[100];
            
            // Procesare comenzi
            if (strcmp(action, "SET_INTERVAL") == 0 && parsedArgs == 2) {
                current_period_ms = (uint32_t)param;
                snprintf(statusPayload, sizeof(statusPayload), "%s;%lld;OK SET_INTERVAL %d", 
                         DEVICE_ID, (long long)epoch_ms(), current_period_ms);
                         
            } else if (strcmp(action, "SET_THRESHOLD") == 0 && parsedArgs == 2) {
                current_threshold = param;
                snprintf(statusPayload, sizeof(statusPayload), "%s;%lld;OK SET_THRESHOLD %.2f", 
                         DEVICE_ID, (long long)epoch_ms(), current_threshold);
                         
            } else if (strcmp(action, "RESET") == 0) {
                current_period_ms = PERIOD_MS_INITIAL;
                current_threshold = THRESHOLD_INITIAL;
                snprintf(statusPayload, sizeof(statusPayload), "%s;%lld;OK RESET", 
                         DEVICE_ID, (long long)epoch_ms());
                         
            } else if (strcmp(action, "STATUS") == 0) {
                snprintf(statusPayload, sizeof(statusPayload), "%s;%lld;STATUS interval=%d, threshold=%.2f", 
                         DEVICE_ID, (long long)epoch_ms(), current_period_ms, current_threshold);
                         
            } else {
                snprintf(statusPayload, sizeof(statusPayload), "%s;%lld;ERROR Comanda invalida", 
                         DEVICE_ID, (long long)epoch_ms());
            }
            
            mqtt_publish("status", statusPayload);
            Serial.printf("[aperiodic] Comandă executată: %s\n", cmd.text);
        }
    }
}