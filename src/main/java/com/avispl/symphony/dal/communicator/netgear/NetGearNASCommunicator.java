package com.avispl.symphony.dal.communicator.netgear;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.TelnetCommunicator;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NetGearNASCommunicator extends TelnetCommunicator implements Monitorable, Controller {

    private static final String TELNET_ENABLE = "en";
    private static final String TELNET_SHOW_IP_MANAGEMENT = "show ip management";
//    private static final String TELNET_SHOW_HARDWARE = "show hardware"; // filter these
    private static final String TELNET_SHOW_POE = "show poe";
    private static final String TELNET_SHOW_ENVIRONMENT = "show environment";
    private static final String TELNET_SHOW_PORT_STATUS_ALL = "show port status all | exclude lag";
//    private static final String TELNET_UNSAVED_CHANGES_PROMPT = "Would you like to save them now? (y/n)";
    private static final String TELNET_STACK_RELOAD_PROMPT = "Are you sure you want to reload the stack? (y/n) ";

//    private String adapterVersion;
//    private String currentTelnetOutput = "";

    private ReentrantLock telnetOperationsLock = new ReentrantLock();

    public NetGearNASCommunicator(){
        super();
        this.setLoginPrompt("User:");
        this.setPasswordPrompt("Password:");
        this.setCommandSuccessList(Arrays.asList("\n","#","--More-- or (q)uit"," (y/n) ", "Password:")); //Would you like to save them now? (y/n)
        this.setCommandErrorList(Arrays.asList("% Invalid input detected at '^' marker."));
        this.setLoginSuccessList(Collections.singletonList(">"));
        this.setOptionHandlers(Collections.singletonList(new EchoOptionHandler(true, true, true ,false)));
//        adapterVersion = fetchAdapterVersion();

//        logger.debug("NetGear DAL Aggregator has started. Version: " + adapterVersion);
    }


    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        logger.debug("Received controllable property " + controllableProperty.getProperty() + " " + controllableProperty.getValue());

        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        telnetOperationsLock.lock();
        if(!enableTelnet()){
            return;
        }
        try {
            if (property.equals("Reload")) {
                reloadStack();
            } else if (property.startsWith("Port")) {
                String portName = property.split(" ")[1];
                switch (value){
                    case "1":
                        startupPortSequence(portName);
                        break;
                    case "0":
                        shutdownPortSequence(portName);
                        break;
                    default:
                        logger.debug("Unexpected value " + value + " for the port " + portName);
                        break;
                }

            } else {
                logger.info("Command " + property + " is not implemented. Skipping...");
            }
        } finally {
            destroyChannel();
            telnetOperationsLock.unlock();
        }
    }


    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: controllablePropertyList){
            controlProperty(controllableProperty);
        }
    }

    private boolean enableTelnet() throws Exception {
        refreshTelnet();
        String response = internalSend(TELNET_ENABLE);
        boolean telnetEnabled = requestAuthenticationRefresh(response).endsWith("#");

        if(!telnetEnabled){
            logger.error("Telnet connection to " + host + " cannot be established");
        }
        return telnetEnabled;
    }

    private void refreshTelnet() throws Exception {
        if(!isChannelConnected()){
            createChannel();
        }
    }

    private String requestAuthenticationRefresh(String response) throws Exception {
        if(response.endsWith("Password:")){
            return internalSend(getPassword());
        }
        return response;
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();
        LinkedHashMap<String, String> statisticsMap = new LinkedHashMap<>();

        try {
            telnetOperationsLock.lock();
            if(!enableTelnet()){
                return Collections.emptyList();
            }

            String ipManagementData = internalSend(TELNET_SHOW_IP_MANAGEMENT);
            String poeData = internalSend(TELNET_SHOW_POE);
            String environmentData = fetchPaginatedResponse(TELNET_SHOW_ENVIRONMENT);
            String ports = fetchPaginatedResponse(TELNET_SHOW_PORT_STATUS_ALL);

            statisticsMap.putAll(extractTelnetResponseProperties(ipManagementData, "\\.{2,}"));
            statisticsMap.putAll(extractTelnetResponseProperties(poeData, "\\.{2,}"));

            LinkedHashMap<String, String> activePortData = extractPortData(ports);
            LinkedHashMap<String, String> portControls = generatePortControls(activePortData);
            LinkedHashMap<String, String> portControlledProperties = generatePortControlledProperties(activePortData);
            LinkedHashMap<String, String> environmentStatus = extractEnvironmentStatus(environmentData);

            portControls.putAll(createControls());
            portControlledProperties.putAll(createControls());

            statisticsMap.putAll(environmentStatus);
            statisticsMap.putAll(portControlledProperties);
//            statisticsMap.put("Version", adapterVersion);

            statistics.setStatistics(statisticsMap);
            statistics.setControl(portControls);
        } finally {
            destroyChannel();
            telnetOperationsLock.unlock();
        }
        return Collections.singletonList(statistics);
    }

//    private String fetchAdapterVersion(){
//        final Properties properties = new Properties();
//        try {
//            properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("./version.properties"));
//        } catch (IOException e) {
//            logger.error("Unable to find version.properties file. Falling back to 1.0.0-SNAPSHOT");
//            return "1.0.0-SNAPSHOT";
//        }
//        return properties.getProperty("adapter.version");
//    }

    private void reloadStack() throws Exception {
        String response = internalSend("reload\ny");
        if(response.endsWith(TELNET_STACK_RELOAD_PROMPT)) {
            internalSend("y");
        }
    }

    private void shutdownPortSequence(String portName) throws Exception {
        enableTelnet();
        internalSend("config\ninterface " + portName + "\nshutdown");
    }

    private void startupPortSequence(String portName) throws Exception {
        enableTelnet();
        internalSend("config\ninterface " + portName + "\nno shutdown");
    }

    private String fetchPaginatedResponse(String command) throws Exception {
        StringBuilder telnetResponseStringBuilder = new StringBuilder();
        String response = internalSend(command);

        do {
            telnetResponseStringBuilder.append(response);
            response = internalSend("-");
        } while (!response.endsWith("#"));

        return telnetResponseStringBuilder.append(response).toString();
    }

//    private String fetchPorts() throws Exception {
//        StringBuilder environmentDataStringBuilder = new StringBuilder();
//        String response = internalSend(TELNET_SHOW_PORT_STATUS_ALL);
//
//        do {
//            environmentDataStringBuilder.append(response);
//            response = internalSend("-");
//        } while (!response.endsWith("#"));
//
//        return environmentDataStringBuilder.append(response).toString();
//    }
//
//    private String fetchEnvironmentData() throws Exception {
//        StringBuilder environmentDataStringBuilder = new StringBuilder();
//        String response = internalSend(TELNET_SHOW_ENVIRONMENT);
//
//        do {
//            environmentDataStringBuilder.append(response);
//            response = internalSend("-");
//        } while (!response.endsWith("#"));
//
//        return environmentDataStringBuilder.append(response).toString();
//    }

    private Map<String, String> createControls(){
        Map<String, String> controls = new HashMap<>();
        controls.put("Reload", "Push");
        return controls;
    }

    private LinkedHashMap<String, String> generatePortControls(Map<String, String> portsMap){
        LinkedHashMap<String, String> portControls = new LinkedHashMap<>();
        portsMap.keySet().forEach(s -> portControls.put("Port " + s, "Toggle"));

        return portControls;
    }

    private LinkedHashMap<String, String> generatePortControlledProperties(Map<String, String> portsMap){
        LinkedHashMap<String, String> portControls = new LinkedHashMap<>();
        portsMap.keySet().forEach(s -> portControls.put("Port " + s, portsMap.get(s)));

        return portControls;
    }

    private LinkedHashMap<String, String> extractEnvironmentStatus(String environmentData){
        LinkedHashMap<String, String> environmentStatus = new LinkedHashMap<>();

        LinkedHashMap<String, String> temp = new LinkedHashMap<>();
        LinkedHashMap<String, String> fans = new LinkedHashMap<>();
        LinkedHashMap<String, String> power = new LinkedHashMap<>();

        int mode = 0;
        for (String s : environmentData.split("\n")) {
            if(s.startsWith("Temperature Sensors:")){
                mode = 1;
            }
            if(s.startsWith("Fans:")){
                mode = 2;
            }
            if(s.startsWith("Power Modules:")){
                mode = 3;
            }

            if(Character.isDigit(s.charAt(0))){
                String[] sensorLine = s.replaceAll("\\r", "").split("\\s{2,}");
                switch (mode){
                    case 1:
                        temp.put("Temp. Sensor " + sensorLine[1] + ", " + sensorLine[2], sensorLine[3] + ", " + sensorLine[4]);
                        break;
                    case 2:
                        fans.put("Fan " + sensorLine[1] + ", " + sensorLine[2], sensorLine[6] + ", " + sensorLine[4] + "rps / " + sensorLine[5]);
                        break;
                    case 3:
                        power.put("Power supply " + sensorLine[1] + ", " + sensorLine[2], sensorLine[3] + ", " + sensorLine[4]);
                        break;
                    default:
                        logger.debug("No sensor data in line " + s);
                        break;
                }
            }
        }

        environmentStatus.putAll(temp);
        environmentStatus.putAll(fans);
        environmentStatus.putAll(power);

        return environmentStatus;
    }

    private LinkedHashMap<String, String> extractPortData(String portsData){
        LinkedHashMap<String, String> ports = new LinkedHashMap<>();

        Arrays.stream(portsData.split("\r")).forEach(portDataLine -> {
            if(portDataLine.matches("^(\n)*\\d\\/\\d\\/\\d.+?")) {
                String[] portDataArray = portDataLine.split("  ");
                List<String> portDataList = Arrays.stream(portDataArray).filter(portColumnData -> !portColumnData.isEmpty()).collect(Collectors.toList());

                ports.put(portDataList.get(0).replace("\n", ""), String.valueOf(portDataLine.contains(" Up ")));
            }
        });

        return ports;
    }

    private Map<String, String> extractTelnetResponseProperties(String response, String separatorPattern){
        String[] lines = response.split("\n");
        LinkedHashMap<String, String> responseMap = new LinkedHashMap<>();
        Arrays.stream(lines).forEach(s -> {
            if(Pattern.compile(separatorPattern).matcher(s).find()) {
                String[] line = s.split(separatorPattern);
                responseMap.put(line[0], line[1]);
            }
        });
        return responseMap;
    }

}