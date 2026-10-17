package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        // Retinem momentul ultimei alarme acceptate (initial 0 pentru a accepta prima alarma imediat)
        long lastReleaseTime = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Asteptam sosirea unui eveniment de alarma din coada
                Double temp = alarmEvents.take();
                long now = System.currentTimeMillis();

                // 2. Verificam respectarea intervalului minim intre sosiri (inter-arrival time)
                if (now - lastReleaseTime < MIN_INTERARRIVAL_MS) {
                    System.out.printf("[%s] [SPORADIC] Alarma respinsa (prea deasa, t=%.2f C, delta=%d ms < %d ms)%n",
                            deviceId, temp, (now - lastReleaseTime), MIN_INTERARRIVAL_MS);
                    continue;
                }

                // 3. Evenimentul este acceptat: actualizam momentul ultimei eliberari
                lastReleaseTime = now;
                String formattedTemp = String.format(Locale.US, "%.2f", temp);
                
                // Mesaj format: "deviceId;timestamp;temperatura;prag"
                // Folosim pragul standard 28.0 corespunzator configuratiei de baza
                String message = deviceId + ";" + now + ";" + formattedTemp + ";28.00";
                
                mqtt.publish(Config.alarmTopic(deviceId), message);
                System.out.printf("[%s] [SPORADIC] Alarma publicata: %s%n", deviceId, message);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Eroare in SporadicAlarmTask: " + e.getMessage());
            }
        }
    }
}