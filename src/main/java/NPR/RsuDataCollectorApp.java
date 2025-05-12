package org.myproject.applications;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.RoadSideUnitApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageReception;
import org.eclipse.mosaic.lib.enums.AdHocChannel;
import org.eclipse.mosaic.lib.geo.GeoPoint;
import org.eclipse.mosaic.lib.objects.addressing.DestinationAddressContainer;
import org.eclipse.mosaic.lib.objects.addressing.IpResolver;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aplicação RSU que recebe dados dos veículos, envia-os para o nó Fog Computing
 * e retransmite alertas de risco e informações de tráfego de volta aos veículos.
 */
public class RsuDataCollectorApp extends AbstractApplication<RoadSideUnitOperatingSystem> implements RoadSideUnitApplication {

    private static final long PROCESSING_INTERVAL = 1000; // Intervalo de processamento em milissegundos (1 segundo)
    private static final int BROADCAST_RADIUS = 500; // Raio de broadcast em metros
    private static final String APP_ID = "RsuDataCollector";
    private static final String FOG_SERVER_ID = "fog_server_1"; // ID do servidor Fog

    // Lista para armazenar as mensagens recebidas dos veículos
    private final List<VehicleDataSenderApp.VehicleDataMessage> receivedMessages = new ArrayList<>();
    
    // Mapa para guardar os últimos alertas de risco recebidos do Fog, por veículo
    private final Map<String, FogComputingApp.RiskSituation> vehicleRisks = new HashMap<>();
    
    // Informações de tráfego mais recentes recebidas do Fog
    private List<FogComputingApp.TrafficSegmentInfo> trafficInfo = new ArrayList<>();
    private long lastTrafficInfoTime = 0;

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Aplicação RsuDataCollector inicializada na RSU {}", getOs().getId());
        
        // Agenda o primeiro evento de processamento de dados
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL, this::processAndForwardVehicleData);
    }

    @Override
    public void onShutdown() {
        getLog().infoSimTime(this, "Aplicação RsuDataCollector encerrada na RSU {}", getOs().getId());
    }

    @Override
    public void onMessageReceived(V2xMessageReception messageReception) {
        V2xMessage receivedMessage = messageReception.getMessage();
        
        // Processa mensagens recebidas dos veículos
        if (receivedMessage instanceof VehicleDataSenderApp.VehicleDataMessage) {
            VehicleDataSenderApp.VehicleDataMessage vehicleDataMessage = (VehicleDataSenderApp.VehicleDataMessage) receivedMessage;
            
            // Adiciona a mensagem à lista de mensagens recebidas
            synchronized (receivedMessages) {
                receivedMessages.add(vehicleDataMessage);
            }
            
            getLog().infoSimTime(this, "RSU {} recebeu dados do veículo {}: posição=({}, {}), velocidade={} m/s, sentido={}°",
                    getOs().getId(), 
                    vehicleDataMessage.getVehicleId(), 
                    vehicleDataMessage.getPosition().getLatitude(),
                    vehicleDataMessage.getPosition().getLongitude(),
                    vehicleDataMessage.getSpeed(),
                    vehicleDataMessage.getHeading());
            
            // Se houver um alerta de risco para este veículo, envia imediatamente
            synchronized (vehicleRisks) {
                if (vehicleRisks.containsKey(vehicleDataMessage.getVehicleId())) {
                    sendRiskAlertToVehicle(vehicleDataMessage.getVehicleId(), vehicleRisks.get(vehicleDataMessage.getVehicleId()));
                }
            }
        }
        // Processa mensagens de alertas de risco recebidas do Fog
        else if (receivedMessage instanceof FogComputingApp.FogRiskAlertsMessage) {
            FogComputingApp.FogRiskAlertsMessage riskMessage = (FogComputingApp.FogRiskAlertsMessage) receivedMessage;
            processRiskAlerts(riskMessage);
        }
        // Processa mensagens de informações de tráfego recebidas do Fog
        else if (receivedMessage instanceof FogComputingApp.FogTrafficInfoMessage) {
            FogComputingApp.FogTrafficInfoMessage trafficMessage = (FogComputingApp.FogTrafficInfoMessage) receivedMessage;
            processTrafficInfo(trafficMessage);
        }
        // Processa mensagens encaminhadas por outros veículos (multi-hop)
        else if (receivedMessage instanceof VehicleMultiHopApp.ForwardedVehicleMessage) {
            VehicleMultiHopApp.ForwardedVehicleMessage forwardedMessage = (VehicleMultiHopApp.ForwardedVehicleMessage) receivedMessage;
            processForwardedMessage(forwardedMessage);
        }
    }

    /**
     * Processa os dados recebidos dos veículos e encaminha para o nó Fog.
     */
    private void processAndForwardVehicleData(@Nonnull final Event event) {
        List<VehicleDataSenderApp.VehicleDataMessage> messagesToProcess;
        
        // Obtém a lista de mensagens recebidas para processamento
        synchronized (receivedMessages) {
            if (receivedMessages.isEmpty()) {
                getLog().infoSimTime(this, "RSU {} não tem dados de veículos para encaminhar", getOs().getId());
                
                // Agenda o próximo evento de processamento
                getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL, this::processAndForwardVehicleData);
                return;
            }
            
            messagesToProcess = new ArrayList<>(receivedMessages);
            receivedMessages.clear();
        }
        
        // Registra o número de mensagens a processar
        getLog().infoSimTime(this, "RSU {} encaminhando dados de {} veículos para o Fog", 
                getOs().getId(), messagesToProcess.size());
        
        // Criar a mensagem agregada para enviar ao Fog
        RsuAggregatedDataMessage aggregatedMessage = new RsuAggregatedDataMessage(
                getOs().getId(),
                messagesToProcess
        );
        
        // Configurar o roteamento da mensagem para o servidor Fog via IP
        DestinationAddressContainer destination = new DestinationAddressContainer(
                IpResolver.getSingleton().nameToIpAddress(FOG_SERVER_ID),
                APP_ID
        );
        
        MessageRouting routing = MessageRouting.ipRouting(destination);
        
        // Transmitir a mensagem para o Fog
        getOs().getRouter().sendIpMessage(aggregatedMessage, routing);
        
        // Distribuir informações de tráfego caso tenhamos dados recentes
        if (!trafficInfo.isEmpty() && (getOs().getSimulationTime() - lastTrafficInfoTime < 10000)) {
            broadcastTrafficInfo();
        }
        
        // Agenda o próximo evento de processamento
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL, this::processAndForwardVehicleData);
    }
    
    /**
     * Processa as mensagens de alertas de risco recebidas do Fog.
     */
    private void processRiskAlerts(FogComputingApp.FogRiskAlertsMessage message) {
        getLog().infoSimTime(this, "RSU {} recebeu {} alertas de risco do servidor Fog {}",
                getOs().getId(), message.getRiskSituations().size(), message.getFogServerId());
        
        // Atualiza o mapa de riscos
        synchronized (vehicleRisks) {
            for (FogComputingApp.RiskSituation risk : message.getRiskSituations()) {
                // Armazena alertas por veículo primário
                vehicleRisks.put(risk.getPrimaryVehicleId(), risk);
                
                // Se houver um veículo secundário também envolvido, armazena o mesmo alerta
                if (risk.getSecondaryVehicleId() != null) {
                    vehicleRisks.put(risk.getSecondaryVehicleId(), risk);
                }
            }
        }
        
        // Envia imediatamente os alertas via broadcast para todos os veículos próximos
        broadcastRiskAlerts(message.getRiskSituations());
    }
    
    /**
     * Processa as mensagens de informações de tráfego recebidas do Fog.
     */
    private void processTrafficInfo(FogComputingApp.FogTrafficInfoMessage message) {
        getLog().infoSimTime(this, "RSU {} recebeu informações de tráfego do servidor Fog {} para {} segmentos",
                getOs().getId(), message.getFogServerId(), message.getSegmentInfos().size());
        
        // Atualiza as informações de tráfego
        trafficInfo = new ArrayList<>(message.getSegmentInfos());
        lastTrafficInfoTime = getOs().getSimulationTime();
        
        // Envia as informações de tráfego para os veículos próximos
        broadcastTrafficInfo();
    }
    
    /**
     * Processa mensagens encaminhadas por outros veículos (multi-hop).
     */
    private void processForwardedMessage(VehicleMultiHopApp.ForwardedVehicleMessage message) {
        getLog().infoSimTime(this, "RSU {} recebeu mensagem encaminhada do veículo {}, originalmente de {}",
                getOs().getId(), message.getForwarderVehicleId(), message.getOriginalMessage().getVehicleId());
        
        // Adiciona a mensagem original à lista de mensagens recebidas
        synchronized (receivedMessages) {
            receivedMessages.add(message.getOriginalMessage());
        }
    }
    
    /**
     * Envia um alerta de risco diretamente para um veículo específico.
     */
    private void sendRiskAlertToVehicle(String vehicleId, FogComputingApp.RiskSituation risk) {
        // Configurar o roteamento para o veículo via IP (se disponível)
        try {
            DestinationAddressContainer destination = new DestinationAddressContainer(
                    IpResolver.getSingleton().nameToIpAddress(vehicleId),
                    APP_ID
            );
            
            MessageRouting routing = MessageRouting.ipRouting(destination);
            
            // Cria uma mensagem de alerta para o veículo
            RsuRiskAlertMessage alertMessage = new RsuRiskAlertMessage(
                    getOs().getId(),
                    getOs().getSimulationTime(),
                    risk
            );
            
            // Transmitir a mensagem para o veículo
            getOs().getRouter().sendIpMessage(alertMessage, routing);
            
            getLog().infoSimTime(this, "RSU {} enviou alerta de risco diretamente para o veículo {}",
                    getOs().getId(), vehicleId);
        } catch (Exception e) {
            getLog().error("Erro ao enviar alerta para o veículo {}: {}", vehicleId, e.getMessage());
        }
    }
    
    /**
     * Envia alertas de risco para todos os veículos na área via broadcast.
     */
    private void broadcastRiskAlerts(List<FogComputingApp.RiskSituation> risks) {
        if (risks.isEmpty()) {
            return;
        }
        
        // Cria uma mensagem de alertas para broadcast
        RsuRiskAlertsMessage alertsMessage = new RsuRiskAlertsMessage(
                getOs().getId(),
                getOs().getSimulationTime(),
                new ArrayList<>(risks)
        );
        
        // Configurar o roteamento para broadcast na área
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
                AdHocChannel.CCH,
                APP_ID,
                BROADCAST_RADIUS,
                getOs().getPosition()
        );
        
        // Transmitir a mensagem
        getOs().getAdHocModule().sendV2xMessage(alertsMessage, routing);
        
        getLog().infoSimTime(this, "RSU {} enviou {} alertas de risco via broadcast para veículos na área",
                getOs().getId(), risks.size());
    }
    
    /**
     * Envia informações de tráfego para todos os veículos na área via broadcast.
     */
    private void broadcastTrafficInfo() {
        if (trafficInfo.isEmpty()) {
            return;
        }
        
        // Cria uma mensagem de informações de tráfego para broadcast
        RsuTrafficInfoMessage trafficMessage = new RsuTrafficInfoMessage(
                getOs().getId(),
                getOs().getSimulationTime(),
                new ArrayList<>(trafficInfo)
        );
        
        // Configurar o roteamento para broadcast na área
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
                AdHocChannel.CCH,
                APP_ID,
                BROADCAST_RADIUS,
                getOs().getPosition()
        );
        
        // Transmitir a mensagem
        getOs().getAdHocModule().sendV2xMessage(trafficMessage, routing);
        
        getLog().infoSimTime(this, "RSU {} enviou informações de tráfego via broadcast para veículos na área",
                getOs().getId());
    }

    /**
     * Classe interna que representa a mensagem agregada enviada pela RSU ao Fog.
     */
    public static class RsuAggregatedDataMessage extends V2xMessage {
        private final String rsuId;
        private final List<VehicleDataSenderApp.VehicleDataMessage> vehicleMessages;

        public RsuAggregatedDataMessage(String rsuId, List<VehicleDataSenderApp.VehicleDataMessage> vehicleMessages) {
            this.rsuId = rsuId;
            this.vehicleMessages = vehicleMessages;
        }

        public String getRsuId() {
            return rsuId;
        }

        public List<VehicleDataSenderApp.VehicleDataMessage> getVehicleMessages() {
            return vehicleMessages;
        }

        @Override
        public String toString() {
            return "RsuAggregatedDataMessage{" +
                    "rsuId='" + rsuId + '\'' +
                    ", vehicleMessagesCount=" + vehicleMessages.size() +
                    '}';
        }
    }
    
    /**
     * Classe interna que representa a mensagem de alerta de risco para um veículo específico.
     */
    public static class RsuRiskAlertMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final FogComputingApp.RiskSituation riskSituation;

        public RsuRiskAlertMessage(String rsuId, long timestamp, FogComputingApp.RiskSituation riskSituation) {
            this.rsuId = rsuId;
            this.timestamp = timestamp;
            this.riskSituation = riskSituation;
        }

        public String getRsuId() {
            return rsuId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public FogComputingApp.RiskSituation getRiskSituation() {
            return riskSituation;
        }
    }
    
    /**
     * Classe interna que representa a mensagem de alertas de risco enviada via broadcast.
     */
    public static class RsuRiskAlertsMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final List<FogComputingApp.RiskSituation> riskSituations;

        public RsuRiskAlertsMessage(String rsuId, long timestamp, List<FogComputingApp.RiskSituation> riskSituations) {
            this.rsuId = rsuId;
            this.timestamp = timestamp;
            this.riskSituations = riskSituations;
        }

        public String getRsuId() {
            return rsuId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public List<FogComputingApp.RiskSituation> getRiskSituations() {
            return riskSituations;
        }
    }
    
    /**
     * Classe interna que representa a mensagem de informações de tráfego enviada via broadcast.
     */
    public static class RsuTrafficInfoMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final List<FogComputingApp.TrafficSegmentInfo> segmentInfos;

        public RsuTrafficInfoMessage(String rsuId, long timestamp, List<FogComputingApp.TrafficSegmentInfo> segmentInfos) {
            this.rsuId = rsuId;
            this.timestamp = timestamp;
            this.segmentInfos = segmentInfos;
        }

        public String getRsuId() {
            return rsuId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public List<FogComputingApp.TrafficSegmentInfo> getSegmentInfos() {
            return segmentInfos;
        }
    }
}
