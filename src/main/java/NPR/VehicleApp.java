package NPR;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageReception;
import org.eclipse.mosaic.lib.enums.AdHocChannel;
import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.lib.geo.GeoPoint;
import java.util.*;

public class VehicleApp extends AbstractApplication<VehicleOperatingSystem> implements VehicleApplication {

    // Configurações
    private static final long TRANSMISSION_INTERVAL = 100; // ms (10Hz)
    private static final int DIRECT_TRANSMISSION_RADIUS = 300; // metros
    private static final int MAX_HOPS = 3; // Máximo de saltos
    private static final long MESSAGE_TTL = 5000; // ms (5s)
    private static final long CLEANUP_INTERVAL = 1000; // ms
    private static final double RISK_DISTANCE_THRESHOLD = 150.0; // metros
    private static final String APP_ID = "VehicleApp";
    
    // Estruturas de dados
    private final Map<String, Long> forwardedMessages = new HashMap<>();
    private final Map<String, NeighborInfo> knownNeighbors = new HashMap<>();
    private final List<RiskSituation> activeRisks = new ArrayList<>();
    private String nearestRsuId = null;
    private GeoPoint nearestRsuPosition = null;
    private double nearestRsuDistance = Double.MAX_VALUE;
    
    // Estatísticas
    private int totalMessagesSent = 0;
    private int totalMessagesForwarded = 0;
    private int totalRisksReceived = 0;

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Iniciando aplicação veicular");
        
        // Configurar comunicação
        getOs().getAdHocModule().enable(
            new AdHocModuleConfiguration()
                .addRadio()
                .channel(AdHocChannel.CCH)
                .power(50)
                .create()
        );
        
        // Agendar o primeiro envio de dados
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRANSMISSION_INTERVAL, this::sendVehicleData);
        
        // Agendar limpeza periódica
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + CLEANUP_INTERVAL, this::cleanupOldData);
    }

    private void sendVehicleData(@Nonnull final Event event) {
        // Coletar dados do veículo
        VehicleData vehicleData = getOs().getVehicleData();
        GeoPoint position = vehicleData.getPosition();
        double speed = vehicleData.getSpeed();
        double heading = vehicleData.getHeading();
        TurnIndicator turnIndicator = determineTurnIndicator(vehicleData);

        // Criar mensagem
        VehicleDataMessage message = new VehicleDataMessage(
                getOs().getId(),
                position,
                speed,
                heading,
                getOs().getSimulationTime(),
                turnIndicator
        );

        // Configurar roteamento (broadcast geográfico)
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
                AdHocChannel.CCH,
                APP_ID,
                DIRECT_TRANSMISSION_RADIUS,
                position
        );

        // Enviar
        getOs().getAdHocModule().sendV2xMessage(message, routing);
        totalMessagesSent++;
        
        getLog().debugSimTime(this, "Veículo {} enviou dados: posição=({}, {}), velocidade={} m/s",
                getOs().getId(), position.getLatitude(), position.getLongitude(), speed);

        // Agendar próximo envio
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRANSMISSION_INTERVAL, this::sendVehicleData);
    }

    @Override
    public void onMessageReceived(V2xMessageReception reception) {
        V2xMessage msg = reception.getMessage();

        if (msg instanceof VehicleDataMessage) {
            processVehicleMessage((VehicleDataMessage) msg);
        } else if (msg instanceof ForwardedVehicleMessage) {
            processForwardedMessage((ForwardedVehicleMessage) msg);
        } else if (msg instanceof RsuRiskAlertMessage) {
            processRiskAlert((RsuRiskAlertMessage) msg);
        } else if (msg instanceof RsuRiskAlertsMessage) {
            processRiskAlerts((RsuRiskAlertsMessage) msg);
        } else if (msg instanceof RsuTrafficInfoMessage) {
            processTrafficInfo((RsuTrafficInfoMessage) msg);
        }
    }

    private void processVehicleMessage(VehicleDataMessage message) {
        // Ignorar mensagens próprias
        if (message.getVehicleId().equals(getOs().getId())) {
            return;
        }
        
        // Atualizar informações do vizinho
        NeighborInfo neighbor = new NeighborInfo(
            message.getVehicleId(),
            message.getPosition(),
            message.getSpeed(),
            message.getHeading(),
            getOs().getSimulationTime()
        );
        knownNeighbors.put(message.getVehicleId(), neighbor);
        
        // Verificar se deve encaminhar (multi-hop)
        if (shouldForwardMessage(message, 0)) {
            forwardMessage(message, 0);
        }
    }

    private void processForwardedMessage(ForwardedVehicleMessage forwardedMessage) {
        VehicleDataMessage originalMessage = forwardedMessage.getOriginalMessage();
        
        // Ignorar mensagens próprias ou já processadas
        if (originalMessage.getVehicleId().equals(getOs().getId()) || 
            forwardedMessages.containsKey(originalMessage.getMessageId())) {
            return;
        }
        
        // Registrar mensagem
        forwardedMessages.put(originalMessage.getMessageId(), getOs().getSimulationTime());
        
        // Atualizar informações do vizinho
        NeighborInfo neighbor = new NeighborInfo(
            originalMessage.getVehicleId(),
            originalMessage.getPosition(),
            originalMessage.getSpeed(),
            originalMessage.getHeading(),
            getOs().getSimulationTime()
        );
        knownNeighbors.put(originalMessage.getVehicleId(), neighbor);
        
        // Verificar se deve encaminhar novamente
        if (shouldForwardMessage(originalMessage, forwardedMessage.getCurrentHop())) {
            forwardMessage(originalMessage, forwardedMessage.getCurrentHop());
        }
    }

    private boolean shouldForwardMessage(VehicleDataMessage message, int currentHop) {
        // Verificar limite de saltos
        if (currentHop >= MAX_HOPS) {
            return false;
        }
        
        // Verificar mensagens antigas
        if (getOs().getSimulationTime() - message.getTimestamp() > MESSAGE_TTL) {
            return false;
        }
        
        // Verificar se já foi encaminhada
        if (forwardedMessages.containsKey(message.getMessageId())) {
            return false;
        }
        
        // Verificar proximidade com RSU
        if (nearestRsuId != null && nearestRsuDistance < DIRECT_TRANSMISSION_RADIUS) {
            return false;
        }
        
        return true;
    }

    private void forwardMessage(VehicleDataMessage originalMessage, int currentHop) {
        // Criar mensagem encaminhada
        ForwardedVehicleMessage fwdMsg = new ForwardedVehicleMessage(
            getOs().getId(),
            getOs().getSimulationTime(),
            originalMessage,
            currentHop + 1
        );

        // Configurar roteamento
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
            AdHocChannel.CCH,
            APP_ID,
            DIRECT_TRANSMISSION_RADIUS,
            getOs().getVehicleData().getPosition()
        );

        // Enviar
        getOs().getAdHocModule().sendV2xMessage(fwdMsg, routing);
        totalMessagesForwarded++;
        
        // Registrar encaminhamento
        forwardedMessages.put(originalMessage.getMessageId(), getOs().getSimulationTime());
        
        getLog().debugSimTime(this, "Veículo {} encaminhou mensagem de {} (salto {})",
            getOs().getId(), originalMessage.getVehicleId(), currentHop + 1);
    }

    private void processRiskAlert(RsuRiskAlertMessage message) {
        RiskSituation risk = message.getRiskSituation();
        
        getLog().infoSimTime(this, "ALERTA DE RISCO: {}", risk.getDescription());
        
        // Atualizar RSU mais próxima
        updateNearestRsu(message.getRsuId(), message.getRsuPosition());
        
        // Adicionar risco ativo
        addActiveRisk(risk);
        
        // Responder ao risco
        respondToRisk(risk);
    }

    private void processRiskAlerts(RsuRiskAlertsMessage message) {
        for (RiskSituation risk : message.getRiskSituations()) {
            if (isRiskRelevant(risk)) {
                getLog().infoSimTime(this, "ALERTA DE RISCO: {}", risk.getDescription());
                addActiveRisk(risk);
                respondToRisk(risk);
            }
        }
    }

    private void processTrafficInfo(RsuTrafficInfoMessage message) {
        getLog().infoSimTime(this, "Informações de tráfego recebidas da RSU {}", message.getRsuId());
        
        // 1. Atualizar métricas de tráfego locais
        Map<String, Double> newMetrics = message.getTrafficMetrics();
        trafficMetrics.putAll(newMetrics);
        
        // 2. Determinar segmento atual do veículo
        String currentSegment = determineCurrentSegment();
        
        // 3. Verificar condições do segmento atual
        if (currentSegment != null && trafficMetrics.containsKey(currentSegment)) {
            double segmentSpeed = trafficMetrics.get(currentSegment);
            
            // 4. Tomar ações com base nas condições do tráfego
            if (segmentSpeed < 10.0) { // Congestionamento grave (< 36 km/h)
                handleSevereCongestion(currentSegment, segmentSpeed);
            } 
            else if (segmentSpeed < 20.0) { // Congestionamento moderado (< 72 km/h)
                handleModerateCongestion(currentSegment, segmentSpeed);
            } 
            else if (segmentSpeed > 50.0) { // Tráfego fluido (> 180 km/h)
                handleFreeFlowTraffic(currentSegment, segmentSpeed);
            }
            
            // 5. Verificar se existem rotas alternativas melhores
            checkAlternativeRoutes(currentSegment, segmentSpeed);
        }
        
        // 6. Log detalhado para depuração
        getLog().debugSimTime(this, "Métricas de tráfego atualizadas: {}", trafficMetrics);
    }
    
    // Métodos auxiliares =====================================================
    
    private String determineCurrentSegment() {
        // Lógica para determinar o segmento atual baseado na posição GPS
        GeoPoint position = getOs().getVehicleData().getPosition();
        
        // Formato: seg_<latitude_arredondada>_<longitude_arredondada>
        return String.format("seg_%.4f_%.4f", 
            Math.round(position.getLatitude() * 10000) / 10000.0,
            Math.round(position.getLongitude() * 10000) / 10000.0);
    }
    
    private void handleSevereCongestion(String segment, double speed) {
        // Ações para congestionamento grave
        getLog().warnSimTime(this, "CONGESTIONAMENTO GRAVE no segmento {}: {:.1f} km/h", 
                            segment, speed * 3.6);
        
        // 1. Alertar o motorista
        getOs().getHumanMachineInterface().displayMessage(
            "Congestionamento grave à frente! Velocidade: " + Math.round(speed * 3.6) + " km/h");
        
        // 2. Reduzir velocidade do veículo
        getOs().slowDown(0.6, 3000); // Reduzir para 60% da velocidade atual por 3 segundos
        
        // 3. Sugerir rota alternativa
        suggestAlternativeRoute(segment);
    }
    
    private void handleModerateCongestion(String segment, double speed) {
        // Ações para congestionamento moderado
        getLog().infoSimTime(this, "Congestionamento moderado no segmento {}: {:.1f} km/h", 
                            segment, speed * 3.6);
        
        // 1. Alertar o motorista
        getOs().getHumanMachineInterface().displayMessage(
            "Tráfego lento à frente: " + Math.round(speed * 3.6) + " km/h");
        
        // 2. Reduzir velocidade gradualmente
        getOs().slowDown(0.8, 2000); // Reduzir para 80% da velocidade atual por 2 segundos
    }
    
    private void handleFreeFlowTraffic(String segment, double speed) {
        // Informar tráfego livre
        getLog().debugSimTime(this, "Tráfego livre no segmento {}: {:.1f} km/h", 
                             segment, speed * 3.6);
        
        // Restaurar velocidade normal
        if (getOs().getVehicleData().getSpeed() < speed * 0.9) {
            getOs().accelerate(1.2, 1000); // Acelerar para velocidade normal
        }
    }
    
    private void checkAlternativeRoutes(String currentSegment, double currentSpeed) {
        // Buscar rotas alternativas com melhor desempenho
        String bestAlternative = findBestAlternativeRoute(currentSegment, currentSpeed);
        
        if (bestAlternative != null) {
            double alternativeSpeed = trafficMetrics.getOrDefault(bestAlternative, 0.0);
            
            // Se a alternativa for pelo menos 30% mais rápida
            if (alternativeSpeed > currentSpeed * 1.3) {
                getLog().infoSimTime(this, "Rota alternativa mais rápida disponível: {} ({:.1f} km/h vs {:.1f} km/h)", 
                                    bestAlternative, alternativeSpeed * 3.6, currentSpeed * 3.6);
                
                getOs().getHumanMachineInterface().displayMessage(
                    "Rota alternativa mais rápida disponível: " + 
                    Math.round(alternativeSpeed * 3.6) + " km/h");
            }
        }
    }
    
    private String findBestAlternativeRoute(String currentSegment, double currentSpeed) {
        // Lógica simplificada para encontrar melhor rota alternativa
        // Em implementação real, integraria com sistema de navegação
        
        String bestRoute = null;
        double bestSpeed = currentSpeed;
        
        for (Map.Entry<String, Double> entry : trafficMetrics.entrySet()) {
            if (!entry.getKey().equals(currentSegment) && 
                entry.getValue() > bestSpeed &&
                isRouteRelevant(entry.getKey())) {
                
                bestSpeed = entry.getValue();
                bestRoute = entry.getKey();
            }
        }
        
        return bestRoute;
    }
    
    private boolean isRouteRelevant(String segmentId) {
        // Verificar se o segmento é relevante para a rota atual
        // (simplificado - sempre retorna true)
        return true;
    }
    
    private void suggestAlternativeRoute(String congestedSegment) {
        // Enviar solicitação para sistema de navegação
        getLog().infoSimTime(this, "Solicitando rota alternativa para evitar segmento {}", congestedSegment);
        
        // Em implementação real:
        // getOs().getNavigationModule().findAlternativeRoute(congestedSegment);
    }

    private void respondToRisk(RiskSituation risk) {
        // Implementar ações de resposta ao risco
        // (ex: reduzir velocidade, alterar rota, alertar motorista)
        getOs().slowDown(0.7, 2000); // Reduzir 30% da velocidade por 2 segundos
    }

    private void cleanupOldData(@Nonnull final Event event) {
        long currentTime = getOs().getSimulationTime();
        
        // Limpar mensagens encaminhadas antigas
        forwardedMessages.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > MESSAGE_TTL);
            
        // Limpar vizinhos antigos
        knownNeighbors.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().getLastUpdateTime() > 10000);
            
        // Limpar riscos antigos
        activeRisks.removeIf(risk -> 
            currentTime - risk.getTimestamp() > 15000);
            
        // Reagendar limpeza
        getOs().getEventManager().addEvent(currentTime + CLEANUP_INTERVAL, this::cleanupOldData);
    }

    private TurnIndicator determineTurnIndicator(VehicleData vehicleData) {
        // Simulação simples
        if (vehicleData.getSpeed() < 1.0) return TurnIndicator.HAZARD;
        return TurnIndicator.NONE;
    }

    private void updateNearestRsu(String rsuId, GeoPoint rsuPosition) {
        if (rsuPosition == null) return;
        
        double distance = GeoUtils.calculateDistance(
            getOs().getVehicleData().getPosition(), 
            rsuPosition
        );
        
        if (distance < nearestRsuDistance) {
            nearestRsuId = rsuId;
            nearestRsuPosition = rsuPosition;
            nearestRsuDistance = distance;
        }
    }

    private void addActiveRisk(RiskSituation risk) {
        // Remover riscos duplicados
        activeRisks.removeIf(r -> r.getId().equals(risk.getId()));
        activeRisks.add(risk);
        totalRisksReceived++;
    }

    private boolean isRiskRelevant(RiskSituation risk) {
        // Verificar se o risco é relevante para este veículo
        return risk.getAffectedVehicles().contains(getOs().getId()) || 
               GeoUtils.calculateDistance(
                   getOs().getVehicleData().getPosition(), 
                   risk.getLocation()
               ) < RISK_DISTANCE_THRESHOLD;
    }

    // ===== CLASSES INTERNAS =====
    
    public enum TurnIndicator {
        NONE, LEFT, RIGHT, HAZARD
    }
    
    public enum RiskType {
        COLLISION_RISK, SPEED_VIOLATION, PEDESTRIAN_RISK, ROAD_HAZARD
    }
    
    public static class NeighborInfo {
        private final String vehicleId;
        private final GeoPoint position;
        private final double speed;
        private final double heading;
        private final long lastUpdateTime;
        
        public NeighborInfo(String vehicleId, GeoPoint position, double speed, double heading, long lastUpdateTime) {
            this.vehicleId = vehicleId;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.lastUpdateTime = lastUpdateTime;
        }
        
        // Getters
        public String getVehicleId() { return vehicleId; }
        public GeoPoint getPosition() { return position; }
        public double getSpeed() { return speed; }
        public double getHeading() { return heading; }
        public long getLastUpdateTime() { return lastUpdateTime; }
    }
    
    public static class VehicleDataMessage extends V2xMessage {
        private final String vehicleId;
        private final GeoPoint position;
        private final double speed;
        private final double heading;
        private final long timestamp;
        private final TurnIndicator turnIndicator;
        private final String messageId;
        
        public VehicleDataMessage(String vehicleId, GeoPoint position, double speed, 
                                 double heading, long timestamp, TurnIndicator turnIndicator) {
            super(null); // O roteamento será definido no envio
            this.vehicleId = vehicleId;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.timestamp = timestamp;
            this.turnIndicator = turnIndicator;
            this.messageId = vehicleId + "_" + timestamp;
        }
        
        // Getters
        public String getVehicleId() { return vehicleId; }
        public GeoPoint getPosition() { return position; }
        public double getSpeed() { return speed; }
        public double getHeading() { return heading; }
        public long getTimestamp() { return timestamp; }
        public TurnIndicator getTurnIndicator() { return turnIndicator; }
        public String getMessageId() { return messageId; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("VehicleData[%s,%.6f,%.6f,%.1f,%.1f,%d,%s]",
                vehicleId, position.getLatitude(), position.getLongitude(), 
                speed, heading, timestamp, turnIndicator);
        }
    }
    
    public static class ForwardedVehicleMessage extends V2xMessage {
        private final String forwarderId;
        private final long forwardTimestamp;
        private final VehicleDataMessage originalMessage;
        private final int currentHop;
        
        public ForwardedVehicleMessage(String forwarderId, long forwardTimestamp, 
                                      VehicleDataMessage originalMessage, int currentHop) {
            super(null);
            this.forwarderId = forwarderId;
            this.forwardTimestamp = forwardTimestamp;
            this.originalMessage = originalMessage;
            this.currentHop = currentHop;
        }
        
        // Getters
        public String getForwarderId() { return forwarderId; }
        public long getForwardTimestamp() { return forwardTimestamp; }
        public VehicleDataMessage getOriginalMessage() { return originalMessage; }
        public int getCurrentHop() { return currentHop; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("Forwarded[%s,%d,%d,%s]",
                forwarderId, forwardTimestamp, currentHop, originalMessage);
        }
    }
    
    public static class RiskSituation {
        private final String id;
        private final RiskType type;
        private final Set<String> affectedVehicles;
        private final GeoPoint location;
        private final String description;
        private final long timestamp;
        
        public RiskSituation(String id, RiskType type, Set<String> affectedVehicles, 
                            GeoPoint location, String description) {
            this.id = id;
            this.type = type;
            this.affectedVehicles = affectedVehicles;
            this.location = location;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public String getId() { return id; }
        public RiskType getType() { return type; }
        public Set<String> getAffectedVehicles() { return affectedVehicles; }
        public GeoPoint getLocation() { return location; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class RsuRiskAlertMessage extends V2xMessage {
        private final String rsuId;
        private final GeoPoint rsuPosition;
        private final RiskSituation riskSituation;
        
        public RsuRiskAlertMessage(MessageRouting routing, String rsuId, 
                                  GeoPoint rsuPosition, RiskSituation riskSituation) {
            super(routing);
            this.rsuId = rsuId;
            this.rsuPosition = rsuPosition;
            this.riskSituation = riskSituation;
        }
        
        // Getters
        public String getRsuId() { return rsuId; }
        public GeoPoint getRsuPosition() { return rsuPosition; }
        public RiskSituation getRiskSituation() { return riskSituation; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("RiskAlert[%s,%s]", rsuId, riskSituation);
        }
    }
    
    public static class RsuRiskAlertsMessage extends V2xMessage {
        private final String rsuId;
        private final GeoPoint rsuPosition;
        private final List<RiskSituation> riskSituations;
        
        public RsuRiskAlertsMessage(MessageRouting routing, String rsuId, 
                                   GeoPoint rsuPosition, List<RiskSituation> riskSituations) {
            super(routing);
            this.rsuId = rsuId;
            this.rsuPosition = rsuPosition;
            this.riskSituations = riskSituations;
        }
        
        // Getters
        public String getRsuId() { return rsuId; }
        public GeoPoint getRsuPosition() { return rsuPosition; }
        public List<RiskSituation> getRiskSituations() { return riskSituations; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("RiskAlerts[%s,%d risks]", rsuId, riskSituations.size());
        }
    }
    
    public static class RsuTrafficInfoMessage extends V2xMessage {
        private final String rsuId;
        private final Map<String, Double> trafficMetrics; // segmentId -> metric
        
        public RsuTrafficInfoMessage(MessageRouting routing, String rsuId, 
                                    Map<String, Double> trafficMetrics) {
            super(routing);
            this.rsuId = rsuId;
            this.trafficMetrics = trafficMetrics;
        }
        
        // Getters
        public String getRsuId() { return rsuId; }
        public Map<String, Double> getTrafficMetrics() { return trafficMetrics; }
        
        @Override
        public EncodedPayload getPayload() {
            return new EncodedPayload(toString().getBytes());
        }
        
        @Override
        public String toString() {
            return String.format("TrafficInfo[%s,%d segments]", rsuId, trafficMetrics.size());
        }
    }
}
