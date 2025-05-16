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

import overflowdb.schema.NeighborInfo;

import java.util.*;

/**
 * Aplicação veicular para segurança rodoviária com suporte a transmissão multi-hop
 * e integração com Fog Computing para detecção e alerta de riscos.
 */
public class VehicleApp extends AbstractApplication<VehicleOperatingSystem> implements VehicleApplication {

    public void onStartup() {
        // Implement startup logic if needed
    }

    @Override
    public void onShutdown() {
        // Implement shutdown logic if needed
    }

    // Constantes para configuração
    private static final long TRANSMISSION_INTERVAL = 1000; // Intervalo de transmissão em ms (1 segundo)
    private static final int DIRECT_TRANSMISSION_RADIUS = 300; // Raio de transmissão direta em metros
    private static final int MAX_HOPS = 3; // Número máximo de saltos para encaminhamento
    private static final long MESSAGE_TTL = 5000; // Tempo de vida da mensagem em ms
    private static final long NEIGHBOR_TIMEOUT = 10000; // Tempo para considerar um vizinho desatualizado
    private static final double RISK_DISTANCE_THRESHOLD = 150.0; // Distância para considerar um risco relevante
    private static final String APP_ID = "VehicleApp";
    
    // Mapa para rastrear mensagens já encaminhadas (para evitar duplicações)
    private final Map<String, Long> forwardedMessages = new HashMap<>();
    
    // Lista de veículos vizinhos conhecidos
    private final Map<String, NeighborInfo> knownNeighbors = new HashMap<>();
    
    // Informações de tráfego mais recentes
    private final Map<String, TrafficSegmentInfo> trafficSegments = new HashMap<>();


    // Alertas de risco recebidos
    private final List<RiskSituation> activeRisks = new ArrayList<>();

    // Stub for RiskSituation to resolve compilation error
    public static class RiskSituation {
        private final RiskType type;
        private final String primaryVehicleId;
        private final String secondaryVehicleId;
        private final String description;

        public RiskSituation(RiskType type, String primaryVehicleId, String secondaryVehicleId, String description) {
            this.type = type;
            this.primaryVehicleId = primaryVehicleId;
            this.secondaryVehicleId = secondaryVehicleId;
            this.description = description;
        }

        public RiskType getType() { return type; }
        public String getPrimaryVehicleId() { return primaryVehicleId; }
        public String getSecondaryVehicleId() { return secondaryVehicleId; }
        public String getDescription() { return description; }
    }
    private long lastRiskCleanupTime = 0;

    // Informações sobre a RSU mais próxima conhecida
    private String nearestRsuId = null;
    private GeoPoint nearestRsuPosition = null;
    private double nearestRsuDistance = Double.MAX_VALUE;
    // Estatísticas
    private int totalMessagesSent = 0;
    private int totalMessagesForwarded = 0;
    private int totalRisksReceived = 0;

    // Dummy classes for missing types
// ====== STUBS AND ENUMS FOR MISSING TYPES ======
// Simple stub for GeoPoint
public static class GeoPoint {
    private final double latitude;
    private final double longitude;
    public GeoPoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
}

// Simple stub for TrafficSegmentInfo
public static class TrafficSegmentInfo {
    private final String segmentId;
    public TrafficSegmentInfo(String segmentId) {
        this.segmentId = segmentId;
    }
    public String getSegmentId() { return segmentId; }
}

// Simple stub for TurnIndicator
public enum TurnIndicator {
    NONE, LEFT, RIGHT, HAZARD
}

// Simple stub for GeoUtils
public static class GeoUtils {
    public static double calculateDistance(GeoPoint a, GeoPoint b) {
        // Dummy implementation
        return Math.sqrt(Math.pow(a.getLatitude() - b.getLatitude(), 2) + Math.pow(a.getLongitude() - b.getLongitude(), 2));
    }
}

// Enum for risk types
public enum RiskType {
    COLLISION_RISK,
    SPEED_VIOLATION,
    PEDESTRIAN_RISK,
    ROAD_HAZARD
}

// Dummy message classes for RSU messages
public static class RsuRiskAlertMessage extends V2xMessage {
    private final String rsuId;
    private final RiskSituation riskSituation;

    public RsuRiskAlertMessage(String rsuId, RiskSituation riskSituation) {
        super(null); // Pass appropriate arguments as required by V2xMessage's constructor
        this.rsuId = rsuId;
        this.riskSituation = riskSituation;
    }

    public String getRsuId() { return rsuId; }
    public RiskSituation getRiskSituation() { return riskSituation; }

    @Override
    public EncodedPayload getPayload() { return riskSituation; }
}

public static class RsuRiskAlertsMessage extends V2xMessage {
    private final String rsuId;
    private final java.util.List<RiskSituation> riskSituations;

    public RsuRiskAlertsMessage(String rsuId, java.util.List<RiskSituation> riskSituations) {
        super(null); // Pass appropriate argument as required by V2xMessage's constructor
        this.rsuId = rsuId;
        this.riskSituations = riskSituations;
    }

    public String getRsuId() { return rsuId; }
    public java.util.List<RiskSituation> getRiskSituations() { return riskSituations; }

    @Override
    public EncodedPayload getPayload() { return riskSituations; }
}

// Dummy message class for traffic info
public static class RsuTrafficInfoMessage extends V2xMessage {
    private final String rsuId;
    private final java.util.List<TrafficSegmentInfo> segmentInfos;

    public RsuTrafficInfoMessage(String rsuId, java.util.List<TrafficSegmentInfo> segmentInfos) {
        super(new EncodedPayload(segmentInfos)); // Pass the payload to the superclass constructor
        this.rsuId = rsuId;
        this.segmentInfos = segmentInfos;
    }

    public String getRsuId() { return rsuId; }
    public java.util.List<TrafficSegmentInfo> getSegmentInfos() { return segmentInfos; }

    @Override
    public EncodedPayload getPayload() { return segmentInfos; }

    /**
     * Envia os dados do veículo para RSUs e outros veículos próximos.
     */
    private void sendVehicleData(@Nonnull final Event event) {
        // Obter os dados atuais do veículo
        VehicleData vehicleData = getOs().getVehicleData();
        GeoPoint position = vehicleData.getPosition();
        double speed = vehicleData.getSpeed();
        double heading = vehicleData.getHeading();
        TurnIndicator turnIndicator = determineTurnIndicator(vehicleData);

        // Criar a mensagem com os dados do veículo (equivalente a CAM - Cooperative Awareness Message)
        VehicleDataMessage message = new VehicleDataMessage(
                getOs().getId(),
                position,
                speed,
                heading,
                getOs().getSimulationTime(),
                turnIndicator
        );
        
        // Configurar o routing da mensagem (broadcast direto para RSUs e outros veículos)
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
                AdHocChannel.CCH,
                APP_ID,
                DIRECT_TRANSMISSION_RADIUS,
                position
        );
        
        // Transmitir a mensagem
        getOs().getAdHocModule().sendV2xMessage(message, routing);
        totalMessagesSent++;
        
       getLog().infoSimTime(this, "Veículo {} enviou dados: posição=({}, {}), velocidade={} m/s, sentido={}°, pisca={}",
            getOs().getId(), position.getLatitude(), position.getLongitude(), speed, heading, turnIndicator);
    }        
    // Verifica se há alertas de risco ativos para este veículo
    // Removed duplicate or unnecessary call to checkActiveRisks()
   
    // Agendar a próxima transmissão
    {
    getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRANSMISSION_INTERVAL, this::sendVehicleData);
    }
    
    
    /**
     * Processa mensagem direta recebida de outro veículo.
     */
    private void processVehicleMessage(VehicleDataMessage message) {
        String senderId = message.getVehicleId();
        GeoPoint senderPosition = message.getPosition();
        
        // Ignorar mensagens próprias
        if (senderId.equals(getOs().getId())) {
            return;
        }
        
        // Atualiza informações do vizinho
        NeighborInfo neighbor = new NeighborInfo(
                senderId,
                senderPosition,
                message.getSpeed(),
                message.getHeading(),
                this.getOs().getSimulationTime()
        );
        this.knownNeighbors.put(senderId, neighbor);
        
        // Calcula distância até o remetente
        double distance = GeoUtils.calculateDistance(getOs().getVehicleData().getPosition(), senderPosition);
        
        getLog().infoSimTime(this, "Veículo {} recebeu dados do veículo {} a {:.1f}m de distância",
                getOs().getId(), senderId, distance);
        
        // Verifica se a mensagem deve ser encaminhada (multi-hop)
        if (shouldForwardMessage(message, 1)) {
            forwardMessage(message, 1);
        }
    }

    /**
     * Processa mensagem encaminhada recebida.
     */
    // ====== CLASSES INTERNAS E MENSAGENS ======

    /**
     * Classe para armazenar informações sobre veículos vizinhos.
     */
    private static class NeighborInfo {
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

        public String getVehicleId() {
            return vehicleId;
        }

        public GeoPoint getPosition() {
            return position;
        }

        public double getSpeed() {
            return speed;
        }

        public double getHeading() {
            return heading;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }
    public static class VehicleDataMessage extends V2xMessage {
        private final String vehicleId;
        private final GeoPoint position;
        private final double speed;
        private final double heading;
        private final long timestamp;
        private final TurnIndicator turnIndicator; // Novo campo
    
        public VehicleDataMessage(String vehicleId, GeoPoint position, double speed, double heading, long timestamp, TurnIndicator turnIndicator) {
            super(new EncodedPayload(vehicleId + "," + position + "," + speed + "," + heading + "," + timestamp + "," + turnIndicator));
            this.vehicleId = vehicleId;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.timestamp = timestamp;
            this.turnIndicator = turnIndicator;
        }

        public TurnIndicator getTurnIndicator() {
            return turnIndicator;
        }

        public String getVehicleId() {
            return vehicleId;
        }

        public GeoPoint getPosition() {
            return position;
        }

        public double getSpeed() {
            return speed;
        }

        public double getHeading() {
            return heading;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public Object getPayload() {
            return null;
        }

        @Override
        public String toString() {
            return "VehicleDataMessage{" +
                    "vehicleId='" + vehicleId + '\'' +
                    ", position=" + position +
                    ", speed=" + speed +
                    ", heading=" + heading +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    public static class ForwardedVehicleMessage extends V2xMessage {
        private final String forwarderVehicleId;
        private final long timestamp;
        private final VehicleDataMessage originalMessage;
        private final int currentHop;
    
        public ForwardedVehicleMessage(String forwarderVehicleId, long timestamp,
                VehicleDataMessage originalMessage, int currentHop) {
            super(new EncodedPayload(forwarderVehicleId + "," + timestamp + "," + currentHop + "," + originalMessage));
            this.forwarderVehicleId = forwarderVehicleId;
            this.timestamp = timestamp;
            this.originalMessage = originalMessage;
            this.currentHop = currentHop;
        }

        public String getForwarderVehicleId() {
            return forwarderVehicleId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public VehicleDataMessage getOriginalMessage() {
            return originalMessage;
        }

        public int getCurrentHop() {
            return currentHop;
        }


        @Override
        public EncodedPayload getPayload() {
            // You may need to encode originalMessage as an EncodedPayload if required by your framework
            // For now, return null or implement encoding as needed
            return null;
        }
    }

    private void processForwardedMessage(ForwardedVehicleMessage forwardedMessage) {
        VehicleDataMessage originalMessage = forwardedMessage.getOriginalMessage();
        String originalSenderId = originalMessage.getVehicleId();
        String forwarderId = forwardedMessage.getForwarderVehicleId();
        int currentHop = forwardedMessage.getCurrentHop();
    
        // Ignorar mensagens próprias ou já encaminhadas pelo próprio veículo
        if (originalSenderId.equals(this.getOs().getId()) || forwarderId.equals(this.getOs().getId())) {
            return;
        }

        // Gera um ID único para esta mensagem
        String messageId = originalSenderId + "_" + forwardedMessage.getTimestamp();

        // Verifica se já processamos esta mensagem
        if (forwardedMessages.containsKey(messageId)) {
            return;
        }

        // Registra que recebemos esta mensagem
        forwardedMessages.put(messageId, getOs().getSimulationTime());

        getLog().infoSimTime(this, "Veículo {} recebeu mensagem encaminhada do veículo {} no salto {}, originalmente de {}",
                getOs().getId(), forwarderId, currentHop, originalSenderId);

        // Atualiza informações do vizinho original
        NeighborInfo neighbor = new NeighborInfo(
                originalSenderId,
                originalMessage.getPosition(),
                originalMessage.getSpeed(),
                originalMessage.getHeading(),
                this.getOs().getSimulationTime()
        );
        knownNeighbors.put(originalSenderId, neighbor);

        // Verifica se deve continuar encaminhando
        if (shouldForwardMessage(originalMessage, currentHop)) {
            forwardMessage(originalMessage, currentHop);
        }
    }
    
    /**
     * Processa alerta de risco individual recebido da RSU.
     */
    private void processRiskAlert(RsuRiskAlertMessage message) {
        RiskSituation risk = message.getRiskSituation();
        
        getLog().infoSimTime(this, "Veículo {} recebeu alerta de risco da RSU {}: {}",
                getOs().getId(), message.getRsuId(), risk.getDescription());
        
        // Atualiza informações da RSU mais próxima
        updateNearestRsu(message.getRsuId(), null);
        
        // Adiciona o risco à lista de riscos ativos
        addActiveRisk(risk);
        
        // Responde ao risco recebido
        respondToRisk(risk);
    }
    
    /**
     * Processa múltiplos alertas de risco recebidos da RSU.
     */
    private void processRiskAlerts(RsuRiskAlertsMessage message) {
        List<RiskSituation> risks = message.getRiskSituations();
        
        getLog().infoSimTime(this, "Veículo {} recebeu {} alertas de risco da RSU {}",
                getOs().getId(), risks.size(), message.getRsuId());
        
        // Atualiza informações da RSU mais próxima
        updateNearestRsu(message.getRsuId(), null);
        
        // Analisa os riscos relevantes para este veículo
        boolean vehicleAtRisk = false;
        for (RiskSituation risk : risks) {
            // Adiciona à lista de riscos ativos
            addActiveRisk(risk);
            
            // Verifica se este veículo está envolvido no risco
            if (getOs().getId().equals(risk.getPrimaryVehicleId()) || 
                    getOs().getId().equals(risk.getSecondaryVehicleId())) {
                vehicleAtRisk = true;
                getLog().infoSimTime(this, "Veículo {} está em situação de risco: {}",
                        getOs().getId(), risk.getDescription());
                
                // Responde ao risco recebido
                respondToRisk(risk);
            }
        }
        
        // Se não houver riscos específicos para este veículo, verifica se há riscos próximos
        if (!vehicleAtRisk) {
            checkNearbyRisks();
        }
    private boolean shouldForwardMessage(VehicleDataMessage message, int currentHop) {
        // Verifica se atingiu o limite máximo de saltos
        if (currentHop >= MAX_HOPS) {
            return false;
        }

        // Verifica se a mensagem está vindo do próprio veículo
        if (message.getVehicleId().equals(getOs().getId())) {
            return false;
        }

        // Verifica se a mensagem é recente o suficiente para ser encaminhada
        long messageAge = getOs().getSimulationTime() - message.getTimestamp();
        if (messageAge > MESSAGE_TTL) {
            return false;
        }

        // Verifica se existe uma RSU próxima que já receberia a mensagem diretamente
        if (this.nearestRsuId != null && this.nearestRsuDistance < DIRECT_TRANSMISSION_RADIUS) {
            return false;
        }

        return true;
    }
    private void forwardMessage(VehicleDataMessage originalMessage, int currentHop) {
     */
    private boolean shouldForwardMessage(VehicleDataMessage message, int currentHop) {
        // Verifica se atingiu o limite máximo de saltos
        if (currentHop >= MAX_HOPS) {
            return false;
        }
        
        // Verifica se a mensagem está vindo do próprio veículo
        if (message.getVehicleId().equals(getOs().getId())) {
            return false;
        }
        
        // Verifica se a mensagem é recente o suficiente para ser encaminhada
        long messageAge = getOs().getSimulationTime() - message.getTimestamp();
        if (messageAge > MESSAGE_TTL) {
            return false;
        }
        
        // Verifica se existe uma RSU próxima que já receberia a mensagem diretamente
        if (nearestRsuId != null && nearestRsuDistance < DIRECT_TRANSMISSION_RADIUS) {
    private TurnIndicator determineTurnIndicator(VehicleData vehicleData) {
        //simulação de paragem de emergência
        // If getBrakeLight() or getLaneChangeIntention() are not available, always return NONE
        return TurnIndicator.NONE;
    }
    private void forwardMessage(VehicleDataMessage originalMessage, int currentHop) {
        // Cria uma mensagem encaminhada
        ForwardedVehicleMessage forwardedMessage = new ForwardedVehicleMessage(
                getOs().getId(),
                getOs().getSimulationTime(),
                originalMessage,
                currentHop + 1
        );
        
        // Configurar o roteamento para broadcast geográfico
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(
                AdHocChannel.CCH,
                APP_ID,
                DIRECT_TRANSMISSION_RADIUS,
                getOs().getVehicleData().getPosition()
        );
        
        // Transmitir a mensagem
        getOs().getAdHocModule().sendV2xMessage(forwardedMessage, routing);
        totalMessagesForwarded++;
        
        // Registra que esta mensagem foi encaminhada
        String messageId = originalMessage.getVehicleId() + "_" + getOs().getSimulationTime();
        forwardedMessages.put(messageId, getOs().getSimulationTime());
        
        getLog().infoSimTime(this, "Veículo {} encaminhou mensagem do veículo {} no salto {}",
                getOs().getId(), originalMessage.getVehicleId(), currentHop + 1);
    }

    /**
     * Limpa mensagens antigas e outros dados obsoletos.
     */
    private void cleanupOldData(@Nonnull final Event event) {
        long currentTime = getOs().getSimulationTime();
        
        // Limpa mensagens encaminhadas antigas
        Iterator<Map.Entry<String, Long>> messageIterator = forwardedMessages.entrySet().iterator();
        while (messageIterator.hasNext()) {
            Map.Entry<String, Long> entry = messageIterator.next();
            if (currentTime - entry.getValue() > MESSAGE_TTL) {
                messageIterator.remove();
            }
        }
    /**
     * Determina o estado dos piscas com base no comportamento do veículo.
     */
    private TurnIndicator determineTurnIndicator(VehicleData vehicleData) {
        //simulação de paragem de emergência
        try {
            if (vehicleData.getBrakeLight() && vehicleData.getSpeed() < 1.0) {
                return TurnIndicator.HAZARD;
            }
            double angularChange = vehicleData.getLaneChangeIntention();
            if (angularChange > 0.5) {
                return TurnIndicator.RIGHT;
            } else if (angularChange < -0.5) {
                return TurnIndicator.LEFT;
            }
        } catch (Exception e) {
            // API pode não estar disponível, ignore
        }
        return TurnIndicator.NONE;
    }
        RsuTrafficInfoMessage().getEventManager().addEvent(getOs().getSimulationTime() + MESSAGE_TTL, this::cleanupOldData);
    }

    /**
     * Determina o estado dos piscas com base no comportamento do veículo.
    */
    private TurnIndicator determineTurnIndicator(VehicleData vehicleData) {
        //simulação de paragem de emergência
        if (vehicleData.getBrakeLight() && vehicleData.getSpeed() < 1.0) {
            return TurnIndicator.HAZARD;
        }
    
        // Tenta detectar intenção de conversão baseada na trajetória
        try {
            double angularChange = vehicleData.getLaneChangeIntention();
            if (angularChange > 0.5) {
                return TurnIndicator.RIGHT;
            } else if (angularChange < -0.5) {
                return TurnIndicator.LEFT;
            }
        } catch (Exception e) {
            // API pode não estar disponível, ignore
        }
    
        return TurnIndicator.NONE;
    }

    /**
     * Atualiza as informações da RSU mais próxima.
     */
    private void updateNearestRsu(String rsuId, GeoPoint rsuPosition) {
        // Se não temos a posição da RSU, tentamos estimar
        if (rsuPosition == null) {
            // Aproximação: a RSU está aproximadamente na nossa posição
            rsuPosition = getOs().getVehicleData().getPosition();
        }
        
        double distance = GeoUtils.calculateDistance(getOs().getVehicleData().getPosition(), rsuPosition);
        
        // Atualiza se for a RSU mais próxima até agora
        if (nearestRsuId == null || distance < nearestRsuDistance) {
            nearestRsuId = rsuId;
            nearestRsuPosition = rsuPosition;
            nearestRsuDistance = distance;
        }
    }
    
    /**
     * Adiciona um risco à lista de riscos ativos.
     */
    private void addActiveRisk(RiskSituation risk) {
        // Verifica se já temos este risco
        for (RiskSituation existingRisk : activeRisks) {
            if (isSameRisk(existingRisk, risk)) {
                // Substitui pelo mais recente
                activeRisks.remove(existingRisk);
                activeRisks.add(risk);
                return;
            }
        }
        
        // Adiciona novo risco
        activeRisks.add(risk);
        totalRisksReceived++;
    }
    
    /**
     * Verifica se dois riscos são essencialmente o mesmo.
     */
    private boolean isSameRisk(RiskSituation risk1, RiskSituation risk2) {
        // Mesmos veículos envolvidos e mesmo tipo de risco
        return risk1.getType() == risk2.getType() &&
               Objects.equals(risk1.getPrimaryVehicleId(), risk2.getPrimaryVehicleId()) &&
               Objects.equals(risk1.getSecondaryVehicleId(), risk2.getSecondaryVehicleId());
    }
    
    // Ensure only one implementation of checkActiveRisks() exists
        /**
         * Verifica os riscos ativos e toma ações apropriadas.
         */
        private void checkActiveRisks() {
            if (activeRisks.isEmpty()) {
                return;
            }
            
            // Filtrar riscos relevantes para este veículo
            for (RiskSituation risk : activeRisks) {
                if (isRiskRelevantToThisVehicle(risk)) {
                    respondToRisk(risk);
                }
            }
        }
    
    /**
     * Verifica se há riscos próximos ao veículo.
     */
    private void checkNearbyRisks() {
        GeoPoint currentPosition = getOs().getVehicleData().getPosition();
    // ====== CLASSES INTERNAS E MENSAGENS ======

    /**
     * Classe para armazenar informações sobre veículos vizinhos.
     */
    private static class NeighborInfo {
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

        public String getVehicleId() {
            return vehicleId;
        }

        public GeoPoint getPosition() {
            return position;
        }

        public double getSpeed() {
            return speed;
        }

        public double getHeading() {
            return heading;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }
    public static class VehicleDataMessage extends V2xMessage {
        private final String vehicleId;
        private final GeoPoint position;
        private final double speed;
        private final double heading;
        private final long timestamp;
        private final TurnIndicator turnIndicator; // Novo campo

        public VehicleDataMessage(String vehicleId, GeoPoint position, double speed, double heading, long timestamp, TurnIndicator turnIndicator) {
            super(null); // Pass a suitable EncodedPayload or required argument for V2xMessage's constructor
            this.vehicleId = vehicleId;
            this.position = position;
            this.speed = speed;
            this.heading = heading;
            this.timestamp = timestamp;
            this.turnIndicator = turnIndicator;
        }

        public TurnIndicator getTurnIndicator() {
            return turnIndicator;
        }

        public String getVehicleId() {
            return vehicleId;
        }

        public GeoPoint getPosition() {
            return position;
        }

        public double getSpeed() {
            return speed;
        }

        public double getHeading() {
            return heading;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public Object getPayload() {
            return null;
        }

        @Override
        public String toString() {
            return "VehicleDataMessage{" +
                    "vehicleId='" + vehicleId + '\'' +
                    ", position=" + position +
                    ", speed=" + speed +
                    ", heading=" + heading +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    public static class ForwardedVehicleMessage extends V2xMessage {
        private final String forwarderVehicleId;
        private final long timestamp;
        private final VehicleDataMessage originalMessage;
        private final int currentHop;

        public ForwardedVehicleMessage(String forwarderVehicleId, long timestamp,
                VehicleDataMessage originalMessage, int currentHop) {
            super(null); // Pass a suitable EncodedPayload or required argument for V2xMessage's constructor
            this.forwarderVehicleId = forwarderVehicleId;
            this.timestamp = timestamp;
            this.originalMessage = originalMessage;
            this.currentHop = currentHop;
        }

        public String getForwarderVehicleId() {
            return forwarderVehicleId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public VehicleDataMessage getOriginalMessage() {
            return originalMessage;
        }

        public int getCurrentHop() {
            return currentHop;
        }


        @Override
        public EncodedPayload getPayload() {
            // You may need to encode originalMessage as an EncodedPayload if required by your framework
            // For now, return null or implement encoding as needed
            return null;
        }
    }
        public VehicleDataMessage getOriginalMessage() {
    // Implement required interface methods
    @Override
    public void processEvent(Event event) {
        // No-op or implement as needed
    }
}

// Implement required interface methods in VehicleApp
@Override
public void onVehicleUpdated(VehicleData previousData, VehicleData currentData) {
    // No-op or implement as needed
}

@Override
public void processEvent(Event arg0) throws Exception {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'processEvent'");
}

}
