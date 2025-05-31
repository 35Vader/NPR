package org.myproject.applications;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.ServerApplication;
import org.eclipse.mosaic.fed.application.app.api.os.ServerOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageReception;
import org.eclipse.mosaic.lib.geo.CartesianPoint;
import org.eclipse.mosaic.lib.geo.GeoPoint;
import org.eclipse.mosaic.lib.geo.GeoUtils;
import org.eclipse.mosaic.lib.objects.addressing.DestinationAddressContainer;
import org.eclipse.mosaic.lib.objects.addressing.IpResolver;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import javax.annotation.Nonnull;
import java.util.*;

public class FogComputingApp extends AbstractApplication<ServerOperatingSystem> 
        implements ServerApplication {

    // Configurações
    private static final long PROCESSING_INTERVAL = 100; // ms (10Hz)
    private static final long RISK_DETECTION_INTERVAL = 200; // ms (5Hz)
    private static final long TRAFFIC_INFO_INTERVAL = 5000; // ms (0.2Hz)
    private static final String APP_ID = "FogComputingApp";
    
    // Parâmetros de risco
    private static final double COLLISION_TIME_THRESHOLD = 3.0; // segundos
    private static final double COLLISION_DISTANCE_THRESHOLD = 10.0; // metros
    private static final double SPEED_LIMIT = 50.0 / 3.6; // m/s (50 km/h)
    
    // Estruturas de dados
    private final Map<String, VehicleInfo> vehicleInfoMap = new HashMap<>();
    private final Map<String, GeoPoint> rsuLocations = new HashMap<>();
    private final Map<String, TrafficSegmentInfo> trafficSegments = new HashMap<>();
    private final Set<String> activeRSUs = new HashSet<>();

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Iniciando nó Fog Computing");
        
        // Agendar processamentos periódicos
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + PROCESSING_INTERVAL, 
            this::processData
        );
        
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + RISK_DETECTION_INTERVAL, 
            this::detectRisks
        );
        
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + TRAFFIC_INFO_INTERVAL, 
            this::sendTrafficInfo
        );
    }

    @Override
    public void onMessageReceived(V2xMessageReception reception) {
        V2xMessage msg = reception.getMessage();
        
        if (msg instanceof RsuDataCollectorApp.RsuAggregatedDataMessage) {
            processRsuData((RsuDataCollectorApp.RsuAggregatedDataMessage) msg);
        }
    }

    private void processRsuData(RsuDataCollectorApp.RsuAggregatedDataMessage msg) {
        String rsuId = msg.getRsuId();
        GeoPoint rsuPosition = msg.getRsuPosition();
        
        // Registrar RSU
        activeRSUs.add(rsuId);
        rsuLocations.put(rsuId, rsuPosition);
        
        // Processar dados dos veículos
        for (VehicleApp.VehicleDataMessage vehicleMsg : msg.getVehicleMessages()) {
            String vehicleId = vehicleMsg.getVehicleId();
            VehicleInfo vehicleInfo = vehicleInfoMap.computeIfAbsent(vehicleId, 
                id -> new VehicleInfo(id, vehicleMsg.getPosition(), vehicleMsg.getSpeed(), 
                                     vehicleMsg.getHeading(), getOs().getSimulationTime()));
            
            // Atualizar dados do veículo
            vehicleInfo.update(
                vehicleMsg.getPosition(), 
                vehicleMsg.getSpeed(), 
                vehicleMsg.getHeading(), 
                getOs().getSimulationTime()
            );
            
            // Atualizar segmento de tráfego
            updateTrafficSegment(vehicleId, vehicleMsg.getPosition(), vehicleMsg.getSpeed());
        }
    }

    private void updateTrafficSegment(String vehicleId, GeoPoint position, double speed) {
        String segmentId = getSegmentId(position);
        TrafficSegmentInfo segment = trafficSegments.computeIfAbsent(segmentId, 
            id -> new TrafficSegmentInfo(id, position));
        
        segment.addVehicle(vehicleId, speed, getOs().getSimulationTime());
    }
    
    private String getSegmentId(GeoPoint position) {
        return String.format("seg_%.4f_%.4f", 
            Math.round(position.getLatitude() * 10000) / 10000.0,
            Math.round(position.getLongitude() * 10000) / 10000.0);
    }

    private void processData(@Nonnull final Event event) {
        // Remover veículos inativos
        long currentTime = getOs().getSimulationTime();
        vehicleInfoMap.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().getLastUpdateTime() > 10000);
        
        // Reagendar
        getOs().getEventManager().addEvent(
            currentTime + PROCESSING_INTERVAL, 
            this::processData
        );
    }

    private void detectRisks(@Nonnull final Event event) {
        List<RiskSituation> detectedRisks = new ArrayList<>();
        List<VehicleInfo> vehicles = new ArrayList<>(vehicleInfoMap.values());
        
        // 1. Detectar violações de velocidade
        for (VehicleInfo vehicle : vehicles) {
            if (vehicle.getSpeed() > SPEED_LIMIT) {
                detectedRisks.add(createSpeedViolation(vehicle));
            }
        }
        
        // 2. Detectar riscos de colisão
        for (int i = 0; i < vehicles.size(); i++) {
            for (int j = i + 1; j < vehicles.size(); j++) {
                VehicleInfo v1 = vehicles.get(i);
                VehicleInfo v2 = vehicles.get(j);
                
                double distance = GeoUtils.calculateDistance(v1.getPosition(), v2.getPosition());
                if (distance < 100) { // Apenas veículos próximos
                    double ttc = calculateTTC(v1, v2);
                    if (ttc > 0 && ttc < COLLISION_TIME_THRESHOLD) {
                        detectedRisks.add(createCollisionRisk(v1, v2, ttc, distance));
                        
                        // Enviar comando para semáforo se for em cruzamento
                        if (isNearIntersection(v1.getPosition())) {
                            sendTrafficLightCommand(
                                getIntersectionId(v1.getPosition()),
                                TrafficLightCommandMessage.Command.FORCE_RED,
                                15 // segundos
                            );
                        }
                    }
                }
            }
        }
        
        // 3. Enviar alertas para RSUs
        if (!detectedRisks.isEmpty()) {
            sendRiskAlerts(detectedRisks);
        }
        
        // Reagendar
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + RISK_DETECTION_INTERVAL, 
            this::detectRisks
        );
    }
    
    private RiskSituation createSpeedViolation(VehicleInfo vehicle) {
        return new RiskSituation(
            vehicle.getVehicleId(),
            null,
            RiskType.SPEED_VIOLATION,
            vehicle.getPosition(),
            String.format("Veículo %s excedeu limite de velocidade: %.1f km/h",
                vehicle.getVehicleId(), vehicle.getSpeed() * 3.6)
        );
    }
    
    private RiskSituation createCollisionRisk(VehicleInfo v1, VehicleInfo v2, double ttc, double distance) {
        return new RiskSituation(
            v1.getVehicleId(),
            v2.getVehicleId(),
            RiskType.COLLISION_RISK,
            GeoUtils.calculateMidpoint(v1.getPosition(), v2.getPosition()),
            String.format("Risco de colisão entre %s e %s em %.1f segundos (distância: %.1f m)",
                v1.getVehicleId(), v2.getVehicleId(), ttc, distance)
        );
    }
    
    private double calculateTTC(VehicleInfo v1, VehicleInfo v2) {
        // Implementação simplificada do Time To Collision
        CartesianPoint p1 = GeoUtils.toCartesian(v1.getPosition());
        CartesianPoint p2 = GeoUtils.toCartesian(v2.getPosition());
        
        CartesianPoint relPosition = p2.subtract(p1);
        CartesianPoint relVelocity = new CartesianPoint(
            v2.getSpeed() * Math.cos(Math.toRadians(v2.getHeading())) - 
            v1.getSpeed() * Math.cos(Math.toRadians(v1.getHeading())),
            v2.getSpeed() * Math.sin(Math.toRadians(v2.getHeading())) - 
            v1.getSpeed() * Math.sin(Math.toRadians(v1.getHeading()))
        );
        
        double closingSpeed = relVelocity.dot(relPosition) / relPosition.length();
        if (closingSpeed >= 0) return Double.POSITIVE_INFINITY; // Afastando-se
        
        return -relPosition.length() / closingSpeed;
    }
    
    private boolean isNearIntersection(GeoPoint position) {
        for (GeoPoint rsuPos : rsuLocations.values()) {
            if (GeoUtils.calculateDistance(position, rsuPos) < 100) {
                return true;
            }
        }
        return false;
    }
    
    private String getIntersectionId(GeoPoint position) {
        // Lógica para mapear posição para ID de cruzamento
        // (simplificado para demonstração)
        return "intersection_" + 
            Math.round(position.getLatitude() * 1000) + "_" + 
            Math.round(position.getLongitude() * 1000);
    }

    private void sendRiskAlerts(List<RiskSituation> risks) {
        FogRiskAlertsMessage alertsMsg = new FogRiskAlertsMessage(
            getOs().getId(),
            getOs().getSimulationTime(),
            risks
        );
        
        for (String rsuId : activeRSUs) {
            DestinationAddressContainer destination = new DestinationAddressContainer(
                IpResolver.getSingleton().nameToIpAddress(rsuId), 
                RsuDataCollectorApp.APP_ID
            );
            
            MessageRouting routing = MessageRouting.ipRouting(destination);
            getOs().getRouter().sendIpMessage(alertsMsg, routing);
        }
    }
    
    private void sendTrafficLightCommand(String intersectionId, 
                                        TrafficLightCommandMessage.Command command, 
                                        int duration) {
        // Encontrar RSU mais próxima do cruzamento
        String targetRsu = findClosestRsu(intersectionId);
        if (targetRsu == null) return;
        
        DestinationAddressContainer destination = new DestinationAddressContainer(
            IpResolver.getSingleton().nameToIpAddress(targetRsu), 
            RsuDataCollectorApp.APP_ID
        );
        
        MessageRouting routing = MessageRouting.ipRouting(destination);
        TrafficLightCommandMessage commandMsg = new TrafficLightCommandMessage(
            routing,
            command,
            intersectionId,
            duration
        );
        
        getOs().getRouter().sendIpMessage(commandMsg, routing);
    }
    
    private String findClosestRsu(String intersectionId) {
        // Lógica simplificada: retorna primeira RSU
        return activeRSUs.isEmpty() ? null : activeRSUs.iterator().next();
    }

    private void sendTrafficInfo(@Nonnull final Event event) {
        if (trafficSegments.isEmpty() || activeRSUs.isEmpty()) return;
        
        FogTrafficInfoMessage trafficMsg = new FogTrafficInfoMessage(
            getOs().getId(),
            getOs().getSimulationTime(),
            new ArrayList<>(trafficSegments.values())
        );
        
        for (String rsuId : activeRSUs) {
            DestinationAddressContainer destination = new DestinationAddressContainer(
                IpResolver.getSingleton().nameToIpAddress(rsuId), 
                RsuDataCollectorApp.APP_ID
            );
            
            MessageRouting routing = MessageRouting.ipRouting(destination);
            getOs().getRouter().sendIpMessage(trafficMsg, routing);
        }
        
        // Reagendar
        getOs().getEventManager().addEvent(
            getOs().getSimulationTime() + TRAFFIC_INFO_INTERVAL, 
            this::sendTrafficInfo
        );
    }

    // ===== CLASSES INTERNAS =====
    
    public static class VehicleInfo {
        private final String vehicleId;
        private GeoPoint position;
        private double speed;
        private double heading;
        private long lastUpdateTime;
        
        public VehicleInfo(String vehicleId, GeoPoint position, double speed, 
                          double heading, long timestamp) {
            this.vehicleId = vehicleId;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.lastUpdateTime = timestamp;
        }
        
        public void update(GeoPoint position, double speed, double heading, long timestamp) {
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.lastUpdateTime = timestamp;
        }
        
        // Getters
        public String getVehicleId() { return vehicleId; }
        public GeoPoint getPosition() { return position; }
        public double getSpeed() { return speed; }
        public double getHeading() { return heading; }
        public long getLastUpdateTime() { return lastUpdateTime; }
    }
    
    public static class TrafficSegmentInfo {
        private final String segmentId;
        private final GeoPoint center;
        private int vehicleCount = 0;
        private double totalSpeed = 0;
        private double avgSpeed = 0;
        private long lastUpdate;
        
        public TrafficSegmentInfo(String segmentId, GeoPoint center) {
            this.segmentId = segmentId;
            this.center = center;
        }
        
        public void addVehicle(String vehicleId, double speed, long timestamp) {
            vehicleCount++;
            totalSpeed += speed;
            avgSpeed = totalSpeed / vehicleCount;
            lastUpdate = timestamp;
        }
        
        // Getters
        public String getSegmentId() { return segmentId; }
        public GeoPoint getCenter() { return center; }
        public int getVehicleCount() { return vehicleCount; }
        public double getAvgSpeed() { return avgSpeed; }
        public long getLastUpdate() { return lastUpdate; }
    }
    
    public enum RiskType {
        COLLISION_RISK, SPEED_VIOLATION, PEDESTRIAN_RISK, ROAD_HAZARD
    }
    
    public static class RiskSituation {
        private final String primaryVehicleId;
        private final String secondaryVehicleId;
        private final RiskType type;
        private final GeoPoint location;
        private final String description;
        private final long timestamp;
        
        public RiskSituation(String primaryVehicleId, String secondaryVehicleId,
                            RiskType type, GeoPoint location, String description) {
            this.primaryVehicleId = primaryVehicleId;
            this.secondaryVehicleId = secondaryVehicleId;
            this.type = type;
            this.location = location;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public String getPrimaryVehicleId() { return primaryVehicleId; }
        public String getSecondaryVehicleId() { return secondaryVehicleId; }
        public RiskType getType() { return type; }
        public GeoPoint getLocation() { return location; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class FogRiskAlertsMessage extends V2xMessage {
        private final String fogServerId;
        private final long timestamp;
        private final List<RiskSituation> riskSituations;
        
        public FogRiskAlertsMessage(String fogServerId, long timestamp, 
                                   List<RiskSituation> riskSituations) {
            super(null);
            this.fogServerId = fogServerId;
            this.timestamp = timestamp;
            this.riskSituations = riskSituations;
        }
        
        // Getters
        public String getFogServerId() { return fogServerId; }
        public long getTimestamp() { return timestamp; }
        public List<RiskSituation> getRiskSituations() { return riskSituations; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("FogRiskAlerts[%s,%d risks]", fogServerId, riskSituations.size());
        }
    }
    
    public static class FogTrafficInfoMessage extends V2xMessage {
        private final String fogServerId;
        private final long timestamp;
        private final List<TrafficSegmentInfo> segmentInfos;
        
        public FogTrafficInfoMessage(String fogServerId, long timestamp, 
                                    List<TrafficSegmentInfo> segmentInfos) {
            super(null);
            this.fogServerId = fogServerId;
            this.timestamp = timestamp;
            this.segmentInfos = segmentInfos;
        }
        
        // Getters
        public String getFogServerId() { return fogServerId; }
        public long getTimestamp() { return timestamp; }
        public List<TrafficSegmentInfo> getSegmentInfos() { return segmentInfos; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("FogTrafficInfo[%s,%d segments]", fogServerId, segmentInfos.size());
        }
    }
    
    public static class TrafficLightCommandMessage extends V2xMessage {
        public enum Command {
            SWITCH_PROGRAM, FORCE_RED, FORCE_GREEN, EXTEND_GREEN, EMERGENCY_STOP
        }
        
        private final Command command;
        private final String intersectionId;
        private final int duration; // segundos
        
        public TrafficLightCommandMessage(MessageRouting routing, Command command, 
                                         String intersectionId, int duration) {
            super(routing);
            this.command = command;
            this.intersectionId = intersectionId;
            this.duration = duration;
        }
        
        // Getters
        public Command getCommand() { return command; }
        public String getIntersectionId() { return intersectionId; }
        public int getDuration() { return duration; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("TrafficLightCmd[%s,%s,%ds]", 
                command, intersectionId, duration);
        }
    }
}
