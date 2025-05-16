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

public class RsuDataCollectorApp extends AbstractApplication<RoadSideUnitOperatingSystem> implements RoadSideUnitApplication {

    private static final long PROCESSING_INTERVAL = 1000;
    private static final int BROADCAST_RADIUS = 500;
    private static final String APP_ID = "RsuDataCollector";
    private static final String FOG_SERVER_ID = "fog_server_1";

    private final List<VehicleDataSenderApp.VehicleDataMessage> receivedMessages = new ArrayList<>();
    private final Map<String, FogComputingApp.RiskSituation> vehicleRisks = new HashMap<>();
    private List<FogComputingApp.TrafficSegmentInfo> trafficInfo = new ArrayList<>();
    private long lastTrafficInfoTime = 0;

    @Override
    public void onStartup() {
        getLog().infoSimTime(this, "RSU {} iniciou", getOs().getId());
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL, this::processAndForwardVehicleData);
    }

    @Override
    public void onShutdown() {
        getLog().infoSimTime(this, "RSU {} desligada", getOs().getId());
    }

    @Override
    public void onMessageReceived(V2xMessageReception reception) {
        V2xMessage msg = reception.getMessage();

        if (msg instanceof VehicleDataSenderApp.VehicleDataMessage vehicleMsg) {
            synchronized (receivedMessages) {
                receivedMessages.add(vehicleMsg);
            }
            getLog().infoSimTime(this, "Recebido dado de {}", vehicleMsg.getVehicleId());
            synchronized (vehicleRisks) {
                if (vehicleRisks.containsKey(vehicleMsg.getVehicleId())) {
                    sendRiskAlertToVehicle(vehicleMsg.getVehicleId(), vehicleRisks.get(vehicleMsg.getVehicleId()));
                }
            }
        } else if (msg instanceof FogComputingApp.FogRiskAlertsMessage riskMsg) {
            processRiskAlerts(riskMsg);
        } else if (msg instanceof FogComputingApp.FogTrafficInfoMessage trafficMsg) {
            processTrafficInfo(trafficMsg);
        } else if (msg instanceof VehicleMultiHopApp.ForwardedVehicleMessage fwdMsg) {
            synchronized (receivedMessages) {
                receivedMessages.add(fwdMsg.getOriginalMessage());
            }
        }
    }

    private void processAndForwardVehicleData(@Nonnull final Event event) {
        List<VehicleDataSenderApp.VehicleDataMessage> msgs;
        synchronized (receivedMessages) {
            if (receivedMessages.isEmpty()) {
                scheduleNext();
                return;
            }
            msgs = new ArrayList<>(receivedMessages);
            receivedMessages.clear();
        }

        RsuAggregatedDataMessage aggregate = new RsuAggregatedDataMessage(getOs().getId(), msgs);
        DestinationAddressContainer destination = new DestinationAddressContainer(IpResolver.getSingleton().nameToIpAddress(FOG_SERVER_ID), APP_ID);
        MessageRouting routing = MessageRouting.ipRouting(destination);
        getOs().getRouter().sendIpMessage(aggregate, routing);

        if (!trafficInfo.isEmpty() && getOs().getSimulationTime() - lastTrafficInfoTime < 10000) {
            broadcastTrafficInfo();
        }

        scheduleNext();
    }

    private void scheduleNext() {
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + PROCESSING_INTERVAL, this::processAndForwardVehicleData);
    }

    private void processRiskAlerts(FogComputingApp.FogRiskAlertsMessage msg) {
        synchronized (vehicleRisks) {
            for (var risk : msg.getRiskSituations()) {
                vehicleRisks.put(risk.getPrimaryVehicleId(), risk);
                if (risk.getSecondaryVehicleId() != null) {
                    vehicleRisks.put(risk.getSecondaryVehicleId(), risk);
                }
            }
        }
        broadcastRiskAlerts(msg.getRiskSituations());
    }

    private void processTrafficInfo(FogComputingApp.FogTrafficInfoMessage msg) {
        trafficInfo = new ArrayList<>(msg.getSegmentInfos());
        lastTrafficInfoTime = getOs().getSimulationTime();
        broadcastTrafficInfo();
    }

    private void sendRiskAlertToVehicle(String vehicleId, FogComputingApp.RiskSituation risk) {
        try {
            DestinationAddressContainer dest = new DestinationAddressContainer(IpResolver.getSingleton().nameToIpAddress(vehicleId), APP_ID);
            MessageRouting routing = MessageRouting.ipRouting(dest);
            RsuRiskAlertMessage alert = new RsuRiskAlertMessage(getOs().getId(), getOs().getSimulationTime(), risk);
            getOs().getRouter().sendIpMessage(alert, routing);
        } catch (Exception e) {
            getLog().error("Erro ao enviar alerta para {}: {}", vehicleId, e.getMessage());
        }
    }

    private void broadcastRiskAlerts(List<FogComputingApp.RiskSituation> risks) {
        if (risks.isEmpty()) return;
        RsuRiskAlertsMessage msg = new RsuRiskAlertsMessage(getOs().getId(), getOs().getSimulationTime(), new ArrayList<>(risks));
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(AdHocChannel.CCH, APP_ID, BROADCAST_RADIUS, getOs().getPosition());
        getOs().getAdHocModule().sendV2xMessage(msg, routing);
    }

    private void broadcastTrafficInfo() {
        if (trafficInfo.isEmpty()) return;
        RsuTrafficInfoMessage msg = new RsuTrafficInfoMessage(getOs().getId(), getOs().getSimulationTime(), new ArrayList<>(trafficInfo));
        MessageRouting routing = MessageRouting.createGeoBroadcastRouting(AdHocChannel.CCH, APP_ID, BROADCAST_RADIUS, getOs().getPosition());
        getOs().getAdHocModule().sendV2xMessage(msg, routing);
    }

    public static class RsuAggregatedDataMessage extends V2xMessage {
        private final String rsuId;
        private final List<VehicleDataSenderApp.VehicleDataMessage> vehicleMessages;

        public RsuAggregatedDataMessage(String id, List<VehicleDataSenderApp.VehicleDataMessage> msgs) {
            rsuId = id;
            vehicleMessages = msgs;
        }
        public String getRsuId() { return rsuId; }
        public List<VehicleDataSenderApp.VehicleDataMessage> getVehicleMessages() { return vehicleMessages; }
    }

    public static class RsuRiskAlertMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final FogComputingApp.RiskSituation riskSituation;

        public RsuRiskAlertMessage(String rsuId, long ts, FogComputingApp.RiskSituation r) {
            this.rsuId = rsuId;
            this.timestamp = ts;
            this.riskSituation = r;
        }
        public String getRsuId() { return rsuId; }
        public long getTimestamp() { return timestamp; }
        public FogComputingApp.RiskSituation getRiskSituation() { return riskSituation; }
    }

    public static class RsuRiskAlertsMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final List<FogComputingApp.RiskSituation> riskSituations;

        public RsuRiskAlertsMessage(String rsuId, long ts, List<FogComputingApp.RiskSituation> risks) {
            this.rsuId = rsuId;
            this.timestamp = ts;
            this.riskSituations = risks;
        }
        public String getRsuId() { return rsuId; }
        public long getTimestamp() { return timestamp; }
        public List<FogComputingApp.RiskSituation> getRiskSituations() { return riskSituations; }
    }

    public static class RsuTrafficInfoMessage extends V2xMessage {
        private final String rsuId;
        private final long timestamp;
        private final List<FogComputingApp.TrafficSegmentInfo> segmentInfos;

        public RsuTrafficInfoMessage(String rsuId, long ts, List<FogComputingApp.TrafficSegmentInfo> infos) {
            this.rsuId = rsuId;
            this.timestamp = ts;
            this.segmentInfos = infos;
        }
        public String getRsuId() { return rsuId; }
        public long getTimestamp() { return timestamp; }
        public List<FogComputingApp.TrafficSegmentInfo> getSegmentInfos() { return segmentInfos; }
    }
}
