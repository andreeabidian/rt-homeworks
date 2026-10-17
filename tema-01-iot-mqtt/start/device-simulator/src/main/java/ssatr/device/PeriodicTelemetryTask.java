package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        // Punctul inițial r0 pe grila de timp în nanosecunde monotone
        long nextReleaseNanos = System.nanoTime();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Citim temperatura de la senzor
                double temp = sensor.read();
                long timestamp = System.currentTimeMillis();
                String formattedTemp = String.format(Locale.US, "%.2f", temp);

                // 2. Publicăm pe topic-ul de telemetrie: deviceId;timestamp;temperatura
                String message = deviceId + ";" + timestamp + ";" + formattedTemp;
                mqtt.publish(Config.telemetryTopic(deviceId), message);

                // 3. Dacă temperatura depășește pragul, semnalăm în coada de alarme
                if (temp > threshold) {
                    alarmEvents.offer(temp);
                }

                // 4. Calculăm eliberarea următorului job pe grilă (r_k = r_{k-1} + T)
                long currentPeriodNanos = periodMs * 1_000_000L;
                nextReleaseNanos += currentPeriodNanos;

                // 5. Așteptare absolută: așteptăm doar cât a mai rămas până la grilă
                long sleepNanos = nextReleaseNanos - System.nanoTime();
                if (sleepNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } else {
                    // Dacă jobul a depășit grila (întârziere mare), realiniem startul
                    nextReleaseNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Eroare în PeriodicTelemetryTask: " + e.getMessage());
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}