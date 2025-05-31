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
import java.util.*;

public class RsuDataCollectorApp extends AbstractApplication<RoadSideUnitOperatingSystem> 
        implements RoadSideUnitApplication {

    private static final long PROCESSING_INTERVAL = 100; // ms (10Hz)
    private static final int BROADCAST_RADIUS = 500; // metros
    private static final String APP_ID = "RsuDataCollector";
    private static final String FOG_SERVER_ID = "fog_server_1";
    
    // Estruturas de dados
    private final List<VehicleApp.VehicleDataMessage> receivedMessages = new ArrayList<>();
    private final Map<String, VehicleApp.RiskSituation> activeRisks = new HashMap<>();
    private final Map<String, Double> trafficMetrics = new HashMap<>(); // segmentId -> metric

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Iniciando RSU {}", getOs().getId());
        
        // Configurar comunicação
        getOs().getAdHocModule().enable(
            new AdHocModuleConfiguration()
                .addRadio()
                .channel(AdHocChannel.CCH)
                .power(100)
                .create()
        );
        
        // Agendar primeiro processamento
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + PROCESSING_INTERVAL, 
            this::processAndForwardData
        );
    }

    @Override
    public void onMessageReceived(V2xMessageReception reception) {
        V2xMessage msg = reception.getMessage();
        
        if (msg instanceof VehicleApp.VehicleDataMessage) {
            synchronized (receivedMessages) {
                receivedMessages.add((VehicleApp.VehicleDataMessage) msg);
            }
        } 
        else if (msg instanceof VehicleApp.ForwardedVehicleMessage) {
            synchronized (receivedMessages) {
                receivedMessages.add(((VehicleApp.ForwardedVehicleMessage) msg).getOriginalMessage());
            }
        }
        else if (msg instanceof FogComputingApp.FogRiskAlertsMessage) {
            processRiskAlerts((FogComputingApp.FogRiskAlertsMessage) msg);
        }
        else if (msg instanceof FogComputingApp.FogTrafficInfoMessage) {
            processTrafficInfo((FogComputingApp.FogTrafficInfoMessage) msg);
        }
        else if (msg instanceof FogComputingApp.TrafficLightCommandMessage) {
            forwardTrafficLightCommand((FogComputingApp.TrafficLightCommandMessage) msg);
        }
    }

    private void processAndForwardData(@Nonnull final Event event) {
        // Coletar mensagens para processamento
        List<VehicleApp.VehicleDataMessage> messagesToProcess;
        synchronized (receivedMessages) {
            messagesToProcess = new ArrayList<>(receivedMessages);
            receivedMessages.clear();
        }
        
        if (!messagesToProcess.isEmpty()) {
            // Processar métricas de tráfego (simplificado)
            processTrafficMetrics(messagesToProcess);
            
            // Enviar dados agregados para o Fog
            sendToFog(messagesToProcess);
        }
        
        // Enviar alertas ativos para veículos
        broadcastActiveRisks();
        
        // Enviar métricas de tráfego para veículos
        broadcastTrafficInfo();
        
        // Reagendar processamento
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + PROCESSING_INTERVAL, 
            this::processAndForwardData
        );
    }

    private void processTrafficMetrics(List<VehicleApp.VehicleDataMessage> messages) {
        // Lógica simplificada para calcular métricas de tráfego
        // (Em implementação real, usaríamos segmentos de estrada)
        Map<String, Integer> vehicleCounts = new HashMap<>();
        Map<String, Double> avgSpeeds = new HashMap<>();
        
        for (VehicleApp.VehicleDataMessage msg : messages) {
            String segmentId = getSegmentId(msg.getPosition());
            
            vehicleCounts.put(segmentId, vehicleCounts.getOrDefault(segmentId, 0) + 1);
            avgSpeeds.put(segmentId, avgSpeeds.getOrDefault(segmentId, 0.0) + msg.getSpeed());
        }
        
        // Calcular métricas finais
        trafficMetrics.clear();
        for (String segmentId : vehicleCounts.keySet()) {
            int count = vehicleCounts.get(segmentId);
            double avgSpeed = avgSpeeds.get(segmentId) / count;
            trafficMetrics.put(segmentId, avgSpeed);
        }
    }
    
    private String getSegmentId(GeoPoint position) {
        // Simplificação: usar coordenadas arredondadas
        return String.format("seg_%.4f_%.4f", 
            Math.round(position.getLatitude() * 10000) / 10000.0,
            Math.round(position.getLongitude() * 10000) / 10000.0);
    }

    private void sendToFog(List<VehicleApp.VehicleDataMessage> messages) {
        DestinationAddressContainer destination = new DestinationAddressContainer(
            IpResolver.getSingleton().nameToIpAddress(FOG_SERVER_ID), 
            APP_ID
        );
        
        MessageRouting routing = MessageRouting.ipRouting(destination);
        RsuAggregatedDataMessage aggregate = new RsuAggregatedDataMessage(
            getOs().getId(),
            getOs().getPosition(),
            messages
        );
        
        getOs().getRouter().sendIpMessage(aggregate, routing);
    }

    private void processRiskAlerts(FogComputingApp.FogRiskAlertsMessage msg) {
        synchronized (activeRisks) {
            for (FogComputingApp.RiskSituation risk : msg.getRiskSituations()) {
                String riskId = risk.getPrimaryVehicleId() + "_" + risk.getTimestamp();
                activeRisks.put(riskId, convertRisk(risk));
            }
        }
    }
    
    private VehicleApp.RiskSituation convertRisk(FogComputingApp.RiskSituation fogRisk) {
        Set<String> affectedVehicles = new HashSet<>();
        affectedVehicles.add(fogRisk.getPrimaryVehicleId());
        if (fogRisk.getSecondaryVehicleId() != null) {
            affectedVehicles.add(fogRisk.getSecondaryVehicleId());
        }
        
        return new VehicleApp.RiskSituation(
            fogRisk.getPrimaryVehicleId() + "_" + fogRisk.getTimestamp(),
            VehicleApp.RiskType.valueOf(fogRisk.getType().name()),
            affectedVehicles,
            fogRisk.getLocation(),
            fogRisk.getDescription()
        );
    }

    private void processTrafficInfo(FogComputingApp.FogTrafficInfoMessage msg) {
        // Atualizar métricas de tráfego com dados do Fog
        for (FogComputingApp.TrafficSegmentInfo segment : msg.getSegmentInfos()) {
            trafficMetrics.put(segment.getSegmentId(), segment.getAvgSpeed());
        }
    }
    
    private void forwardTrafficLightCommand(FogComputingApp.TrafficLightCommandMessage command) {
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
            AdHocChannel.CCH,
            "TrafficLightApp",
            BROADCAST_RADIUS,
            getOs().getPosition()
        );
        
        FogComputingApp.TrafficLightCommandMessage broadcastMsg = 
            new FogComputingApp.TrafficLightCommandMessage(
                routing,
                command.getCommand(),
                command.getIntersectionId(),
                command.getDuration()
            );
        
        getOs().getAdHocModule().sendV2xMessage(broadcastMsg, routing);
        
        getLog().infoSimTime(this, "Encaminhado comando para semáforo: {}",
            command.getCommand());
    }

    private void broadcastActiveRisks() {
        if (activeRisks.isEmpty()) return;
        
        List<VehicleApp.RiskSituation> risks = new ArrayList<>(activeRisks.values());
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
            AdHocChannel.CCH,
            VehicleApp.APP_ID,
            BROADCAST_RADIUS,
            getOs().getPosition()
        );
        
        VehicleApp.RsuRiskAlertsMessage alertsMsg = new VehicleApp.RsuRiskAlertsMessage(
            routing,
            getOs().getId(),
            getOs().getPosition(),
            risks
        );
        
        getOs().getAdHocModule().sendV2xMessage(alertsMsg, routing);
    }

    private void broadcastTrafficInfo() {
        if (trafficMetrics.isEmpty()) return;
        
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
            AdHocChannel.CCH,
            VehicleApp.APP_ID,
            BROADCAST_RADIUS,
            getOs().getPosition()
        );
        
        VehicleApp.RsuTrafficInfoMessage infoMsg = new VehicleApp.RsuTrafficInfoMessage(
            routing,
            getOs().getId(),
            new HashMap<>(trafficMetrics)
        );
        
        getOs().getAdHocModule().sendV2xMessage(infoMsg, routing);
    }

    public static class RsuAggregatedDataMessage extends V2xMessage {
        private final String rsuId;
        private final GeoPoint rsuPosition;
        private final List<VehicleApp.VehicleDataMessage> vehicleMessages;
        
        public RsuAggregatedDataMessage(String rsuId, GeoPoint rsuPosition, 
                                       List<VehicleApp.VehicleDataMessage> vehicleMessages) {
            super(null);
            this.rsuId = rsuId;
            this.rsuPosition = rsuPosition;
            this.vehicleMessages = vehicleMessages;
        }
        
        // Getters
        public String getRsuId() { return rsuId; }
        public GeoPoint getRsuPosition() { return rsuPosition; }
        public List<VehicleApp.VehicleDataMessage> getVehicleMessages() { return vehicleMessages; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("RsuAggregate[%s,%d msgs]", rsuId, vehicleMessages.size());
        }
    }
}
