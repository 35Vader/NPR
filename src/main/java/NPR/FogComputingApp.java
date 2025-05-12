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
import java.util.concurrent.TimeUnit;

/**
 * Aplicação para o nó Fog que processa dados recebidos das RSUs, calcula
 * situações de risco e envia alertas de volta para as RSUs e veículos.
 */
public class FogComputingApp extends AbstractApplication<ServerOperatingSystem> implements ServerApplication {

    // Constantes para configuração
    private static final long PROCESSING_INTERVAL_MS = 100; // Intervalo de processamento (100ms)
    private static final long RISK_DETECTION_INTERVAL_MS = 200; // Intervalo de cálculo de risco (200ms)
    private static final long TRAFFIC_INFO_INTERVAL_MS = 5000; // Intervalo para informações de tráfego (5s)
    private static final String APP_ID = "FogComputingApp";
    
    // Parâmetros para detecção de risco
    private static final double COLLISION_RISK_TIME_THRESHOLD_S = 3.0; // Limiar de tempo para risco de colisão (3s)
    private static final double COLLISION_DISTANCE_THRESHOLD_M = 10.0; // Distância mínima para risco (10m)
    private static final double SPEED_LIMIT_KMH = 50.0; // Limite de velocidade (km/h)
    private static final double SPEED_LIMIT_MS = SPEED_LIMIT_KMH / 3.6; // Limite de velocidade (m/s)
    
    // Estruturas de dados para armazenar informações dos veículos
    private final Map<String, VehicleInfo> vehicleInfoMap = new HashMap<>();
    private final Map<String, List<String>> rsuToVehicles = new HashMap<>();
    private final Map<String, GeoPoint> rsuLocationMap = new HashMap<>();
    private final Set<String> activeRSUs = new HashSet<>();
    
    // Contadores e estatísticas 
    private int totalVehiclesDetected = 0;
    private int totalRiskSituationsDetected = 0;
    private int totalSpeedViolations = 0;
    private final Map<String, TrafficSegmentInfo> trafficSegments = new HashMap<>();

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Aplicação Fog Computing inicializada no servidor {}", getOs().getId());
        
        // Agenda o processamento periódico de dados
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL_MS, this::processData);
        
        // Agenda a detecção de risco
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + RISK_DETECTION_INTERVAL_MS, this::detectRisk);
        
        // Agenda o envio de informações de tráfego
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRAFFIC_INFO_INTERVAL_MS, this::sendTrafficInfo);
    }

    @Override
    public void onShutdown() {
        getLog().infoSimTime(this, "Aplicação Fog Computing encerrada no servidor {}", getOs().getId());
        
        // Registra estatísticas finais
        getLog().infoSimTime(this, "Estatísticas finais - Veículos detectados: {}, Situações de risco: {}, Violações de velocidade: {}",
                totalVehiclesDetected, totalRiskSituationsDetected, totalSpeedViolations);
    }

    @Override
    public void onMessageReceived(V2xMessageReception messageReception) {
        V2xMessage receivedMessage = messageReception.getMessage();
        
        // Processa mensagens agregadas recebidas das RSUs
        if (receivedMessage instanceof RsuDataCollectorApp.RsuAggregatedDataMessage) {
            processRsuAggregatedData((RsuDataCollectorApp.RsuAggregatedDataMessage) receivedMessage);
        }
    }
    
    /**
     * Processa dados agregados recebidos das RSUs.
     */
    private void processRsuAggregatedData(RsuDataCollectorApp.RsuAggregatedDataMessage message) {
        String rsuId = message.getRsuId();
        List<VehicleDataSenderApp.VehicleDataMessage> vehicleMessages = message.getVehicleMessages();
        
        getLog().infoSimTime(this, "Servidor Fog recebeu {} mensagens de veículos da RSU {}", 
                vehicleMessages.size(), rsuId);
        
        // Registrar RSU como ativa
        activeRSUs.add(rsuId);
        
        // Armazenar a posição da RSU se ainda não conhecemos
        if (!rsuLocationMap.containsKey(rsuId) && !vehicleMessages.isEmpty()) {
            // Assume a posição da RSU próxima ao primeiro veículo (para simplificar)
            rsuLocationMap.put(rsuId, vehicleMessages.get(0).getPosition());
        }
        
        // Lista para registrar veículos em cada RSU
        List<String> vehiclesInRsu = new ArrayList<>();
        
        // Processamento das mensagens dos veículos
        for (VehicleDataSenderApp.VehicleDataMessage vehicleMessage : vehicleMessages) {
            String vehicleId = vehicleMessage.getVehicleId();
            GeoPoint position = vehicleMessage.getPosition();
            double speed = vehicleMessage.getSpeed();
            double heading = vehicleMessage.getHeading();
            
            // Registra veículo como visível por esta RSU
            vehiclesInRsu.add(vehicleId);
            
            // Atualiza ou cria informações do veículo
            VehicleInfo vehicleInfo = vehicleInfoMap.getOrDefault(vehicleId, 
                    new VehicleInfo(vehicleId, position, speed, heading, getOs().getSimulationTime()));
            
            // Calcula aceleração se temos informação anterior
            if (vehicleInfoMap.containsKey(vehicleId)) {
                long timeDelta = getOs().getSimulationTime() - vehicleInfo.getLastUpdateTime();
                double speedDelta = speed - vehicleInfo.getSpeed();
                
                if (timeDelta > 0) {
                    double acceleration = (speedDelta / timeDelta) * 1000; // convert to m/s²
                    vehicleInfo.setAcceleration(acceleration);
                }
            }
            
            // Atualiza informações do veículo
            vehicleInfo.updateData(position, speed, heading, getOs().getSimulationTime());
            vehicleInfoMap.put(vehicleId, vehicleInfo);
            
            // Incrementa o contador de veículos detectados se for novo
            if (!vehicleInfoMap.containsKey(vehicleId)) {
                totalVehiclesDetected++;
            }
            
            // Atualiza estatísticas de tráfego por segmento
            updateTrafficSegmentInfo(vehicleId, position, speed);
        }
        
        // Atualiza a lista de veículos para esta RSU
        rsuToVehicles.put(rsuId, vehiclesInRsu);
    }
    
    /**
     * Atualiza informações de segmento de tráfego com base na posição do veículo.
     */
    private void updateTrafficSegmentInfo(String vehicleId, GeoPoint position, double speed) {
        // Simplificação: use a coordenada arredondada como identificador de segmento
        // Em uma aplicação real, usaríamos um mapa de estradas com segmentos definidos
        String segmentId = String.format("%.3f_%.3f", 
                Math.floor(position.getLatitude() * 1000) / 1000, 
                Math.floor(position.getLongitude() * 1000) / 1000);
        
        TrafficSegmentInfo segment = trafficSegments.getOrDefault(segmentId, 
                new TrafficSegmentInfo(segmentId, position));
        
        segment.addVehicleData(vehicleId, speed);
        trafficSegments.put(segmentId, segment);
    }
    
    /**
     * Processa periodicamente os dados armazenados.
     */
    private void processData(@Nonnull final Event event) {
        // Remove informações de veículos antigos (mais de 10 segundos sem atualização)
        long currentTime = getOs().getSimulationTime();
        List<String> vehiclesToRemove = new ArrayList<>();
        
        for (Map.Entry<String, VehicleInfo> entry : vehicleInfoMap.entrySet()) {
            if (currentTime - entry.getValue().getLastUpdateTime() > 10000) {
                vehiclesToRemove.add(entry.getKey());
            }
        }
        
        for (String vehicleId : vehiclesToRemove) {
            vehicleInfoMap.remove(vehicleId);
        }
        
        if (!vehiclesToRemove.isEmpty()) {
            getLog().infoSimTime(this, "Removidos {} veículos sem atualização recente", vehiclesToRemove.size());
        }
        
        // Agenda o próximo processamento
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL_MS, this::processData);
    }
    
    /**
     * Detecta situações de risco entre veículos.
     */
    private void detectRisk(@Nonnull final Event event) {
        List<RiskSituation> detectedRisks = new ArrayList<>();
        
        // Verifica violações de velocidade
        for (VehicleInfo vehicle : vehicleInfoMap.values()) {
            // Verifica se a velocidade está acima do limite
            if (vehicle.getSpeed() > SPEED_LIMIT_MS) {
                RiskSituation risk = new RiskSituation(
                        RiskType.SPEED_VIOLATION,
                        vehicle.getVehicleId(),
                        null,
                        vehicle.getPosition(),
                        String.format("Velocidade: %.1f km/h (limite: %.1f km/h)", 
                                vehicle.getSpeed() * 3.6, SPEED_LIMIT_KMH)
                );
                detectedRisks.add(risk);
                totalSpeedViolations++;
            }
        }
        
        // Verifica possíveis colisões entre pares de veículos
        List<VehicleInfo> vehicles = new ArrayList<>(vehicleInfoMap.values());
        for (int i = 0; i < vehicles.size(); i++) {
            VehicleInfo vehicle1 = vehicles.get(i);
            
            for (int j = i + 1; j < vehicles.size(); j++) {
                VehicleInfo vehicle2 = vehicles.get(j);
                
                // Calcula a distância entre os veículos
                double distance = GeoUtils.calculateDistance(vehicle1.getPosition(), vehicle2.getPosition());
                
                // Se estiverem próximos, verificar possível colisão
                if (distance < 50) { // Só analisa veículos a menos de 50m
                    // Cálculo simplificado de TTC (Time To Collision)
                    double ttc = calculateTimeToCollision(vehicle1, vehicle2);
                    
                    // Se o TTC for menor que o limiar, considera um risco
                    if (ttc > 0 && ttc < COLLISION_RISK_TIME_THRESHOLD_S) {
                        RiskSituation risk = new RiskSituation(
                                RiskType.COLLISION_RISK,
                                vehicle1.getVehicleId(),
                                vehicle2.getVehicleId(),
                                vehicle1.getPosition(),
                                String.format("Possível colisão em %.1f segundos com %s (distância: %.1f m)", 
                                        ttc, vehicle2.getVehicleId(), distance)
                        );
                        detectedRisks.add(risk);
                        totalRiskSituationsDetected++;
                    }
                }
            }
        }
        
        // Envia alertas para as situações de risco detectadas
        if (!detectedRisks.isEmpty()) {
            sendRiskAlerts(detectedRisks);
        }
        
        // Agenda a próxima detecção de risco
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + RISK_DETECTION_INTERVAL_MS, this::detectRisk);
    }
    
    /**
     * Calcula o tempo para colisão entre dois veículos.
     * Implementação simplificada - em um sistema real seria mais complexo.
     */
    private double calculateTimeToCollision(VehicleInfo vehicle1, VehicleInfo vehicle2) {
        // Converte coordenadas geográficas para cartesianas para simplificar cálculos
        CartesianPoint pos1 = GeoUtils.toCartesian(vehicle1.getPosition());
        CartesianPoint pos2 = GeoUtils.toCartesian(vehicle2.getPosition());
        
        // Calcula vetores de velocidade baseados na velocidade e direção
        double vx1 = vehicle1.getSpeed() * Math.sin(Math.toRadians(vehicle1.getHeading()));
        double vy1 = vehicle1.getSpeed() * Math.cos(Math.toRadians(vehicle1.getHeading()));
        
        double vx2 = vehicle2.getSpeed() * Math.sin(Math.toRadians(vehicle2.getHeading()));
        double vy2 = vehicle2.getSpeed() * Math.cos(Math.toRadians(vehicle2.getHeading()));
        
        // Diferenças de posição e velocidade
        double dx = pos2.getX() - pos1.getX();
        double dy = pos2.getY() - pos1.getY();
        double dvx = vx2 - vx1;
        double dvy = vy2 - vy1;
        
        // Cálculo do produto escalar
        double a = dvx * dvx + dvy * dvy;
        if (a < 0.0001) return Double.POSITIVE_INFINITY; // Velocidades relativas muito pequenas
        
        double b = 2 * (dx * dvx + dy * dvy);
        double c = dx * dx + dy * dy - COLLISION_DISTANCE_THRESHOLD_M * COLLISION_DISTANCE_THRESHOLD_M;
        
        // Cálculo da equação quadrática
        double discriminant = b * b - 4 * a * c;
        
        if (discriminant < 0) return Double.POSITIVE_INFINITY; // Sem solução real
        
        // Encontra o menor tempo positivo
        double t1 = (-b + Math.sqrt(discriminant)) / (2 * a);
        double t2 = (-b - Math.sqrt(discriminant)) / (2 * a);
        
        // Retorna o menor tempo positivo, ou infinito se ambos forem negativos
        if (t1 >= 0 && t2 >= 0) {
            return Math.min(t1, t2);
        } else if (t1 >= 0) {
            return t1;
        } else if (t2 >= 0) {
            return t2;
        } else {
            return Double.POSITIVE_INFINITY;
        }
    }
    
    /**
     * Envia alertas de risco para as RSUs apropriadas.
     */
    private void sendRiskAlerts(List<RiskSituation> risks) {
        if (risks.isEmpty() || activeRSUs.isEmpty()) {
            return;
        }
        
        getLog().infoSimTime(this, "Enviando {} alertas de risco para {} RSUs", risks.size(), activeRSUs.size());
        
        // Cria mensagem com alertas de risco
        FogRiskAlertsMessage alertsMessage = new FogRiskAlertsMessage(
                getOs().getId(),
                getOs().getSimulationTime(),
                new ArrayList<>(risks)
        );
        
        // Envia para cada RSU ativa
        for (String rsuId : activeRSUs) {
            // Configurar o roteamento para cada RSU
            DestinationAddressContainer destination = new DestinationAddressContainer(
                    IpResolver.getSingleton().nameToIpAddress(rsuId),
                    APP_ID
            );
            
            MessageRouting routing = MessageRouting.ipRouting(destination);
            
            // Transmitir a mensagem
            getOs().getRouter().sendIpMessage(alertsMessage, routing);
        }
    }
    
    /**
     * Envia informações de tráfego periodicamente.
     */
    private void sendTrafficInfo(@Nonnull final Event event) {
        if (activeRSUs.isEmpty() || trafficSegments.isEmpty()) {
            // Agenda a próxima transmissão
            getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRAFFIC_INFO_INTERVAL_MS, this::sendTrafficInfo);
            return;
        }
        
        // Para cada RSU, determina quais segmentos são relevantes (simplificação)
        // Em uma aplicação real, seria baseado na proximidade geográfica
        for (String rsuId : activeRSUs) {
            // Cria uma lista de informações de segmentos relevantes para esta RSU
            List<TrafficSegmentInfo> relevantSegments = new ArrayList<>(trafficSegments.values());
            
            // Cria mensagem com informações de tráfego
            FogTrafficInfoMessage trafficMessage = new FogTrafficInfoMessage(
                    getOs().getId(),
                    getOs().getSimulationTime(),
                    new ArrayList<>(relevantSegments)
            );
            
            // Configurar o roteamento
            DestinationAddressContainer destination = new DestinationAddressContainer(
                    IpResolver.getSingleton().nameToIpAddress(rsuId),
                    APP_ID
            );
            
            MessageRouting routing = MessageRouting.ipRouting(destination);
            
            // Transmitir a mensagem
            getOs().getRouter().sendIpMessage(trafficMessage, routing);
            
            getLog().infoSimTime(this, "Servidor Fog enviou informações de tráfego para RSU {} com {} segmentos",
                    rsuId, relevantSegments.size());
        }
        
        // Agenda a próxima transmissão
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRAFFIC_INFO_INTERVAL_MS, this::sendTrafficInfo);
    }
    
    /**
     * Classe interna para armazenar informações de um veículo.
     */
    public static class VehicleInfo {
        private final String vehicleId;
        private GeoPoint position;
        private double speed;
        private double heading;
        private double acceleration;
        private long lastUpdateTime;
        private GeoPoint previousPosition;
        private final long creationTime;

        public VehicleInfo(String vehicleId, GeoPoint position, double speed, double heading, long timestamp) {
            this.vehicleId = vehicleId;
            this.position = position;
            this.previousPosition = position;
            this.speed = speed;
            this.heading = heading;
            this.acceleration = 0.0;
            this.lastUpdateTime = timestamp;
            this.creationTime = timestamp;
        }

        public void updateData(GeoPoint position, double speed, double heading, long timestamp) {
            this.previousPosition = this.position;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.lastUpdateTime = timestamp;
        }

        public String getVehicleId() {
            return vehicleId;
        }

        public GeoPoint getPosition() {
            return position;
        }
        
        public GeoPoint getPreviousPosition() {
            return previousPosition;
        }

        public double getSpeed() {
            return speed;
        }

        public double getHeading() {
            return heading;
        }

        public double getAcceleration() {
            return acceleration;
        }

        public void setAcceleration(double acceleration) {
            this.acceleration = acceleration;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
        
        public long getCreationTime() {
            return creationTime;
        }
    }
    
    /**
     * Enum para os tipos de risco.
     */
    public enum RiskType {
        COLLISION_RISK,
        SPEED_VIOLATION,
        PEDESTRIAN_RISK,
        ROAD_HAZARD
    }
    
    /**
     * Classe para situações de risco.
     */
    public static class RiskSituation {
        private final RiskType type;
        private final String primaryVehicleId;
        private final String secondaryVehicleId; // pode ser null para riscos que envolvem apenas um veículo
        private final GeoPoint location;
        private final String description;
        private final long timestamp;

        public RiskSituation(RiskType type, String primaryVehicleId, String secondaryVehicleId, 
                GeoPoint location, String description) {
            this.type = type;
            this.primaryVehicleId = primaryVehicleId;
            this.secondaryVehicleId = secondaryVehicleId;
            this.location = location;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }

        public RiskType getType() {
            return type;
        }

        public String getPrimaryVehicleId() {
            return primaryVehicleId;
        }

        public String getSecondaryVehicleId() {
            return secondaryVehicleId;
        }

        public GeoPoint getLocation() {
            return location;
        }

        public String getDescription() {
            return description;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
    
    /**
     * Classe para informações de segmento de tráfego.
     */
    public static class TrafficSegmentInfo {
        private final String segmentId;
        private final GeoPoint centerLocation;
        private final Map<String, Double> vehicleSpeeds = new HashMap<>();
        private int vehicleCount = 0;
        private double avgSpeed = 0.0;
        private double maxSpeed = 0.0;
        private long lastUpdateTime;

        public TrafficSegmentInfo(String segmentId, GeoPoint centerLocation) {
            this.segmentId = segmentId;
            this.centerLocation = centerLocation;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void addVehicleData(String vehicleId, double speed) {
            // Atualiza a velocidade do veículo
            vehicleSpeeds.put(vehicleId, speed);
            
            // Recalcula estatísticas
            vehicleCount = vehicleSpeeds.size();
            
            double totalSpeed = 0;
            maxSpeed = 0;
            for (double v : vehicleSpeeds.values()) {
                totalSpeed += v;
                if (v > maxSpeed) {
                    maxSpeed = v;
                }
            }
            
            avgSpeed = totalSpeed / vehicleCount;
            lastUpdateTime = System.currentTimeMillis();
        }

        public String getSegmentId() {
            return segmentId;
        }

        public GeoPoint getCenterLocation() {
            return centerLocation;
        }

        public int getVehicleCount() {
            return vehicleCount;
        }

        public double getAvgSpeed() {
            return avgSpeed;
        }

        public double getMaxSpeed() {
            return maxSpeed;
        }
        
        public double getDensity() {
            // Densidade simplificada: veículos por segmento
            return vehicleCount;
        }
        
        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }
    
    /**
     * Mensagem com alertas de risco enviados pelo servidor Fog.
     */
    public static class FogRiskAlertsMessage extends V2xMessage {
        private final String fogServerId;
        private final long timestamp;
        private final List<RiskSituation> riskSituations;

        public FogRiskAlertsMessage(String fogServerId, long timestamp, List<RiskSituation> riskSituations) {
            this.fogServerId = fogServerId;
            this.timestamp = timestamp;
            this.riskSituations = riskSituations;
        }

        public String getFogServerId() {
            return fogServerId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public List<RiskSituation> getRiskSituations() {
            return riskSituations;
        }
    }
    
    /**
     * Mensagem com informações de tráfego enviados pelo servidor Fog.
     */
    public static class FogTrafficInfoMessage extends V2xMessage {
        private final String fogServerId;
        private final long timestamp;
        private final List<TrafficSegmentInfo> segmentInfos;

        public FogTrafficInfoMessage(String fogServerId, long timestamp, List<TrafficSegmentInfo> segmentInfos) {
            this.fogServerId = fogServerId;
            this.timestamp = timestamp;
            this.segmentInfos = segmentInfos;
        }

        public String getFogServerId() {
            return fogServerId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public List<TrafficSegmentInfo> getSegmentInfos() {
            return segmentInfos;
        }
    }
}
