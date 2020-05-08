/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.netgear;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.TelnetCommunicator;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NetGearNASCommunicator extends TelnetCommunicator implements Monitorable, Controller {

    private static final String TELNET_UNSAVED_CHANGES_PROMPT = "Would you like to save them now? (y/n) ";
    private static final String TELNET_STACK_RELOAD_PROMPT = "Are you sure you want to reload the stack? (y/n) ";
    private static final long reloadGracePeriod = 180000;
    private static final int controlTelnetTimeout = 3000;
    private static final int statisticsTelnetTimeout = 30000;

    private volatile boolean isOccupiedByControl = false;

    private final ReentrantLock telnetOperationsLock = new ReentrantLock();
    private ScheduledExecutorService statisticsExclusionScheduler = Executors.newScheduledThreadPool(1);
    private ExtendedStatistics localStatistics;

    public NetGearNASCommunicator(){
        super();
        this.setLoginPrompt("User:");
        this.setPasswordPrompt("Password:");
        this.setCommandSuccessList(Arrays.asList("\n","#","--More-- or (q)uit", "Config file 'startup-config' created successfully .", "Configuration Saved!", TELNET_UNSAVED_CHANGES_PROMPT, TELNET_STACK_RELOAD_PROMPT, "Password:")); //Would you like to save them now? (y/n)
        this.setCommandErrorList(Arrays.asList("% Invalid input detected at '^' marker."));
        this.setLoginSuccessList(Collections.singletonList(">"));
        this.setOptionHandlers(Collections.singletonList(new EchoOptionHandler(true, true, true ,false)));
    }

    /**
     * Controls NetGear switch by implementing Stack Reload functionality and PoE ports startup/shutdown
     */
    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        telnetOperationsLock.lock();
        try {
            this.timeout = controlTelnetTimeout;
            if(!enableTelnet()){
                return;
            }

            if (property.equals("Reload")) {
                reloadStack();
            } else if (property.startsWith("Port")) {
                String portName = property.replaceAll("[^\\d.^\\/]", "");;
                switch (value){
                    case "1":
                        startupPortSequence(portName);
                        break;
                    case "0":
                        shutdownPortSequence(portName);
                        break;
                    default:
                        logger.warn("NetGearCommunicator: Unexpected control value " + value + " for the port " + portName);
                        break;
                }
            } else {
                logger.info("NetGearCommunicator: Command " + property + " is not implemented. Skipping.");
            }
        } finally {
            this.timeout = statisticsTelnetTimeout;
            telnetOperationsLock.unlock();
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("NetGearCommunicator: Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: controllablePropertyList){
            controlProperty(controllableProperty);
        }
    }

    /**
     * Enters the "enabled" mode, creates a channel if needed
     *
     * @return boolean value, indicating whether telnet has entered the enabled mode successfully
     */
    private boolean enableTelnet() throws Exception {
        refreshTelnet();
        String response = internalSend("en");
        boolean telnetEnabled = authenticationRefresh(response).endsWith("#");

        if(!telnetEnabled){
            logger.error("NetGearCommunicator: Telnet connection to " + host + " cannot be established");
        }
        return telnetEnabled;
    }

    /**
     * Refreshes telnet status, creates a channel if no channel is connected at the moment
     */
    private void refreshTelnet() throws Exception {
        if(!isChannelConnected()){
            connect();
        }
    }

    /**
     * Refreshes authentication with a given password, based on a previous response (since we may receive a password
     * prompt outside of the "login" sequence, but just because we are requesting specific protected pieces of the
     * data/functionality)
     *
     * @param response telnet response to check for the prompt before sending the password
     * @return either the password input response or the input string.
     */
    private String authenticationRefresh(String response) throws Exception {
        if(response.endsWith("Password:")){
            return internalSend(getPassword());
        }
        return response;
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();

        if(isOccupiedByControl && localStatistics != null){
            if(logger.isInfoEnabled()) {
                logger.info("NetGearCommunicator: Device is in reboot or occupied. Skipping statistics refresh call.");
            }
            statistics.setStatistics(localStatistics.getStatistics());
            statistics.setControllableProperties(localStatistics.getControllableProperties());
            return Collections.singletonList(statistics);
        }

        LinkedHashMap<String, String> statisticsMap = new LinkedHashMap<>();

        telnetOperationsLock.lock();
        try {
            if(!enableTelnet()){
                throw new RuntimeException("Unable to establish a telnet communication session");
            }

            String ipManagementData = internalSend("show ip management");
            String poeData = internalSend("show poe");
            String interfaceSwitchport = internalSend("show interface switchport");
            String environmentData = fetchPaginatedResponse("show environment");
            String interfacesPacketData = fetchPaginatedResponse("show interface ethernet all | exclude lag");
            String ports = fetchPaginatedResponse("show port status all | exclude lag");

            statisticsMap.putAll(extractTelnetResponseProperties(ipManagementData, "\\.{2,}"));
            statisticsMap.putAll(extractTelnetResponseProperties(poeData, "\\.{2,}"));

            Map<String, String> activePortData = new HashMap<>();
            extractPortStatus(activePortData, ports);

            Map<String, String> packetsData = new HashMap<>();
            extractGeneralPacketsData(packetsData, interfaceSwitchport);

            Map<String, String> interfacesData = new HashMap<>();
            extractPortStatistics(interfacesData, interfacesPacketData);

            Map<String, String> portControlledProperties = new HashMap<>();
            generatePortControlledProperties(portControlledProperties, activePortData);

            Map<String, String> environmentStatus = new HashMap<>();
            extractEnvironmentStatus(environmentStatus, environmentData);

            portControlledProperties.put("Reload", "");

            statisticsMap.putAll(environmentStatus);
            statisticsMap.putAll(portControlledProperties);
            statisticsMap.putAll(interfacesData);
            statisticsMap.putAll(packetsData);
            statistics.setStatistics(statisticsMap);

            statistics.setControllableProperties(createAdvancedControls(activePortData));
        } finally {
            telnetOperationsLock.unlock();
            disconnect();
        }
        localStatistics = statistics;

        return Collections.singletonList(statistics);
    }

    /**
     * Extracts general packets statistics for the switch.
     *
     * @param interfaceSwitchport telnet response to extract values from
     * @param packetsData statistics map to extract values to
     */
    private void extractGeneralPacketsData(Map<String, String> packetsData, String interfaceSwitchport) {
        Map<String, String> result = extractTelnetResponseProperties(interfaceSwitchport, "\\.{2,}");
        packetsData.put("TotalPacketsStatistics#Total Packets Received Without Errors", result.get("Packets Received Without Error"));
        packetsData.put("TotalPacketsStatistics#Total Packets Transmitted Without Errors", result.get("Packets Transmitted Without Errors"));
        packetsData.put("TotalPacketsStatistics#Total Packets Received With Errors", result.get("Packets Received With Error"));
        packetsData.put("TotalPacketsStatistics#Total Packets Transmitted With Errors", result.get("Transmit Packet Errors"));
        packetsData.put("TotalPacketsStatistics#Time Since Counters Last Cleared", result.get("Time Since Counters Last Cleared"));
    }

    /**
     * Reloads the entire NetGear stack.
     */
    private void reloadStack() {
        try {
            String response = internalSend("reload");
            if (response.endsWith(TELNET_UNSAVED_CHANGES_PROMPT)) {
                internalSend("y\nreload\ny");
            } else if (response.endsWith(TELNET_STACK_RELOAD_PROMPT)) {
                internalSend("y");
            }
        } catch (Exception e) {
            if(logger.isErrorEnabled()) {
                logger.error("NetGearCommunicator: Error while reloading stack: " + e.getMessage());
            }
        } finally {
            if(logger.isInfoEnabled()) {
                logger.info("NetGearCommunicator: Exited the reload with timeout: " + this.timeout);
            }
        }
    }

    /**
     * Shutdown PoE port
     * @param portName name of the port to shutdown
     */
    private void shutdownPortSequence(String portName) throws Exception {
        portControlWarmup();
        enableTelnet();
        internalSend("config\ninterface " + portName + "\nshutdown");
        localStatistics.getStatistics().put("Port Controls#Port " + portName, "false");
        portControlCooldown();
    }

    /**
     * Startup PoE port
     * @param portName name of the port to start up
     */
    private void startupPortSequence(String portName) throws Exception {
        portControlWarmup();
        enableTelnet();
        internalSend("config\ninterface " + portName + "\nno shutdown");
        localStatistics.getStatistics().put("Port Controls#Port " + portName, "true");
        portControlCooldown();
    }


    /**
     * Part of a port warmup-cooldown strategy. Schedules changing the adapter occupation status to "non occupied".
     */
    private void portControlCooldown(){
        statisticsExclusionScheduler.schedule(() -> isOccupiedByControl = false, 3000, TimeUnit.MILLISECONDS);
    }

    /**
     * The switches that have that many controls share the similar issue - each control action by design will
     * trigger a statistic retrieval. In order to stack multiple controls together - warmup/cooldown is applied to
     * port controls. What this means is that whenever the control is toggled - first it lets Adapter know that
     * it's currently occupied by a control, and the scheduler is initialized. After the control action is finished -
     * port cooldown is initiated. It schedules changing occupation status back to "non occupied" after 3 seconds.
     * If another control is triggered during that period - it resets the occupied action change until finished.
     * This way, all the subsequent control actions will be stacked together without immediate statistics refresh
     * after each of them.
     */
    private void portControlWarmup(){
        if(statisticsExclusionScheduler != null) {
            statisticsExclusionScheduler.shutdownNow();
        }
        isOccupiedByControl = true;
        statisticsExclusionScheduler = Executors.newScheduledThreadPool(1);
    }

    /**
     * Collects the paginated telnet response.
     * Sometimes the response may have a paginated responce which requires sending input programmatically to
     * get the next page.
     *
     * @param command command to collect a response for
     */
    private String fetchPaginatedResponse(String command) throws Exception {
        if(!enableTelnet()){
            return "";
        }

        StringBuilder telnetResponseStringBuilder = new StringBuilder();
        String response = internalSend(command);

        do {
            telnetResponseStringBuilder.append(response);
            response = internalSend("-");
        } while (!response.endsWith("#"));
        return telnetResponseStringBuilder.append(response).toString();
    }

    /**
     * Since {link#AdvancedControllableProperty} were presented in Symphony 4.9 - we need to create them
     * properly. This method creates a Reload button control and a set of Port controls, that are represented
     * by switch controls.
     *
     * @param portsMap map containing all the controls the switch has.
     */
    private List<AdvancedControllableProperty> createAdvancedControls(Map<String, String> portsMap){
        List<AdvancedControllableProperty> portControls = new ArrayList<>();

        AdvancedControllableProperty reloadButton = new AdvancedControllableProperty();
        AdvancedControllableProperty.Button button = new AdvancedControllableProperty.Button();
        button.setGracePeriod(reloadGracePeriod);
        button.setLabel("Reload");
        button.setLabelPressed("Reloading");
        reloadButton.setType(button);
        reloadButton.setName("Reload");

        portsMap.keySet().forEach(s ->
        {
            AdvancedControllableProperty.Switch portSwitch = new AdvancedControllableProperty.Switch();
            portSwitch.setLabelOn("On");
            portSwitch.setLabelOff("Off");
            AdvancedControllableProperty portControl = new AdvancedControllableProperty("Port Controls#Port " + s, new Date(), portSwitch, portsMap.get(s));
            portControls.add(portControl);
        });
        portControls.add(reloadButton);
        return portControls;
    }

    /**
     * In order to have controllable properties displayed on UI - we need to create a set of matching statistics
     * entries in a statistics map. This metod is running through all the ports and created entries that would match
     * (logically) the result of createAdvancedControls() method
     *
     * @param portControls statistics map to add entries to
     * @param portsMap map containing ports information (Name/Status)
     */
    private void generatePortControlledProperties(Map<String, String> portControls, Map<String, String> portsMap){
        portsMap.keySet().forEach(s -> portControls.put("Port Controls#Port " + s, portsMap.get(s)));
    }

    /**
     * Extract status of a NetGear environment, basically a set of telemetry - temperature, fans. Also contains information
     * about the power supply.
     *
     * @param environmentData telnet response containing all the environment data
     * @param environmentStatus statistics map to extract values to
     */
    private void extractEnvironmentStatus(Map<String, String> environmentStatus, String environmentData){
        Map<String, String> temp = new HashMap<>();
        Map<String, String> fans = new HashMap<>();
        Map<String, String> power = new HashMap<>();

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
                        temp.put("Temperature Sensors#Temp. Sensor " + sensorLine[1] + ", " + sensorLine[2], sensorLine[3] + ", " + sensorLine[4]);
                        break;
                    case 2:
                        fans.put("Fans#Fan " + sensorLine[1] + ", " + sensorLine[2], sensorLine[6] + ", " + sensorLine[4] + "rps / " + sensorLine[5]);
                        break;
                    case 3:
                        power.put("Power Modules#Power supply " + sensorLine[1] + ", " + sensorLine[2], sensorLine[3] + ", " + sensorLine[4]);
                        break;
                    default:
                        logger.info("No sensor data in line " + s);
                        break;
                }
            }
        }

        environmentStatus.putAll(temp);
        environmentStatus.putAll(fans);
        environmentStatus.putAll(power);
    }

    /**
     * Extracts ports statuses from the telnet response
     *
     * @param portsData telnet response
     * @param ports statistics map to extract data to
     */
    private void extractPortStatus(Map<String, String> ports, String portsData){
        Arrays.stream(portsData.split("\r")).forEach(portDataLine -> {
            if(portDataLine.matches("^(\n)*\\d\\/\\d\\/\\d.+?")) {
                String[] portDataArray = portDataLine.split("  ");
                List<String> portDataList = Arrays.stream(portDataArray).filter(portColumnData -> !portColumnData.isEmpty()).collect(Collectors.toList());

                ports.put(portDataList.get(0).replace("\n", ""), String.valueOf(portDataLine.contains(" Up ")));
            }
        });
    }

    /**
     * Extracts ports statistics from the telnet response
     *
     * @param portsData telnet response
     * @param ports statistics map to extract data to
     */
    private void extractPortStatistics(Map<String, String> ports, String portsData){
        Arrays.stream(portsData.split("\r")).forEach(portDataLine -> {
            if(portDataLine.matches("^(\n)*\\d\\/\\d\\/\\d.+?")) {
                String[] portDataArray = portDataLine.split("  ");
                List<String> portDataList = Arrays.stream(portDataArray).filter(portColumnData -> !portColumnData.isEmpty()).collect(Collectors.toList());
                ports.put("Ports Packets Statistics#Port " + portDataList.get(0) + " Received" , portDataList.get(4));
                ports.put("Ports Packets Statistics#Port " + portDataList.get(0) + " Transmitted" , portDataList.get(3));
            }
        });
    }

    /**
     * Transforms specific telnet responses into key:value data
     *
     * @param separatorPattern pattern that is used to define the separator between key and value, e.g
     *                         Key...........Value - would have it "\\.{2,}", or Key__________Value
     *                         would have it defined as "_{2,}" etc.
     * @param response response to parse
     * @return Map<String, String> that represents the parsed values
     */
    private Map<String, String> extractTelnetResponseProperties(String response, String separatorPattern){
        String[] lines = response.split("\n");
        LinkedHashMap<String, String> responseMap = new LinkedHashMap<>();
        Arrays.stream(lines).forEach(s -> {
            if(Pattern.compile(separatorPattern).matcher(s).find()) {
                String[] line = s.split(separatorPattern);
                responseMap.put(line[0], line[1].trim().replaceAll("\t", ""));
            }
        });
        return responseMap;
    }

}
