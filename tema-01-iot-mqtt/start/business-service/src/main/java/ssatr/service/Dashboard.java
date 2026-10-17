package ssatr.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul este sincronizat prin ConcurrentHashMap.
 */
public class Dashboard {

    public static class DeviceStatus {
        public double lastTemperature = 0.0;
        public long lastSeenTimestamp = 0;
        public int alarmCount = 0;
    }

    private final Map<String, DeviceStatus> devices = new ConcurrentHashMap<>();

    /**
     * Actualizează datele de telemetrie pentru un device.
     */
    public void updateTelemetry(String deviceId, double temperature, long timestamp) {
        DeviceStatus status = devices.computeIfAbsent(deviceId, k -> new DeviceStatus());
        synchronized (status) {
            status.lastTemperature = temperature;
            status.lastSeenTimestamp = timestamp;
        }
    }

    /**
     * Înregistrează apariția unei alarme pentru un device.
     */
    public void recordAlarm(String deviceId, long timestamp) {
        DeviceStatus status = devices.computeIfAbsent(deviceId, k -> new DeviceStatus());
        synchronized (status) {
            status.alarmCount++;
            status.lastSeenTimestamp = timestamp;
        }
    }

    /**
     * Actualizează timestamp-ul ultimului mesaj primit (de ex. status).
     */
    public void updateLastSeen(String deviceId, long timestamp) {
        DeviceStatus status = devices.computeIfAbsent(deviceId, k -> new DeviceStatus());
        synchronized (status) {
            status.lastSeenTimestamp = timestamp;
        }
    }

    /**
     * Afișează un tabel formatat cu starea tuturor device-urilor.
     */
    public void print() {
        if (devices.isEmpty()) {
            System.out.println("Niciun dispozitiv inregistrat inca.");
            return;
        }

        System.out.println("---------------------------------------------------------------");
        System.out.printf("%-15s %-10s %-20s %-10s%n", "DEVICE", "TEMP (°C)", "ULTIMUL MESAJ", "ALARME");
        System.out.println("---------------------------------------------------------------");

        long now = System.currentTimeMillis();
        for (Map.Entry<String, DeviceStatus> entry : devices.entrySet()) {
            String deviceId = entry.getKey();
            DeviceStatus status = entry.getValue();

            synchronized (status) {
                long diffSec = (now - status.lastSeenTimestamp) / 1000;
                String ago = diffSec <= 0 ? "chiar acum" : "acum " + diffSec + " s";
                System.out.printf("%-15s %-10.2f %-20s %-10d%n",
                        deviceId, status.lastTemperature, ago, status.alarmCount);
            }
        }
        System.out.println("---------------------------------------------------------------");
    }
}