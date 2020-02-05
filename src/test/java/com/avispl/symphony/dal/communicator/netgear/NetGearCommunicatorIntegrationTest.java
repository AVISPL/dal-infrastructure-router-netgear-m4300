package com.avispl.symphony.dal.communicator.netgear;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

@Tag("integration")
public class NetGearCommunicatorIntegrationTest {

    static NetGearNASCommunicator netGearCommunicator;

    @BeforeEach
    public void init() throws Exception {
        netGearCommunicator = new NetGearNASCommunicator();
        netGearCommunicator.setProtocol("telnet");
        netGearCommunicator.setHost("***REMOVED***");
        netGearCommunicator.setLogin("dev");
        netGearCommunicator.setPassword("***REMOVED***");
        netGearCommunicator.init();
    }

    @Test
    public void getMultipleStatistics() throws Exception {
        List<Statistics> stat = netGearCommunicator.getMultipleStatistics();
        Assert.assertFalse(stat.isEmpty());
    }

    @Test
    public void controlPropertyStartupPort() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Port 1/0/1");
        controllableProperty.setValue("1");
        netGearCommunicator.controlProperty(controllableProperty);
        Assert.assertEquals(((ExtendedStatistics)netGearCommunicator.getMultipleStatistics().get(0)).getStatistics().get("Port 1/0/1"), "true");
    }

    @Test
    public void controlPropertyShutdownPort() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Port 1/0/1");
        controllableProperty.setValue("0");
        netGearCommunicator.controlProperty(controllableProperty);
        Assert.assertEquals(((ExtendedStatistics)netGearCommunicator.getMultipleStatistics().get(0)).getStatistics().get("Port 1/0/1"), "false");
    }

    @Test
    @Ignore
    public void controlPropertyReload() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Reload");
        netGearCommunicator.controlProperty(controllableProperty);
    }

}
