#include "config.h"
#include "connection.h"

// Fazele semaforului și numele lor pentru mesajele "lights"
enum Phase { CAR_GREEN, CAR_YELLOW, ALL_RED, PED_GREEN };
const char* const PHASE_NAMES[] = {"CAR_GREEN", "CAR_YELLOW", "ALL_RED", "PED_GREEN"};

// Culorile lămpilor
enum Light { RED, YELLOW, GREEN };
const char* const LIGHT_NAMES[] = {"RED", "YELLOW", "GREEN"};

// ============================================================================
// TODO 0 (c): Starea semaforului (protejată cu mutex) și coada butonului
// ============================================================================
struct SemaphoreState {
    Phase phase;
    uint32_t phase_entered_ms;
    bool ped_request;
};

SemaphoreState state = {CAR_GREEN, 0, false};
SemaphoreHandle_t state_mutex = nullptr;

struct ButtonEvent {
    TickType_t timestamp_ticks;
    bool from_isr;
};

QueueHandle_t button_queue = nullptr;

// ============================================================================
// TODO 1: Task periodic de control (T = 100 ms) - automatul cu stări
// ============================================================================
void controlTask(void* param) {
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        uint32_t now = millis();
        bool changed = false;
        Phase new_phase = CAR_GREEN;

        xSemaphoreTake(state_mutex, portMAX_DELAY);
        uint32_t elapsed = now - state.phase_entered_ms;

        switch (state.phase) {
            case CAR_GREEN:
                if (elapsed >= CAR_GREEN_MIN_MS && state.ped_request) {
                    state.phase = CAR_YELLOW;
                    state.phase_entered_ms = now;
                    changed = true;
                }
                break;

            case CAR_YELLOW:
                if (elapsed >= CAR_YELLOW_MS) {
                    state.phase = ALL_RED;
                    state.phase_entered_ms = now;
                    changed = true;
                }
                break;

            case ALL_RED:
                if (elapsed >= ALL_RED_MS) {
                    state.phase = PED_GREEN;
                    state.phase_entered_ms = now;
                    state.ped_request = false;  // se șterge cererea la intrarea în PED_GREEN
                    changed = true;
                }
                break;

            case PED_GREEN:
                if (elapsed >= PED_GREEN_MS) {
                    state.phase = CAR_GREEN;
                    state.phase_entered_ms = now;
                    changed = true;
                }
                break;
        }

        new_phase = state.phase;
        xSemaphoreGive(state_mutex);

        if (changed) {
            Serial.printf("[control] Faza noua: %s\n", PHASE_NAMES[new_phase]);
        }

        xTaskDelayUntil(&last_wake, pdMS_TO_TICKS(CONTROL_PERIOD_MS));
    }
}

// ============================================================================
// TODO 2: Task periodic de ieșire (T = 500 ms) - lămpi, MQTT și LED placă
// ============================================================================
void outputTask(void* param) {
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        // Copiem starea local, ținând mutexul cât mai puțin
        SemaphoreState snapshot;
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        snapshot = state;
        xSemaphoreGive(state_mutex);

        Light car_light = GREEN;
        Light ped_light = RED;

        switch (snapshot.phase) {
            case CAR_GREEN:
                car_light = GREEN;
                ped_light = RED;
                break;
            case CAR_YELLOW:
                car_light = YELLOW;
                ped_light = RED;
                break;
            case ALL_RED:
                car_light = RED;
                ped_light = RED;
                break;
            case PED_GREEN:
                car_light = RED;
                ped_light = GREEN;
                break;
        }

        // Numărătoarea inversă în PED_GREEN (rotunjită în sus), 0 în rest
        uint32_t countdown = 0;
        if (snapshot.phase == PED_GREEN) {
            uint32_t elapsed = millis() - snapshot.phase_entered_ms;
            if (elapsed < PED_GREEN_MS) {
                uint32_t remaining_ms = PED_GREEN_MS - elapsed;
                countdown = (remaining_ms + 999) / 1000;
            }
        }

        // Publicare pe topicul "lights"
        char payload[128];
        snprintf(payload, sizeof(payload), "%s;%lld;%s;%s;%s;%d;%u",
                 DEVICE_ID,
                 (long long)epoch_ms(),
                 PHASE_NAMES[snapshot.phase],
                 LIGHT_NAMES[car_light],
                 LIGHT_NAMES[ped_light],
                 snapshot.ped_request ? 1 : 0,
                 (unsigned)countdown);

        mqtt_publish("lights", payload);

        // LED-ul albastru de pe placă (aprins pe LOW) indică lampa AȘTEPTAȚI
        digitalWrite(LED_PIN, snapshot.ped_request ? LOW : HIGH);

        xTaskDelayUntil(&last_wake, pdMS_TO_TICKS(OUTPUT_PERIOD_MS));
    }
}

// ============================================================================
// TODO 3: Task sporadic + ISR buton BOOT + comandă MQTT "BUTTON"
// ============================================================================
void sporadicButtonTask(void* param) {
    ButtonEvent ev;
    bool has_accepted = false;
    TickType_t last_accepted = 0;

    for (;;) {
        if (xQueueReceive(button_queue, &ev, portMAX_DELAY) == pdTRUE) {
            if (has_accepted && (ev.timestamp_ticks - last_accepted) < pdMS_TO_TICKS(MIN_INTERARRIVAL_MS)) {
                Serial.printf("[sporadic] Apasare respinsa (%s - prea repede)\n",
                              ev.from_isr ? "BOOT" : "MQTT");
                continue;
            }

            has_accepted = true;
            last_accepted = ev.timestamp_ticks;

            xSemaphoreTake(state_mutex, portMAX_DELAY);
            state.ped_request = true;
            xSemaphoreGive(state_mutex);

            Serial.printf("[sporadic] Cerere pietoni inregistrata (%s)\n",
                          ev.from_isr ? "BOOT" : "MQTT");
        }
    }
}

void IRAM_ATTR button_isr(void) {
    ButtonEvent ev;
    ev.timestamp_ticks = xTaskGetTickCountFromISR();
    ev.from_isr = true;
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(button_queue, &ev, &woken);
    portYIELD_FROM_ISR(woken);
}

void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (strcmp(text, "BUTTON") == 0 && button_queue != nullptr) {
        ButtonEvent ev;
        ev.timestamp_ticks = xTaskGetTickCount();
        ev.from_isr = false;
        xQueueSend(button_queue, &ev, 0);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 03: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // TODO 0 (a): Creăm mutexul și coada înainte de mqtt_start()
    state_mutex = xSemaphoreCreateMutex();
    button_queue = xQueueCreate(16, sizeof(ButtonEvent));
    state.phase = CAR_GREEN;
    state.phase_entered_ms = millis();
    state.ped_request = false;

    mqtt_start();

    // TODO 0 (b): Pornim cele 3 taskuri (Rate Monotonic: T mai mic -> prioritate mai mare)
    // Control (T=100ms) -> prio 4, Output (T=500ms) -> prio 3, Sporadic (T_min=1000ms) -> prio 2
    xTaskCreate(controlTask,        "control",  4096, nullptr, 4, nullptr);
    xTaskCreate(outputTask,         "output",   4096, nullptr, 3, nullptr);
    xTaskCreate(sporadicButtonTask, "button",   4096, nullptr, 2, nullptr);

    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
}

void loop() {
    delay(1000);
}