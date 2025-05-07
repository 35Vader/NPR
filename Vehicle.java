package org.myproject.applications;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageReception;
import org.eclipse.mosaic.lib.enums.AdHocChannel;
import org.eclipse.mosaic.lib.geo.GeoPoint;
import org.eclipse.mosaic.lib.geo.GeoUtils;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * Aplicação veicular para segurança rodoviária com suporte a transmissão multi-hop
 * e integração com Fog Computing para detecção e alerta de riscos.
 */
public class VehicleApp extends AbstractApplication<VehicleOperatingSystem> implements VehicleApplication {

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
    private long lastRiskCleanupTime = 0;
    
    // Informações sobre a RSU mais próxima conhecida
    private String nearestRsuId = null;
    private GeoPoint nearestRsuPosition = null;
    private double nearestRsuDistance = Double.MAX_VALUE;
    
    // Estatísticas
    private int totalMessagesSent = 0;
    private int totalMessagesForwarded = 0;
    private int totalRisksReceived = 0;

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "Aplicação Veicular inicializada no veículo {}", getOs().getId());
        
        // Agenda a primeira transmissão de mensagem
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + TRANSMISSION_INTERVAL, this::sendVehicleData);
        
        // Agenda limpeza de mensagens antigas e informações obsoletas
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + MESSAGE_TTL, this::cleanupOldData);
    }

    @Override
    public void onShutdown() {
        getLog().infoSimTime(this, "Aplicação Veicular encerrada no veículo {}", getOs().getId());
        
        // Registra estatísticas finais
        getLog().infoSimTime(this, "Estatísticas finais - Mensagens enviadas: {}, Mensagens encaminhadas: {}, Riscos recebidos: {}",
                totalMessagesSent, totalMessagesForwarded, totalRisksReceived);
    }
    
    @Override
    public void onMessageReceived(V2xMessageReception messageReception) {
        V2xMessage receivedMessage = messageReception.getMessage();
        
        // Processa mensagem direta de outro veículo
        if (receivedMessage instanceof VehicleDataMessage) {
            processVehicleMessage((VehicleDataMessage) receivedMessage);
        }
        // Processa mensagem encaminhada
        else if (receivedMessage instanceof ForwardedVehicleMessage) {
            processForwardedMessage((ForwardedVehicleMessage) receivedMessage);
        }
        // Processa mensagem de alerta de risco individual da RSU
        else if (receivedMessage instanceof RsuRiskAlertMessage) {
            processRiskAlert((RsuRiskAlertMessage) receivedMessage);
        }
        // Processa mensagem de múltiplos alertas de risco da RSU
        else if (receivedMessage instanceof RsuRiskAlertsMessage) {
            processRiskAlerts((RsuRiskAlertsMessage) receivedMessage);
        }
        // Processa mensagem de informações de tráfego da RSU
        else if (receivedMessage instanceof RsuTrafficInfoMessage) {
            processTrafficInfo((RsuTrafficInfoMessage) receivedMessage);
        }
    }

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
        checkActiveRisks();
        
        // Agendar a próxima transmissão
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
                getOs().getSimulationTime()
        );
        knownNeighbors.put(senderId, neighbor);
        
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
    private void processForwardedMessage(ForwardedVehicleMessage forwardedMessage) {
        VehicleDataMessage originalMessage = forwardedMessage.getOriginalMessage();
        String originalSenderId = originalMessage.getVehicleId();
        String forwarderId = forwardedMessage.getForwarderVehicleId();
        int currentHop = forwardedMessage.getCurrentHop();
        
        // Ignorar mensagens próprias ou já encaminhadas pelo próprio veículo
        if (originalSenderId.equals(getOs().getId()) || forwarderId.equals(getOs().getId())) {
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
                getOs().getSimulationTime()
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
    }
    
    /**
     * Processa informações de tráfego recebidas da RSU.
     */
    private void processTrafficInfo(RsuTrafficInfoMessage message) {
        List<TrafficSegmentInfo> segments = message.getSegmentInfos();
        
        getLog().infoSimTime(this, "Veículo {} recebeu informações de tráfego da RSU {} para {} segmentos",
                getOs().getId(), message.getRsuId(), segments.size());
        
        // Atualiza informações da RSU mais próxima
        updateNearestRsu(message.getRsuId(), null);
        
        // Atualiza as informações de tráfego nos segmentos
        for (TrafficSegmentInfo segment : segments) {
            trafficSegments.put(segment.getSegmentId(), segment);
        }
        
        // Poderia implementar ajustes de rota baseados nas informações de tráfego
        optimizeRoute();
    }
    
    /**
     * Determina se uma mensagem deve ser encaminhada para outros veículos.
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
            // RSU já está ao alcance, não precisa encaminhar
            return false;
        }
        
        // Implementação simples: algoritmo de probabilidade baseado na distância
        // Quanto mais distante é o veículo original, maior a probabilidade de encaminhar
        double distance = GeoUtils.calculateDistance(
                getOs().getVehicleData().getPosition(), 
                message.getPosition());
        
        // Calcula probabilidade baseada na distância (maior distância = maior probabilidade)
        double forwardProbability = Math.min(0.8, distance / (DIRECT_TRANSMISSION_RADIUS * 2));
        
        // Decisão aleatória baseada na probabilidade calculada
        return Math.random() < forwardProbability;
    }
    
    /**
     * Encaminha uma mensagem para outros veículos.
     */
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
        
        // Limpa informações de vizinhos antigos
        Iterator<Map.Entry<String, NeighborInfo>> neighborIterator = knownNeighbors.entrySet().iterator();
        while (neighborIterator.hasNext()) {
            Map.Entry<String, NeighborInfo> entry = neighborIterator.next();
            if (currentTime - entry.getValue().getLastUpdateTime() > NEIGHBOR_TIMEOUT) {
                neighborIterator.remove();
            }
        }
        
        // Limpa riscos antigos
        if (currentTime - lastRiskCleanupTime > 10000) { // A cada 10 segundos
            Iterator<RiskSituation> riskIterator = activeRisks.iterator();
            while (riskIterator.hasNext()) {
                // Remove riscos com mais de 30 segundos
                if (currentTime - riskIterator.next().getTimestamp() > 30000) {
                    riskIterator.remove();
                }
            }
            lastRiskCleanupTime = currentTime;
        }
        
        // Agenda a próxima limpeza
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + MESSAGE_TTL, this::cleanupOldData);
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
        
        for (RiskSituation risk : activeRisks) {
            // Verifica a distância do risco
            double distance = GeoUtils.calculateDistance(currentPosition, risk.getLocation());
            
            if (distance < RISK_DISTANCE_THRESHOLD) {
                getLog().infoSimTime(this, "Veículo {} detectou risco próximo a {:.1f}m: {}",
                        getOs().getId(), distance, risk.getDescription());
                
                // Poderia implementar ações específicas para riscos próximos
            }
        }
    }
    
    /**
     * Verifica se um risco é relevante para este veículo.
     */
    private boolean isRiskRelevantToThisVehicle(RiskSituation risk) {
        // Verifica se este veículo está diretamente envolvido
        if (getOs().getId().equals(risk.getPrimaryVehicleId()) || 
                getOs().getId().equals(risk.getSecondaryVehicleId())) {
            return true;
        }
        
        // Verifica se o risco está geograficamente próximo
        double distance = GeoUtils.calculateDistance(
                getOs().getVehicleData().getPosition(), 
                risk.getLocation());
        
        return distance < RISK_DISTANCE_THRESHOLD;
    }
    
    /**
     * Responde a um risco detectado.
     */
    private void respondToRisk(RiskSituation risk) {
        // Aqui seriam implementadas as ações específicas para cada tipo de risco
        
        switch (risk.getType()) {
            case COLLISION_RISK:
                // Exemplo: poderia reduzir a velocidade
                getLog().infoSimTime(this, "ALERTA CRÍTICO: Veículo {} responde a risco de colisão!", getOs().getId());
                // Em uma implementação real, poderia ajustar a velocidade do veículo
                // getOs().getVehicleControl().slowDown(0.6);
                break;
                
            case SPEED_VIOLATION:
                // Exemplo: alerta de excesso de velocidade
                getLog().infoSimTime(this, "ALERTA: Veículo {} excedeu o limite de velocidade!", getOs().getId());
                // getOs().getVehicleControl().slowDown(0.8);
                break;
                
            case PEDESTRIAN_RISK:
                // Exemplo: alerta de pedestre na via
                getLog().infoSimTime(this, "ALERTA CRÍTICO: Veículo {} - pedestre detectado!", getOs().getId());
                // getOs().getVehicleControl().slowDown(0.5);
                break;
                
            case ROAD_HAZARD:
                // Exemplo: alerta de obstáculo ou perigo na via
                getLog().infoSimTime(this, "ALERTA: Veículo {} - perigo na via detectado!", getOs().getId());
                // getOs().getVehicleControl().slowDown(0.7);
                break;
        }
    }
    
    /**
     * Otimiza a rota do veículo com base nas informações de tráfego.
     */
    private void optimizeRoute() {
        // Implementação simples para demonstração: apenas verifica se há congestionamento
        // no segmento atual
        
        // Em uma aplicação real, usaria o mapa completo da rede viária
        GeoPoint currentPosition = getOs().getVehicleData().getPosition();
        String currentSegmentId = getCurrentSegmentId(currentPosition);
        
        if (trafficSegments.containsKey(currentSegmentId)) {
            TrafficSegmentInfo segment = trafficSegments.get(currentSegmentId);
            
            if (segment.getDensity() > 10) { // Densidade alta
                getLog().infoSimTime(this, "Veículo {} detectou congestionamento no segmento atual ({} veículos)",
                        getOs().getId(), segment.getVehicleCount());
                
                // Em uma implementação real, poderia calcular e recomendar rotas alternativas
                // getOs().getRoutingControl().reroute();
            }
        }
    }
    
    /**
     * Obtém o ID do segmento atual com base na posição.
     * Simplificação: usa coordenadas arredondadas como ID de segmento.
     */
    private String getCurrentSegmentId(GeoPoint position) {
        return String.format("%.3f_%.3f", 
                Math.floor(position.getLatitude() * 1000) / 1000, 
                Math.floor(position.getLongitude() * 1000) / 1000);
    }

    // ====== CLASSES INTERNAS E MENSAGENS ======

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
    
    /**
     * Mensagem básica enviada pelos veículos (similar a um CAM).
     */
    public static class VehicleDataMessage extends V2xMessage {
        private final String vehicleId;
        private final GeoPoint position;
        private final double speed;
        private final double heading;
        private final long timestamp;
        private final TurnIndicator turnIndicator; // Novo campo

        public VehicleDataMessage(String vehicleId, GeoPoint position, double speed, 
                double heading, long timestamp, TurnIndicator turnIndicator) {
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
        public String toString() {
            return "VehicleDataMessage{" +
                    "vehicleId='" + vehicleId + '\'' +
                    ", position=" + position +
                    ", speed=" + speed +
                    ", heading=" + heading +
                    ", timestamp=" + timestamp +
                    '}';
        }

    /**
    * Mensagem encaminhada por um veículo para outros veículos (multi-hop).
    */
    public static class ForwardedVehicleMessage extends V2xMessage {
        private final String forwarderVehicleId;
        private final long timestamp;
        private final VehicleDataMessage originalMessage;
        private final int currentHop;
    
        public ForwardedVehicleMessage(String forwarderVehicleId, long timestamp, 
                VehicleDataMessage originalMessage, int currentHop) {
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
    }
