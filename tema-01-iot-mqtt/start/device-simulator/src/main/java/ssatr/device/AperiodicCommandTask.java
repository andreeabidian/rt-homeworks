package ssatr.device;

import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>        schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                    readuce senzorul la valoarea inițială
 *   STATUS                   raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluam comanda din coada
                String rawCommand = commands.take();
                if (rawCommand == null || rawCommand.trim().isEmpty()) {
                    continue;
                }

                String trimmed = rawCommand.trim();
                String[] parts = trimmed.split("\\s+");
                String action = parts[0].toUpperCase();
                String responseText;

                // 2. Procesam comanda
                try {
                    switch (action) {
                        case "SET_INTERVAL":
                            if (parts.length < 2) {
                                responseText = "ERROR Lipseste parametrul pentru SET_INTERVAL";
                            } else {
                                long interval = Long.parseLong(parts[1]);
                                if (interval <= 0) {
                                    responseText = "ERROR Intervalul trebuie sa fie pozitiv";
                                } else {
                                    periodic.setPeriodMs(interval);
                                    responseText = "OK SET_INTERVAL " + interval;
                                }
                            }
                            break;

                        case "SET_THRESHOLD":
                            if (parts.length < 2) {
                                responseText = "ERROR Lipseste parametrul pentru SET_THRESHOLD";
                            } else {
                                double threshold = Double.parseDouble(parts[1]);
                                periodic.setThreshold(threshold);
                                responseText = "OK SET_THRESHOLD " + threshold;
                            }
                            break;

                        case "RESET":
                            sensor.reset();
                            responseText = "OK RESET";
                            break;

                        case "STATUS":
                            responseText = "OK STATUS period=" + periodic.getPeriodMs()
                                    + " threshold=" + periodic.getThreshold();
                            break;

                        default:
                            responseText = "ERROR Comanda necunoscuta: " + trimmed;
                            break;
                    }
                } catch (NumberFormatException e) {
                    responseText = "ERROR Parametru numeric invalid in comanda: " + trimmed;
                }

                // 3. Publicam confirmarea pe topic-ul de status
                long timestamp = System.currentTimeMillis();
                String statusMessage = deviceId + ";" + timestamp + ";" + responseText;
                mqtt.publish(Config.statusTopic(deviceId), statusMessage);
                System.out.printf("[%s] [APERIODIC] Executat: %s -> Raspuns: %s%n",
                        deviceId, trimmed, responseText);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Eroare in AperiodicCommandTask: " + e.getMessage());
            }
        }
    }
}