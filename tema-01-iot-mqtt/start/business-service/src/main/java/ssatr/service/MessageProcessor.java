package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluam mesajul din coada
                String message = inbox.take();
                if (message == null || message.trim().isEmpty()) {
                    continue;
                }

                // 2. Salvare in fisierul CSV corespunzator (telemetry.csv, alarm.csv, status.csv)
                try {
                    storage.append(kind, message);
                } catch (Exception e) {
                    System.err.println("Eroare la salvarea in CSV: " + e.getMessage());
                }

                // 3. Parsare mesaj: campuri separate prin ';'
                String[] parts = message.split(";");
                if (parts.length < 3) {
                    System.err.println("Mesaj invalid ignorat: " + message);
                    continue;
                }

                String deviceId = parts[0];
                long timestamp = Long.parseLong(parts[1]);

                // 4. Actualizare dashboard si afisare prompta a alarmelor/statusului
                switch (kind) {
                    case "telemetry":
                        double temp = Double.parseDouble(parts[2]);
                        dashboard.updateTelemetry(deviceId, temp, timestamp);
                        break;

                    case "alarm":
                        double alarmTemp = Double.parseDouble(parts[2]);
                        double threshold = parts.length > 3 ? Double.parseDouble(parts[3]) : 0.0;
                        dashboard.recordAlarm(deviceId, timestamp);
                        System.out.printf("%n[ATENTIE - ALARMA] %s: temp=%.2f C a depasit pragul=%.2f C%n> ",
                                deviceId, alarmTemp, threshold);
                        break;

                    case "status":
                        String statusText = parts[2];
                        dashboard.updateLastSeen(deviceId, timestamp);
                        System.out.printf("%n[STATUS CONFIRMATION] %s: %s%n> ", deviceId, statusText);
                        break;

                    default:
                        break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Eroare la procesarea mesajului: " + e.getMessage());
            }
        }
    }
}