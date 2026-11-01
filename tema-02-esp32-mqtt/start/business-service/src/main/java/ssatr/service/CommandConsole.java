package ssatr.service;

import java.util.Scanner;

/**
 * Consola interactivă pentru operator.
 *
 * Comenzi acceptate:
 *   show                   - afișează starea tuturor dispozitivelor
 *   <deviceId> <comandă>   - trimite comanda către device-ul specificat
 *                            (de ex: device-1 SET_INTERVAL 1000)
 *   exit                   - oprește consola / aplicația
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("CommandConsole pornita. Tastati 'show' sau '<deviceId> <comanda>'. 'exit' pentru iesire.");

        while (!Thread.currentThread().isInterrupted()) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("exit".equalsIgnoreCase(line)) {
                System.out.println("Oprire consola...");
                System.exit(0);
                break;
            }

            if ("show".equalsIgnoreCase(line)) {
                // Afișează tabelul cu datele tuturor device-urilor
                dashboard.print();
                continue;
            }

            // Comandă adresată unui device specific: <deviceId> <comanda>
            // Exemplu: device-1 SET_INTERVAL 1000
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                System.out.println("Format comanda invalid. Utilizare: show SAU <deviceId> <comanda> (ex: device-1 STATUS)");
                continue;
            }

            String targetDevice = parts[0];
            String commandPayload = parts[1];

            // Publicare comandă pe topic-ul de comenzi al device-ului
            String commandTopic = Config.commandTopic(targetDevice);
            try {
                mqtt.publish(commandTopic, commandPayload);
                System.out.printf("[COMMAND SENT] Catre '%s': %s%n", targetDevice, commandPayload);
            } catch (Exception e) {
                System.err.println("Eroare la trimiterea comenzii pe MQTT: " + e.getMessage());
            }
        }
    }
}